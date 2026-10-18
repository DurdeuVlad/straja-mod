package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * FUN-6: a dispatched call — a targeted, time-bounded work item produced
 * from a real offense event. Dispatch routes attention; the underlying
 * wanted/fugitive/BOLO/incident records remain the authority.
 */
public class DispatchCall {
    public String id = "";
    /** INCIDENT | BOLO | FUGITIVE | BOUNTY — traces to the source record id. */
    public String sourceType = "";
    public String sourceId = "";
    public String title = "";
    public String detail = "";
    public String dimension = "";
    public double x;
    public double y;
    public double z;
    public String placeLabel = "";
    public String subjectUuid = "";
    public String subjectName = "";
    /** ROUTINE | URGENT. */
    public String severity = "ROUTINE";
    /** ENLISTED | OFFICER | ALL — the band currently allowed to answer. */
    public String band = "ENLISTED";
    /** OPEN | ACCEPTED | EXPIRED | CLOSED. */
    public String status = "OPEN";
    /** Members this band-push was delivered to; declines drop out of it. */
    public List<String> offeredTo = new ArrayList<>();
    /** Members who refused — excluded from every later push of this call. */
    public List<String> declined = new ArrayList<>();
    public String acceptedByUuid = "";
    public String acceptedByName = "";
    /** Player whose action produced the offense — never earns response credit
     *  from their own call (FUN-5 precedent). */
    public String createdByUuid = "";
    /** Player the call is about — a subject cannot hunt themselves. */
    public long acceptedAt;
    public long createdAt;
    /** When the current band expires the call widens or closes. */
    public long bandExpiresAt;
}
