package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * The malformed-marking generator: given a tier, produce the physical mark
 * printed on a forged item. The mark IS the tell — each tier's defect class
 * differs in subtlety, not in existence:
 *
 * <ul>
 *   <li>N1 — spoofs a real allocated serial; flawless format, betrays itself
 *       only when the registry record disagrees with the item that carries it.
 *   <li>N2 — format-perfect serial slightly beyond allocation ("the seal's
 *       micro-print sits a half-point too light").
 *   <li>N3 — valid format, far-fetched number that no registry contains.
 *   <li>N4 — visible format errors: wrong separators, stray characters.
 *   <li>N5 — absurd marks from a curated pool; machine-caught on sight.
 * </ul>
 *
 * Every method is pure: the RNG is the only entropy and the caller supplies
 * the serial space (next unallocated number + existing authentic serials).
 */
public final class ForgeryMarking {

    /** Marks so crude no machine needs a registry check to shred them. */
    private static final List<String> ABSURD_POOL = List.of(
            "#RUSTY-GUN-99", "#ARMY-STAMP-1", "#TOTALLY-REAL-7",
            "#GUNS4U-13", "#OFFICIAL-BOOM-5", "#LEGIT-SERIES-0");

    /** Visible malformations applied to a plausible serial for the N4 tier. */
    private static String malform(String serial, int variant) {
        return switch (variant % 5) {
            case 0 -> serial + "_";            // trailing separator: #RC-15_
            case 1 -> serial.replace("-", "");  // missing separators: #RC15
            case 2 -> serial + "A";            // stray letter: #RC-15A
            case 3 -> serial.toLowerCase(java.util.Locale.ROOT); // #rc-15
            default -> serial.replaceFirst("-", "--");           // #RC--15
        };
    }

    /**
     * @param tier         the rolled quality tier
     * @param nextInt      uniform draws in [0, bound) — callers pass the
     *                     application RNG port's {@code nextInt} reference so
     *                     the domain stays free of port types
     * @param serialPrefix configured authentic prefix, e.g. "RC-"
     * @param nextSerial   the registry's next unallocated number
     * @param authenticSerials serials already claimed by authentic records
     *                         (used by N1 to spoof a real mark)
     */
    public static String markingFor(ForgeryTier tier, IntUnaryOperator nextInt,
                                    String serialPrefix, long nextSerial,
                                    List<String> authenticSerials) {
        String prefix = serialPrefix == null || serialPrefix.isBlank() ? "RC-" : serialPrefix;
        long next = Math.max(1, nextSerial);
        return switch (tier) {
            case N1 -> {
                // Spoof a genuine allocated serial when the registry has one —
                // the only catch is item-vs-record mismatch. Fresh worlds fall
                // back to a serial one step beyond allocation.
                if (authenticSerials != null && !authenticSerials.isEmpty()) {
                    List<String> clean = new ArrayList<>();
                    for (String s : authenticSerials) {
                        if (s != null && s.startsWith(prefix)) clean.add(s);
                    }
                    if (!clean.isEmpty()) {
                        yield "#" + clean.get(nextInt.applyAsInt(clean.size()));
                    }
                }
                yield "#" + prefix + (next + 1);
            }
            case N2 -> "#" + prefix + (next + 1 + nextInt.applyAsInt(9));
            case N3 -> "#" + prefix + Math.max(1,
                    (next * (10L + nextInt.applyAsInt(40)) + nextInt.applyAsInt(97))
                            % 1_000_000_000_000L);
            case N4 -> "#" + malform(prefix + (1 + nextInt.applyAsInt((int) Math.min(next, 98) + 1)),
                    nextInt.applyAsInt(5));
            case N5 -> ABSURD_POOL.get(nextInt.applyAsInt(ABSURD_POOL.size()));
        };
    }

    /** The serial the item *claims* to carry — for N1/N2/N3 the marked serial,
     * for N4/N5 the mark itself is the claim (garbage in, garbage out). */
    public static String claimedSerialFor(String marking) {
        if (marking == null || marking.isBlank()) return "";
        return marking.startsWith("#") ? marking.substring(1) : marking;
    }

    /**
     * Mark readability class for the detection milestone: what a bare look at
     * the marking itself reveals, before any registry cross-check.
     */
    public enum MarkClass { ABSURD, MALFORMED, PLAUSIBLE }

    public static MarkClass classify(String marking, String serialPrefix) {
        if (marking == null || marking.isBlank()) return MarkClass.ABSURD;
        String prefix = serialPrefix == null || serialPrefix.isBlank() ? "RC-" : serialPrefix;
        if (!marking.startsWith("#")) return MarkClass.ABSURD;
        String body = marking.substring(1);
        if (!body.startsWith(prefix)) return MarkClass.ABSURD;
        String digits = body.substring(prefix.length());
        if (digits.isEmpty()) return MarkClass.MALFORMED;
        for (char c : digits.toCharArray()) if (!Character.isDigit(c)) return MarkClass.MALFORMED;
        return MarkClass.PLAUSIBLE;
    }

    private ForgeryMarking() {}
}
