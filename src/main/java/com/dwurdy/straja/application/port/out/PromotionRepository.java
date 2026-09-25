package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.PromotionStore;

public interface PromotionRepository {
    PromotionStore read();
    void write(PromotionStore store);
}
