package com.dwurdy.straja.adapter.out.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class WhipAssetTest {
    private static final Path TEXTURE = Path.of(
            "src/main/resources/assets/straja/textures/item/whip.png");
    private static final Path METADATA = Path.of(
            "src/main/resources/assets/straja/textures/item/whip.png.mcmeta");

    @Test
    void whipTextureHasFourStackedAnimationFrames() throws IOException {
        BufferedImage texture = ImageIO.read(TEXTURE.toFile());
        assertEquals(32, texture.getWidth());
        assertEquals(128, texture.getHeight());
        assertTrue(Files.readString(METADATA).contains("[0, 1, 2, 3, 2, 1]"));
    }
}
