package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.CampaignStore;

public interface CampaignRepository {
    CampaignStore read();
    void write(CampaignStore store);
}
