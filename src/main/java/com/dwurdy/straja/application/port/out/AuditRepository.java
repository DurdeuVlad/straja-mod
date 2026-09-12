package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.AuditEntry;
import java.util.List;

/** Append-only audit log with bounded retention. */
public interface AuditRepository {
    void append(AuditEntry entry);

    List<AuditEntry> entries();

    List<AuditEntry> tail(int count);
}
