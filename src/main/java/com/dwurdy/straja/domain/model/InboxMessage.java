package com.dwurdy.straja.domain.model;

/** Commissioner inbox entry (reports, requests, messages). */
public class InboxMessage {
    public String type = "";
    public String sender = "";
    public String text = "";
    public long at;
    public boolean read;

    public InboxMessage() {}
}
