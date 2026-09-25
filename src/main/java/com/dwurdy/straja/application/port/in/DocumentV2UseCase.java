package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.domain.model.DocumentRedemption;

public interface DocumentV2UseCase {
    record Redemption(boolean accepted, boolean replayed, String reason, DocumentRedemption redemption) {}
}
