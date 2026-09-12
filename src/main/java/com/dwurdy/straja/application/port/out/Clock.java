package com.dwurdy.straja.application.port.out;

/** Time source. Tests inject a fixed/mutable clock; production uses wall time. */
public interface Clock {
    long nowMillis();

    static Clock system() {
        return System::currentTimeMillis;
    }
}
