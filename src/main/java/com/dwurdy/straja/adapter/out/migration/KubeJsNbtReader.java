package com.dwurdy.straja.adapter.out.migration;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal gzipped-NBT reader for KubeJS persistentData files. KubeJS stores
 * every value as a string tag holding JSON; this reader extracts only string
 * tags (recursively) and ignores all other tag types. Pure Java — no Minecraft
 * classes — so it stays unit-testable outside a running server.
 */
public final class KubeJsNbtReader {

    private static final int TAG_END = 0, TAG_BYTE = 1, TAG_SHORT = 2, TAG_INT = 3,
            TAG_LONG = 4, TAG_FLOAT = 5, TAG_DOUBLE = 6, TAG_BYTE_ARRAY = 7,
            TAG_STRING = 8, TAG_LIST = 9, TAG_COMPOUND = 10,
            TAG_INT_ARRAY = 11, TAG_LONG_ARRAY = 12;

    private KubeJsNbtReader() {}

    /**
     * Reads the top-level compound of a gzipped NBT file into a flat
     * {@code key -> raw JSON string} map. Nested compounds are descended into
     * with dotted keys (e.g. {@code KubeJSPersistentData.straja_state}).
     */
    public static Map<String, String> readStrings(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
            int rootType = in.readByte();
            if (rootType != TAG_COMPOUND) throw new IOException("not an NBT compound: " + file);
            in.readUTF(); // root name
            Map<String, String> out = new LinkedHashMap<>();
            readCompound(in, "", out);
            return out;
        }
    }

    /** Same as {@link #readStrings} but for uncompressed NBT input. */
    public static Map<String, String> readStringsUncompressed(InputStream raw) throws IOException {
        try (DataInputStream in = new DataInputStream(raw)) {
            int rootType = in.readByte();
            if (rootType != TAG_COMPOUND) throw new IOException("not an NBT compound");
            in.readUTF();
            Map<String, String> out = new LinkedHashMap<>();
            readCompound(in, "", out);
            return out;
        }
    }

    private static void readCompound(DataInputStream in, String prefix, Map<String, String> out) throws IOException {
        while (true) {
            int type = in.readByte();
            if (type == TAG_END) return;
            String name = in.readUTF();
            String key = prefix.isEmpty() ? name : prefix + "." + name;
            if (type == TAG_STRING) {
                out.put(key, in.readUTF());
            } else if (type == TAG_COMPOUND) {
                readCompound(in, key, out);
            } else {
                skip(in, type);
            }
        }
    }

    private static void skip(DataInputStream in, int type) throws IOException {
        switch (type) {
            case TAG_BYTE -> skipFully(in, 1);
            case TAG_SHORT -> skipFully(in, 2);
            case TAG_INT, TAG_FLOAT -> skipFully(in, 4);
            case TAG_LONG, TAG_DOUBLE -> skipFully(in, 8);
            case TAG_BYTE_ARRAY -> skipFully(in, in.readInt());
            case TAG_STRING -> in.readUTF();
            case TAG_LIST -> {
                int elemType = in.readByte();
                int count = in.readInt();
                if (count < 0) throw new IOException("negative NBT list size " + count);
                for (int i = 0; i < count; i++) skip(in, elemType);
            }
            case TAG_COMPOUND -> readCompound(in, "", new LinkedHashMap<>());
            case TAG_INT_ARRAY -> skipFully(in, (long) in.readInt() * 4);
            case TAG_LONG_ARRAY -> skipFully(in, (long) in.readInt() * 8);
            default -> throw new IOException("unknown NBT tag type " + type);
        }
    }

    /**
     * Guaranteed skip: {@code skipBytes} may return early and silently desync
     * the parser on truncated input; {@code skipNBytes} throws EOFException
     * instead, so malformed files fail loudly.
     */
    private static void skipFully(DataInputStream in, long bytes) throws IOException {
        if (bytes < 0) throw new IOException("negative NBT payload length " + bytes);
        in.skipNBytes(bytes);
    }
}
