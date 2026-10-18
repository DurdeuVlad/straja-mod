package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.JobStore;

/** FUN-10 durable store boundary for job runs, cooldowns, and the trade ledger. */
public interface JobRepository {
    JobStore read();
    void write(JobStore data);
}
