package com.dwurdy.straja.application.port.out;

import java.util.concurrent.atomic.AtomicLong;

/** System clock plus a test-controlled offset. Only used when test commands are enabled. */
public final class MutableClock implements Clock {
    private final AtomicLong offsetMs = new AtomicLong();

    @Override public long nowMillis() {
        return System.currentTimeMillis() + offsetMs.get();
    }

    public void advance(long millis) {
        offsetMs.addAndGet(millis);
    }
}
