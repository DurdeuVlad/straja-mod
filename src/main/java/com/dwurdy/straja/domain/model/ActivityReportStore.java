package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Report register: all filed reports plus each member's period anchor —
 * the timestamp from which the current reporting interval runs. The anchor
 * self-stamps on first contact so a member always gets a full interval to
 * file their first report.
 */
public class ActivityReportStore {
    public Map<String, ActivityReport> reports = new HashMap<>();
    /** member uuid -> anchor millis; a fresh interval starts at each filing. */
    public Map<String, Long> anchors = new HashMap<>();
    public int nextId = 1;

    /** Most recently submitted report by this member, or null. */
    public ActivityReport latestFor(String uuid) {
        ActivityReport latest = null;
        for (ActivityReport r : reports.values()) {
            if (r != null && uuid.equals(r.authorUuid)
                    && (latest == null || r.submittedAt > latest.submittedAt)) {
                latest = r;
            }
        }
        return latest;
    }

    /** The member's report still open for review work (SUBMITTED/COMISAR_REVIEW) or RETURNED. */
    public ActivityReport openFor(String uuid) {
        for (ActivityReport r : reports.values()) {
            if (r != null && uuid.equals(r.authorUuid)
                    && (ActivityReport.SUBMITTED.equals(r.status)
                            || ActivityReport.RETURNED.equals(r.status)
                            || ActivityReport.COMISAR_REVIEW.equals(r.status))) {
                return r;
            }
        }
        return null;
    }

    /** Reports awaiting the Comisar's decision, oldest first. */
    public List<ActivityReport> pendingReview() {
        List<ActivityReport> out = new ArrayList<>();
        for (ActivityReport r : reports.values()) {
            if (r != null && ActivityReport.SUBMITTED.equals(r.status)) out.add(r);
        }
        out.sort((a, b) -> Long.compare(a.submittedAt, b.submittedAt));
        return out;
    }
}
