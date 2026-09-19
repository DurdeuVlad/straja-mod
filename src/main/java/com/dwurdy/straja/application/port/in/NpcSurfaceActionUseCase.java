package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.domain.model.NpcActionRequest;
import com.dwurdy.straja.domain.model.NpcActionResult;

/** Authoritative inbound boundary for provider-submitted NPC actions. */
public interface NpcSurfaceActionUseCase {
    NpcActionResult submit(NpcActionRequest request);
}
