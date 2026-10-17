package com.dwurdy.straja.domain.model;

import java.util.List;

/**
 * The shared field specs for every review form — each decision flow declared
 * its fields twice before (native form fields and NPC-surface input fields),
 * and the copies drifted: different labels, different caps, even different
 * field ids. {@link NpcSurfaceAction.InputField} carries the provider-neutral
 * spec; {@code FormSessionUseCase.fieldOf} adapts it into session fields, so
 * the ids, labels, caps and requiredness are defined once here. The decision
 * word hinted in each label comes from {@link DecisionWords}.
 */
public final class ReviewForm {

    /** Petition/join review: approve or reject ({@link DecisionWords#APPROVE}/{@link DecisionWords#DENY}). */
    public static final NpcSurfaceAction.InputField DECISION =
            new NpcSurfaceAction.InputField(FormField.DECISION, "Decizie (aproba / respinge)", 16, true);
    /** Complaint review ({@link DecisionWords#COMPLAINT_RESOLVE}/{@link DecisionWords#COMPLAINT_RETURN}/{@link DecisionWords#COMPLAINT_DISMISS}). */
    public static final NpcSurfaceAction.InputField DECISION_COMPLAINT =
            new NpcSurfaceAction.InputField(FormField.DECISION, "Decizie (approve / return / dismiss)", 16, true);
    /** Weekly report review ({@link DecisionWords#REPORT_ACCEPT}/{@link DecisionWords#REPORT_RETURN}/{@link DecisionWords#REPORT_SUMMON}). */
    public static final NpcSurfaceAction.InputField DECISION_REPORT =
            new NpcSurfaceAction.InputField(FormField.DECISION, "Decizie (accept / return / call)", 16, true);
    /** Audience review ({@link DecisionWords#AUDIENCE_RESOLVE}/{@link DecisionWords#AUDIENCE_DISMISS}). */
    public static final NpcSurfaceAction.InputField DECISION_AUDIENCE =
            new NpcSurfaceAction.InputField(FormField.DECISION, "Decizie (resolve / dismiss)", 16, true);
    /** Fine-appeal review ({@link DecisionWords#FINE_UPHOLD}/{@link DecisionWords#FINE_REDUCE}/{@link DecisionWords#FINE_VOID}). */
    public static final NpcSurfaceAction.InputField DECISION_APPEAL =
            new NpcSurfaceAction.InputField(FormField.DECISION, "Decizie (uphold / reduce / void)", 16, true);
    /** Optional note sent back to the requester with the decision. */
    public static final NpcSurfaceAction.InputField NOTE =
            new NpcSurfaceAction.InputField(FormField.NOTE, "Motiv pentru solicitant",
                    Text.REASON_LIMIT, false);
    /** Complaint review: the reward granted on resolve. */
    public static final NpcSurfaceAction.InputField REWARD =
            new NpcSurfaceAction.InputField(FormField.REWARD, "Recompensă", 10, true);
    /** Fine-appeal review: the reduced amount, only read on reduce. */
    public static final NpcSurfaceAction.InputField REDUCED_AMOUNT =
            new NpcSurfaceAction.InputField(FormField.REDUCED_AMOUNT, "Sumă redusă", 10, false);

    private ReviewForm() {}

    /** Petition/join review: decision + note. */
    public static List<NpcSurfaceAction.InputField> decisionFields() {
        return List.of(DECISION, NOTE);
    }

    /** Complaint review: decision + reward. */
    public static List<NpcSurfaceAction.InputField> complaintFields() {
        return List.of(DECISION_COMPLAINT, REWARD);
    }

    /** Weekly report review: decision + note. */
    public static List<NpcSurfaceAction.InputField> reportFields() {
        return List.of(DECISION_REPORT, NOTE);
    }

    /** Audience review: decision + note. */
    public static List<NpcSurfaceAction.InputField> audienceFields() {
        return List.of(DECISION_AUDIENCE, NOTE);
    }

    /** Fine-appeal review: decision + reduced amount + reason (cap is policy-driven). */
    public static List<NpcSurfaceAction.InputField> fineAppealFields(int reasonLimit) {
        return List.of(DECISION_APPEAL, REDUCED_AMOUNT,
                new NpcSurfaceAction.InputField(FormField.REASON, "Motiv", reasonLimit, true));
    }
}
