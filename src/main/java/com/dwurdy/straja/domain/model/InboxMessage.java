package com.dwurdy.straja.domain.model;

/** Commissioner inbox entry (reports, requests, messages). */
public class InboxMessage {
    /** Stable id for review/resolve flows. Legacy messages may leave this blank. */
    public String id = "";
    public String type = "";
    public String sender = "";
    /** UUID is authoritative when present; sender remains the human-readable snapshot. */
    public String senderUuid = "";
    public String text = "";
    public long at;
    public boolean read;
    /** OPEN | ACCEPTED | RETURNED | CALLED_IN | RESOLVED (type-dependent). */
    public String status = "OPEN";
    public String reviewText = "";

    public InboxMessage() {}
}
