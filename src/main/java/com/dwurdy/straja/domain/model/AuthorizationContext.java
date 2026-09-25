package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/** Complete input to the common V2 authorization predicate. */
public class AuthorizationContext {
    public String actorUuid = "";
    public String subjectUuid = "";
    public String capability = "";
    public String stationId = "";
    public String jurisdiction = "";
    public String resourceType = "";
    public String operationId = "";
    public String beneficiaryUuid = "";
    public Long expectedSubjectVersion;
    public Long actualSubjectVersion;
    public long requestedAmount;
    public boolean requiresIndependentApproval;
    public List<String> approvalChain = new ArrayList<>();

    public static AuthorizationContext of(String actorUuid, String capability) {
        AuthorizationContext context = new AuthorizationContext();
        context.actorUuid = actorUuid == null ? "" : actorUuid;
        context.capability = capability == null ? "" : capability;
        return context;
    }
}
