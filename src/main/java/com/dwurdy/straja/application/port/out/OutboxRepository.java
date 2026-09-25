package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.OutboxEvent;
import java.util.List;

public interface OutboxRepository {
    List<OutboxEvent> read();
    void write(List<OutboxEvent> events);
}
