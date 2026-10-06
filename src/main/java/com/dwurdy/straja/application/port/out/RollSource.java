package com.dwurdy.straja.application.port.out;

/**
 * Randomness source for gameplay rolls. Tests inject a seeded implementation
 * so every draw is deterministic; production binds a server-seeded random.
 * This is the first RNG in the application layer (#247) — rolls stay
 * unit-testable in the hexagonal style instead of reaching for world RNG.
 */
public interface RollSource {
    /** Uniform int in [0, bound); bound must be positive. */
    int nextInt(int bound);

    /** Uniform double in [0.0, 1.0). */
    double nextDouble();

    /** Production source: nondeterministic draws. */
    static RollSource system() {
        java.util.Random random = new java.util.Random();
        return new RollSource() {
            @Override public int nextInt(int bound) { return random.nextInt(bound); }
            @Override public double nextDouble() { return random.nextDouble(); }
        };
    }
}
