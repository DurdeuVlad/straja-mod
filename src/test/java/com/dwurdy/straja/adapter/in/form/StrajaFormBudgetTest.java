package com.dwurdy.straja.adapter.in.form;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.port.in.FormSessionUseCase;
import com.dwurdy.straja.application.port.in.FormSessionUseCase.Action;
import com.dwurdy.straja.application.port.in.FormSessionUseCase.Field;
import com.dwurdy.straja.application.port.in.FormSessionUseCase.Request;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Field-count contract for shipped forms. {@link FormSessionUseCase#MAX_FIELDS}
 * is the single wire bound shared by the session service, the menu codec and
 * the payload decoder; the BOLO_CREATE regression sent five fields against a
 * four-field cap, so every {@code new FormSessionUseCase.Request(} construction
 * site in production sources is enumerated here and asserted to stay within
 * the bound.
 */
class StrajaFormBudgetTest {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final String REQUEST = "new FormSessionUseCase.Request(";
    private static final String FIELD = "new FormSessionUseCase.Field(";

    @Test
    void everyShippedFormRequestFitsTheFieldBudget() throws IOException {
        int requests = 0;
        List<Path> files;
        try (var stream = Files.walk(MAIN_SOURCES)) {
            files = stream.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path file : files) {
            String src = Files.readString(file);
            for (int at = src.indexOf(REQUEST); at >= 0; requests++) {
                int end = matchingParen(src, at + REQUEST.length() - 1);
                int fields = 0;
                for (int f = at; (f = src.indexOf(FIELD, f)) >= 0 && f < end;
                        fields++, f += FIELD.length());
                assertTrue(fields <= FormSessionUseCase.MAX_FIELDS,
                        file + " request at offset " + at + " sends " + fields
                                + " fields over the " + FormSessionUseCase.MAX_FIELDS
                                + "-field wire bound");
                at = src.indexOf(REQUEST, end);
            }
        }
        assertTrue(requests >= 40,
                "the scan must cover the shipped form sites, found " + requests);
    }

    @Test
    void requestRejectsFieldOverflowAtConstruction() {
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i <= FormSessionUseCase.MAX_FIELDS; i++) {
            fields.add(new Field("f" + i, "Label " + i, 10, false));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new Request(Action.OTHER_REQUEST, "", "T", "P", fields));
    }

    /** Index of the ')' matching the '(' at {@code open}. */
    private static int matchingParen(String src, int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        throw new IllegalArgumentException("unbalanced parens at " + open);
    }
}
