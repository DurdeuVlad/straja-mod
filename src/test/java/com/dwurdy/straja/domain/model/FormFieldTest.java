package com.dwurdy.straja.domain.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.port.in.FormSessionUseCase;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the producer↔consumer field-id contract: every declared id is part
 * of the single {@link FormField} vocabulary and valid on both declaration
 * paths, so a mismatched or renamed id fails here instead of silently
 * losing a form submission.
 */
class FormFieldTest {

    private static Set<String> vocabularyIds() throws Exception {
        Set<String> ids = new HashSet<>();
        for (var field : FormField.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                ids.add((String) field.get(null));
            }
        }
        return ids;
    }

    @Test
    void everyVocabularyIdIsValidOnBothDeclarationPaths() throws Exception {
        Set<String> ids = vocabularyIds();
        assertTrue(ids.size() > 40, "the vocabulary should cover the whole form surface");
        for (String id : ids) {
            // The InputField constructor enforces the kebab-case key pattern.
            assertDoesNotThrow(
                    () -> new NpcSurfaceAction.InputField(id, "label", 1, true),
                    "id not usable as a surface field key: " + id);
        }
    }

    @Test
    void everyReviewFormFieldComesFromTheVocabulary() throws Exception {
        Set<String> ids = vocabularyIds();
        List<List<NpcSurfaceAction.InputField>> factories = List.of(
                ReviewForm.decisionFields(),
                ReviewForm.complaintFields(),
                ReviewForm.reportFields(),
                ReviewForm.audienceFields(),
                ReviewForm.fineAppealFields(240));
        for (var field : ReviewForm.class.getDeclaredFields()) {
            if (field.getType() == NpcSurfaceAction.InputField.class) {
                var spec = (NpcSurfaceAction.InputField) field.get(null);
                assertTrue(ids.contains(spec.key()),
                        "ReviewForm key missing from FormField: " + spec.key());
            }
        }
        for (var fields : factories) {
            for (var spec : fields) {
                assertTrue(ids.contains(spec.key()),
                        "factory key missing from FormField: " + spec.key());
            }
        }
    }

    @Test
    void petitionReviewFormDeclaresTheIdsTheRouterReads() {
        var request = FormSessionUseCase.decisionReview(
                FormSessionUseCase.Action.PETITION_REVIEW, "id_1", "id");
        assertEquals(
                List.of(FormField.DECISION, FormField.NOTE),
                request.fields().stream().map(FormSessionUseCase.Field::id).toList());
    }
}
