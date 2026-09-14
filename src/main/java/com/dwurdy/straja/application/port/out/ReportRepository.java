package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ActivityReportStore;

public interface ReportRepository {
    ActivityReportStore read();

    void write(ActivityReportStore store);
}
