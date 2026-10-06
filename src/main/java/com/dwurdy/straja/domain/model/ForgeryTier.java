package com.dwurdy.straja.domain.model;

/**
 * The five forgery quality tiers of #245 — the locked pyramid
 * N1=3% / N2=7% / N3=15% / N4=30% / N5=45%. Every forged artifact carries a
 * tell; the tier only decides who can see it, not whether it exists.
 * N1 spoofs a real serial and is the "legendary" roll — the paper is perfect,
 * the mismatch between item and registry record betrays it.
 */
public enum ForgeryTier {
    /** Near-perfect: claims an existing authentic serial (or a flawless one). */
    N1,
    /** Fine: format-perfect serial slightly beyond the registry's allocation. */
    N2,
    /** Passable: valid format, far-fetched serial number. */
    N3,
    /** Rough: visible format errors (wrong separators/charset). */
    N4,
    /** Crude: absurd marking, machine-caught on sight. */
    N5;

    /** Default locked pyramid weights, in enum order N1..N5. */
    public static final int[] DEFAULT_WEIGHTS = {3, 7, 15, 30, 45};

    public static ForgeryTier parse(String value) {
        if (value == null) return null;
        try {
            return ForgeryTier.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Draws a tier from cumulative weights. Any non-positive or malformed
     * weights fall back to the locked pyramid so a bad config can't produce
     * authentic-grade output. {@code nextInt} supplies uniform draws in
     * [0, bound) — callers pass the application-layer RNG port's
     * {@code nextInt} reference, keeping the domain free of port types.
     */
    public static ForgeryTier roll(java.util.function.IntUnaryOperator nextInt,
                                   java.util.List<Integer> weights) {
        int[] w = validWeights(weights);
        int total = 0;
        for (int weight : w) total += weight;
        int draw = nextInt.applyAsInt(total);
        for (ForgeryTier tier : values()) {
            draw -= w[tier.ordinal()];
            if (draw < 0) return tier;
        }
        return N5;
    }

    public static int[] validWeights(java.util.List<Integer> weights) {
        if (weights == null || weights.size() != values().length) return DEFAULT_WEIGHTS;
        int[] w = new int[values().length];
        long sum = 0;
        for (int i = 0; i < w.length; i++) {
            Integer v = weights.get(i);
            // Reject non-positive entries AND sums that would overflow the
            // draw bound — a bad override must never throw mid-take.
            if (v == null || v <= 0 || (sum += v) > Integer.MAX_VALUE) {
                return DEFAULT_WEIGHTS;
            }
            w[i] = v;
        }
        return w;
    }
}
