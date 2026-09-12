package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.TestStore;

public interface TestRepository {
    TestStore read();

    void write(TestStore store);
}
