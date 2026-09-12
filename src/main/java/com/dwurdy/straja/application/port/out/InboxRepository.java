package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.InboxMessage;
import java.util.List;

/** Commissioner inbox. */
public interface InboxRepository {
    List<InboxMessage> read();

    void write(List<InboxMessage> messages);
}
