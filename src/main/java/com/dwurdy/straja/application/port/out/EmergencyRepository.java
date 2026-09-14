package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.EmergencyState;

/** §25 persisted emergency state (urgency call + sustained emergency mode). */
public interface EmergencyRepository {
    EmergencyState read();

    void write(EmergencyState state);
}
