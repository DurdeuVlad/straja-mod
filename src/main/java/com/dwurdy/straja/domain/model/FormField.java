package com.dwurdy.straja.domain.model;

/**
 * The single vocabulary for form-field ids — the implicit contract between
 * the forms that declare them ({@code FormSessionUseCase.Field},
 * {@link NpcSurfaceAction.InputField}, content profiles) and the consumers
 * that read submitted values ({@code FormSubmissionRouter}, presentation
 * runtime). Every constant spells the id the way {@link
 * NpcSurfaceAction.InputField} accepts — lower-case kebab — so the same id
 * is valid on both declaration paths; earlier camelCase producer spellings
 * are retired, not tolerated.
 */
public final class FormField {

    public static final String ACCUSED = "accused";
    public static final String ACTIVITY = "activity";
    public static final String AMOUNT = "amount";
    public static final String ANSWER = "answer";
    public static final String AUTHORITY = "authority";
    public static final String CASE_ID = "case-id";
    public static final String CATEGORY = "category";
    public static final String CONTENT = "content";
    public static final String COUNT = "count";
    public static final String CUSTODIAN = "custodian";
    public static final String DAYS = "days";
    public static final String DECISION = "decision";
    public static final String DELTA = "delta";
    public static final String DEPARTMENT = "department";
    public static final String DESCRIPTION = "description";
    public static final String DETAILS = "details";
    public static final String DETAINEE = "detainee";
    public static final String FACTION = "faction";
    public static final String HOURS = "hours";
    public static final String INCIDENT_ID = "incident-id";
    public static final String INCIDENTS = "incidents";
    public static final String KEY = "key";
    public static final String LAW = "law";
    public static final String MAX_ASSIGNEES = "max-assignees";
    public static final String MESSAGE = "message";
    public static final String MINIMUM_RANK = "minimum-rank";
    public static final String MINUTES = "minutes";
    public static final String MISSIONS = "missions";
    public static final String MULTIPLIER = "multiplier";
    public static final String NAME = "name";
    public static final String NOTE = "note";
    public static final String NOTES = "notes";
    public static final String OBJECTIVE = "objective";
    public static final String QUESTION_ID = "question-id";
    public static final String RANK = "rank";
    public static final String REASON = "reason";
    public static final String RECIPIENTS = "recipients";
    public static final String REDUCED_AMOUNT = "reduced-amount";
    public static final String REPORT = "report";
    public static final String RESOLUTION = "resolution";
    public static final String REWARD = "reward";
    public static final String RISK = "risk";
    public static final String ROUNDS = "rounds";
    public static final String SENTENCE_ID = "sentence-id";
    public static final String SKIN = "skin";
    public static final String XP = "xp";
    public static final String OPEN = "open";
    public static final String SLOT = "slot";
    public static final String START = "start";
    public static final String SUBJECT = "subject";
    public static final String TARGET = "target";
    public static final String TARGETS = "targets";
    public static final String TITLE = "title";
    public static final String TYPE = "type";
    public static final String VALUE = "value";

    private FormField() {}
}
