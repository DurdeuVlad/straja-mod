package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.ArrayList;
import java.util.List;

/**
 * #248 / #245 M3 — the patrol book content engine. Each world-day rotates a
 * deterministic slice of the defect catalogue, so today's edition shows
 * today's exemplars; a book stamped yesterday keeps yesterday's pages —
 * editions never auto-update, they go stale.
 *
 * <p>The guide deliberately describes <i>visual tells</i>, never tier names,
 * roll weights, or thresholds — a confiscated book must not leak the
 * detection mechanic to a forger who reads it.</p>
 */
public final class PatrolGuideService {

    /**
     * Defect exemplars an officer can check by eye alone. Mirrors the
     * malformed-mark families the generator actually emits (wrong/lost
     * separators, stray characters, letter-case breaks, absurd stamps) —
     * phrased as field tells, not mechanics.
     */
    private static final List<String> DEFECTS = List.of(
            "Serie cu separator lipsă — citești ceva ca #RC15 în loc de #RC-15.",
            "Serie cu o literă în plus la coadă — #RC-15A nu există în registru.",
            "Serie cu cratimă dublă — #RC--15. Registrul nu emite așa ceva.",
            "Serie scrisă cu litere mici — #rc-15. Tiparul oficial e întotdeauna cu majuscule.",
            "Marcă cu caracter de umplutură la final — #RC-15_ trădează o matriță falsă.",
            "Serie care depășește numărul pe care registrul l-a emis vreodată — întreabă arhivistul.",
            "Stampilă „mărturisitoare” — marcaje ca #GUNS4U-13 sau #TOTALLY-REAL-7 sunt falși grosolan.",
            "Serie reală, obiect greșit — marca e curată, dar registrul spune altceva. Asta e treabă de expert.");

    /** How many exemplars each edition prints. */
    public static final int DEFECTS_PER_EDITION = 3;

    /** Custom-data key stamped on the issued book — the edition's world-day. */
    public static final String PATROL_DAY_KEY = "StrajaPatrolDay";

    private final StrajaPolicies policies;

    public PatrolGuideService(StrajaPolicies policies) {
        this.policies = policies;
    }

    /** Edition tag stamped into the book's custom data. */
    public String edition(long worldDay) {
        return "ziua-" + Math.max(0, worldDay);
    }

    /** An edition goes stale the moment the world-day moves on. */
    public boolean stale(long issuedDay, long currentDay) {
        return issuedDay != currentDay;
    }

    /** The book's rendered pages for a given edition day. */
    public List<String> pages(long worldDay) {
        long day = Math.max(0, worldDay);
        String prefix = policies.artifactSerialPrefix == null
                || policies.artifactSerialPrefix.isBlank()
                ? "RC-" : policies.artifactSerialPrefix;
        List<String> pages = new ArrayList<>();
        pages.add("Ghid de patrulare — ediția zilei " + day + "\n\n"
                + "Comisariatul Straja — uz intern. Nu arăta acest manual civililor.");
        pages.add("Marca autentică\n\n"
                + "Un obiect reglementat poartă marca «#" + prefix + "NNN» — diez, prefix "
                + prefix + " și numărul de serie, fără litere mici, fără spații, fără sufixe.\n\n"
                + "Îndoială? Verifică deținătorul la fața locului cu /straja inspect <jucător> "
                + "sau trimite-l la ghiseul de inspecție.");
        pages.add("Semne sigure de fals\n\n"
                + "Orice marfă reglementată FĂRĂ marcă de serie e neînregistrată — ridic-o.\n\n"
                + "Marcile absurde și cele cu format greșit le prinde poarta singură; "
                + "tu cauți ce trece mașina.");
        for (String defect : rotatedDefects(day)) {
            pages.add("Exemplu de fals\n\n" + defect);
        }
        pages.add("Închidere\n\n"
                + "Ediția " + day + " expiră la miezul nopții. Ghidul de mâine "
                + "arată alte exemple — ia ediția nouă la schimbarea gărzii.");
        return pages;
    }

    /** The day's deterministic rotation through the defect catalogue —
     * a spread stride so consecutive editions show disjoint exemplars. */
    static List<String> rotatedDefects(long day) {
        int size = DEFECTS.size();
        int start = (int) Math.floorMod(day, size);
        List<String> out = new ArrayList<>(DEFECTS_PER_EDITION);
        for (int i = 0; i < DEFECTS_PER_EDITION; i++) {
            out.add(DEFECTS.get((start + i * DEFECTS_PER_EDITION) % size));
        }
        return out;
    }
}
