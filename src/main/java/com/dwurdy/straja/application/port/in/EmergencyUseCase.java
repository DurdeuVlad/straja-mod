package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;

/**
 * §25 emergency system: all-hands urgency calls and the sustained emergency
 * mode (hazard pay, extra patrol rounds). Implemented by the authoritative
 * emergency service; adapters depend only on this port.
 */
public interface EmergencyUseCase {

    /** Broadcasts a short-lived urgency call to every online Straja member. */
    void alert(PlayerGateway actor, String message);

    /** Dismisses a live urgency call early. */
    void clearUrgency(PlayerGateway actor);

    /** Starts the sustained emergency mode (hazard pay + extra patrol rounds). */
    void start(PlayerGateway actor, Double payMultiplier, Integer rounds, String reason);

    /** Ends the sustained emergency mode; accrued salary is untouched. */
    void end(PlayerGateway actor);

    /** Shows the live urgency/emergency state. Readable by anyone. */
    void status(PlayerGateway actor);

    /** Login hook: delivers a still-live urgency call to a member joining late. */
    void deliverUrgency(PlayerGateway player);
}
