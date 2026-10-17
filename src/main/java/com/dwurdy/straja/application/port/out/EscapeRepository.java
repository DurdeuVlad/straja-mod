package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.EscapeStore;

/** FUN-8/9-style durable store boundary for escape-prep records. */
public interface EscapeRepository {
    EscapeStore read();
    void write(EscapeStore data);
}
