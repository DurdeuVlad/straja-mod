package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.DocumentStore;

public interface DocumentRepository {
    DocumentStore read();
    void write(DocumentStore store);
}
