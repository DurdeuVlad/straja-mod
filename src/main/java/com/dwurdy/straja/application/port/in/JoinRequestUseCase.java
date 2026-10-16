package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.domain.model.JoinRequest;

public interface JoinRequestUseCase {
    JoinRequest submit(String subjectUuid, String profession, String reason);
    java.util.List<JoinRequest> pending();
    JoinRequest find(String requestId);
    JoinRequest findOpen(String subjectUuid);
    JoinRequest approve(String actorUuid, String requestId, long expectedVersion);
    JoinRequest reject(String actorUuid, String requestId, long expectedVersion, String reason);
    void deliverOutcome(com.dwurdy.straja.application.port.out.PlayerGateway player);
}
