package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OperationRecord {
    public String operationId = "";
    public String idempotencyKey = "";
    public String kind = "";
    public String actorUuid = "";
    public String subjectUuid = "";
    public String correlationId = "";
    public List<String> aggregateRefs = new ArrayList<>();
    public Map<String, Long> expectedVersions = new LinkedHashMap<>();
    public OperationStatus status = OperationStatus.PREPARED;
    public String result = "";
    public List<String> outboundEvents = new ArrayList<>();
    public int attempts;
    public long createdAt;
    public long updatedAt;

    public enum OperationStatus {
        PREPARED, DOMAIN_COMMITTED, SIDE_EFFECT_PENDING, COMPLETED,
        RETRYABLE, REVIEW, ABORTED
    }
}
