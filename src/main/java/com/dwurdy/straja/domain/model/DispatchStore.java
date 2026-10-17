package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/** FUN-6: durable dispatch-call aggregate. */
public class DispatchStore {
    public int nextId = 1;
    public List<DispatchCall> calls = new ArrayList<>();

    public String nextCallId() {
        return "DC-" + (nextId++);
    }

    public DispatchCall find(String id) {
        if (id == null) return null;
        ensureCalls();
        for (DispatchCall call : calls) {
            if (call != null && id.equals(call.id)) return call;
        }
        return null;
    }

    /** Gson skips field initializers — a payload missing lists nulls them out. */
    public void ensureCalls() {
        if (calls == null) calls = new ArrayList<>();
        for (DispatchCall call : calls) {
            if (call == null) continue;
            if (call.offeredTo == null) call.offeredTo = new ArrayList<>();
            if (call.declined == null) call.declined = new ArrayList<>();
            if (call.dimension == null) call.dimension = "";
            if (call.status == null) call.status = "OPEN";
            if (call.band == null) call.band = "ENLISTED";
            if (call.severity == null) call.severity = "ROUTINE";
            if (call.sourceType == null) call.sourceType = "";
            if (call.sourceId == null) call.sourceId = "";
            if (call.subjectUuid == null) call.subjectUuid = "";
            if (call.subjectName == null) call.subjectName = "";
            if (call.createdByUuid == null) call.createdByUuid = "";
            if (call.title == null) call.title = "";
            if (call.detail == null) call.detail = "";
            if (call.placeLabel == null) call.placeLabel = "";
            if (call.acceptedByUuid == null) call.acceptedByUuid = "";
            if (call.acceptedByName == null) call.acceptedByName = "";
        }
    }
}
