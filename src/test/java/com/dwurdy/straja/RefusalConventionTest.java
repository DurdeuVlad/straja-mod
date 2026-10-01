package com.dwurdy.straja;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.service.AuditService;
import com.dwurdy.straja.application.service.EquipmentService;
import com.dwurdy.straja.application.service.GuardService;
import com.dwurdy.straja.application.service.PlayerService;
import com.dwurdy.straja.support.Fakes;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Issue #204 convention: every player-facing refusal carries a reason key and a
 * concrete {@code straja.remedy.*} next step, and every referenced key exists
 * in both shipped lang files (ro_ro canonical, en_us parity).
 */
class RefusalConventionTest {
    private static final Path SRC = Path.of("src/main/java/com/dwurdy/straja");
    private static final Path LANG = Path.of("src/main/resources/assets/straja/lang");

    // refuse("reason.key", "remedy.key", ...) / refusal("reason.key", "remedy.key", ...)
    private static final Pattern REFUSAL_CALL = Pattern.compile(
            "(?:\\.refuse|\\brefusal)\\(\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"");

    private record Site(Path file, int line, String reasonKey, String remedyKey) {
    }

    private static List<Site> refusalCalls() throws IOException {
        List<Site> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                int line = 0;
                for (String text : Files.readAllLines(file)) {
                    line++;
                    Matcher m = REFUSAL_CALL.matcher(text);
                    while (m.find()) {
                        sites.add(new Site(file, line, m.group(1), m.group(2)));
                    }
                }
            }
        }
        return sites;
    }

    private static Set<String> langKeys(String lang) throws IOException {
        try (var in = Files.newInputStream(LANG.resolve(lang + ".json"))) {
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return root.keySet();
        }
    }

    @Test
    void everyRefusalNamesARemedy() throws IOException {
        var violations = refusalCalls().stream()
                .filter(s -> !s.remedyKey().startsWith("straja.remedy."))
                .map(s -> s.file().getFileName() + ":" + s.line()
                        + " -> second refusal arg is not a straja.remedy.* key: "
                        + s.remedyKey())
                .toList();
        assertTrue(violations.isEmpty(),
                "refusals without a remedy key:\n" + String.join("\n", violations));
    }

    @Test
    void everyRefusalKeyIsTranslated() throws IOException {
        var sites = refusalCalls();
        assertTrue(!sites.isEmpty(), "no refusal call sites found");
        Set<String> keys = new TreeSet<>();
        for (Site s : sites) {
            keys.add(s.reasonKey());
            keys.add(s.remedyKey());
        }
        Set<String> ro = langKeys("ro_ro");
        Set<String> en = langKeys("en_us");
        List<String> missing = keys.stream()
                .filter(k -> !ro.contains(k) || !en.contains(k))
                .toList();
        assertTrue(missing.isEmpty(),
                "refusal keys missing from a lang file:\n" + String.join("\n", missing));
    }

    @Test
    void langFilesStayInParity() throws IOException {
        Set<String> ro = langKeys("ro_ro");
        Set<String> en = langKeys("en_us");
        Set<String> roOnly = new TreeSet<>(ro);
        roOnly.removeAll(en);
        Set<String> enOnly = new TreeSet<>(en);
        enOnly.removeAll(ro);
        assertTrue(roOnly.isEmpty() && enOnly.isEmpty(),
                "lang parity drift — ro-only: " + roOnly + " en-only: " + enOnly);
    }

    /** Spot check: a fired applicant hears why, and who can fix it. */
    @Test
    void refusalRendersReasonAndRemedy() {
        var server = new Fakes.TestServer();
        var ctx = Fakes.context(server, new Fakes.FixedClock(0), Fakes.policies());
        var players = new PlayerService(ctx);
        var guards = new GuardService(ctx, players, new AuditService(ctx),
                new EquipmentService(ctx));

        var player = server.add("fired_recruit");
        var state = players.state(player.uuid());
        state.fired = true;
        players.save(player.uuid(), state);

        guards.applyForStraja(player);

        String last = player.lastMessage();
        assertTrue(last.contains("îndepărtat"), "refusal names the reason: " + last);
        assertTrue(last.contains("Comisarul"), "refusal names the remedy: " + last);
    }
}
