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
 * concrete {@code straja.remedy.*} next step, every referenced key exists in
 * both shipped lang files (ro_ro canonical, en_us parity), placeholder counts
 * match the call args, and no bare denial text reaches a player outside the
 * {@code refuse}/{@code refusal} helper.
 */
class RefusalConventionTest {
    private static final Path SRC = Path.of("src/main/java/com/dwurdy/straja");
    private static final Path LANG = Path.of("src/main/resources/assets/straja/lang");

    private static final Pattern CALL = Pattern.compile(
            "\\b(tell|tellKey|sendFailure|sendSystemMessage|refuse|refusal)\\s*\\(");
    private static final Pattern STRING_LIT = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern DENIAL_RE = Pattern.compile(
            "(?i)(?:^|[\\s.\"])(?:nu |doar |numai |trebuie |insuficient|dezactivat|"
            + "rezervat|interzis|necesită|necesita|cere |offline|necunoscut)"
            + "|maximum \\d*\\s*caractere|numărul maxim|not running");

    /** Post-event notifications and guidance that intentionally stay literal. */
    private static final List<String> NOTIFICATION_WHITELIST = List.of(
            "nu poate crea singur autoritate de arest",
            "NU ESTE MANDAT DE AREST",
            "nu avea Cătușe disponibile",
            "nu poate fi ucis de mecanica Străjii",
            "Nu poți folosi inventarul sau obiectele",
            "nu se consumă",
            "poate fi tăiată doar de alt jucător",
            "nu mai există; ai rămas fără echipă",
            "nu emit",   // role-description text in the rank help block
            "nu se plătește", // failure notification
            "Nu ai fost trimis la pușcărie", // status after hearing
            "Nu ai acte V2 emise",
            "Nu ai obligații în registrul de echipament V2",
            "Nu există campanii V2 active",
            "nu este autoritate de arest",
            "Detașarea retrage doar rolul",
            "Mail offline",
            "Arhivista",
            "Dosarul nu are foi",
            "au fost returnate",
            "îți cere predarea",
            "vrea să te încătușeze",
            "va fi livrată când revine",
            "nu mai este ",
            "Ți s-a pus Sacul",
            "nu mai sunt posibile",
            "Nu te mișca",
            "doar pentru citire",
            "debitare estimată",
            "refuzul explicit al cetățeanului",
            "are funcția",
            "preaviz pentru",
            "Demisia este în așteptare",
            "livrat parțial",
            "livrată parțial",
            "nu au putut fi rezolvate",
            "s-a putut salva",
            "Instalarea NU este gata",
            "suspendă temporar",
            "buletine",
            "h × risc",
            "activat\"\"dezactivat",
            "până la reconectare",
            "nu s-a putut actualiza",
            "Ai fost arestat pentru",
            "această tură cere"
    );

    private record Call(Path file, int line, String method, List<String> args) {
    }

    /** Blank comment text in place (line numbers stay stable). */
    private static String stripComments(String src) {
        StringBuilder sb = new StringBuilder(src);
        boolean inStr = false, inChar = false, line = false, block = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            char next = i + 1 < sb.length() ? sb.charAt(i + 1) : 0;
            if (line) {
                if (c == '\n') line = false;
                else sb.setCharAt(i, ' ');
                continue;
            }
            if (block) {
                if (c == '*' && next == '/') { sb.setCharAt(i, ' '); sb.setCharAt(i + 1, ' '); i++; block = false; }
                else if (c != '\n') sb.setCharAt(i, ' ');
                continue;
            }
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (inChar) {
                if (c == '\\') i++;
                else if (c == '\'') inChar = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '\'') inChar = true;
            else if (c == '/' && next == '/') line = true;
            else if (c == '/' && next == '*') { block = true; sb.setCharAt(i, ' '); sb.setCharAt(i + 1, ' '); i++; }
        }
        return sb.toString();
    }

    /** Balanced-paren call extraction; args are raw top-level arg strings. */
    private static List<Call> calls() throws IOException {
        List<Call> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = stripComments(Files.readString(file));
                Matcher m = CALL.matcher(src);
                while (m.find()) {
                    int open = m.end() - 1;
                    int end = callEnd(src, open);
                    if (end < 0) {
                        continue;
                    }
                    int line = src.substring(0, m.start()).split("\n", -1).length;
                    out.add(new Call(file, line, m.group(1),
                            splitArgs(src.substring(open + 1, end))));
                }
            }
        }
        return out;
    }

    private static int callEnd(String s, int open) {
        int depth = 0;
        boolean inStr = false, inChar = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (inChar) {
                if (c == '\\') i++;
                else if (c == '\'') inChar = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '\'') inChar = true;
            else if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') {
                if (--depth == 0) return i;
            }
        }
        return -1;
    }

    private static List<String> splitArgs(String argText) {
        List<String> args = new ArrayList<>();
        int depth = 0, start = 0;
        boolean inStr = false, inChar = false;
        for (int i = 0; i < argText.length(); i++) {
            char c = argText.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (inChar) {
                if (c == '\\') i++;
                else if (c == '\'') inChar = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '\'') inChar = true;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                args.add(argText.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = argText.substring(start).trim();
        if (!last.isEmpty()) args.add(last);
        return args;
    }

    private static String literalOf(String arg) {
        arg = arg.trim();
        return arg.startsWith("\"") && arg.endsWith("\"")
                ? arg.substring(1, arg.length() - 1) : null;
    }

    /** Concatenated literal parts of an expression (ignores code args). */
    private static String literalText(String expr) {
        Matcher m = STRING_LIT.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) sb.append(m.group());
        return sb.toString();
    }

    private static JsonObject lang(String name) throws IOException {
        try (var in = Files.newInputStream(LANG.resolve(name + ".json"))) {
            return JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static Set<String> keySet(JsonObject o) {
        return o.keySet();
    }

    @Test
    void everyRefusalNamesARemedy() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Call c : calls()) {
            if (!c.method().equals("refuse") && !c.method().equals("refusal")) continue;
            if (c.args().size() < 2) {
                violations.add(c.file().getFileName() + ":" + c.line()
                        + " -> refusal call with <2 args");
                continue;
            }
            String remedy = literalOf(c.args().get(1));
            if (remedy == null) continue; // computed remedy — checked separately
            if (!remedy.startsWith("straja.remedy.")) {
                violations.add(c.file().getFileName() + ":" + c.line()
                        + " -> second refusal arg is not a straja.remedy.* key: " + remedy);
            }
        }
        assertTrue(violations.isEmpty(),
                "refusals without a remedy key:\n" + String.join("\n", violations));
    }

    /** Computed-key refusal paths (e.g. refuseCore) must still pass remedies. */
    @Test
    void computedRemediesAreRemedyKeys() throws IOException {
        Path guard = SRC.resolve("application/service/GuardService.java");
        String src = Files.readString(guard);
        // every string literal returned from coreRemedy must be a remedy key
        Matcher body = Pattern.compile(
                "String coreRemedy\\(.*?\\)(\\s|\\S)*?\\n    \\}").matcher(src);
        assertTrue(body.find(), "coreRemedy lookup not found");
        Matcher lit = STRING_LIT.matcher(body.group());
        List<String> bad = new ArrayList<>();
        while (lit.find()) {
            String v = lit.group();
            if (v.startsWith("\"straja.") && !v.startsWith("\"straja.remedy.")) {
                bad.add(v);
            }
        }
        assertTrue(bad.isEmpty(),
                "coreRemedy returns non-remedy keys: " + bad);
    }

    @Test
    void everyRefusalKeyIsTranslated() throws IOException {
        var sites = calls().stream()
                .filter(c -> c.method().equals("refuse") || c.method().equals("refusal"))
                .toList();
        assertTrue(!sites.isEmpty(), "no refusal call sites found");
        Set<String> keys = new TreeSet<>();
        for (Call c : sites) {
            if (c.args().size() < 2) continue;
            String reason = literalOf(c.args().get(0));
            String remedy = literalOf(c.args().get(1));
            if (reason != null && reason.startsWith("straja.")) keys.add(reason);
            if (remedy != null && remedy.startsWith("straja.")) keys.add(remedy);
        }
        // tellKey paths must resolve too
        for (Call c : calls()) {
            if (!c.method().equals("tellKey") || c.args().isEmpty()) continue;
            String k = literalOf(c.args().get(0));
            if (k != null && k.startsWith("straja.")) keys.add(k);
        }
        Set<String> ro = keySet(lang("ro_ro"));
        Set<String> en = keySet(lang("en_us"));
        List<String> missing = keys.stream()
                .filter(k -> !ro.contains(k) || !en.contains(k))
                .toList();
        assertTrue(missing.isEmpty(),
                "refusal keys missing from a lang file:\n" + String.join("\n", missing));
    }

    /** The ro_ro value's %s count must equal the args passed to refuse(). */
    @Test
    void refusalArgsMatchPlaceholders() throws IOException {
        JsonObject ro = lang("ro_ro");
        List<String> violations = new ArrayList<>();
        for (Call c : calls()) {
            if (!c.method().equals("refuse") && !c.method().equals("refusal")) continue;
            if (c.args().size() < 2) continue;
            String reason = literalOf(c.args().get(0));
            if (reason == null || !ro.has(reason)) continue; // computed or unknown
            long expected = ro.get(reason).getAsString().split("%s", -1).length - 1;
            long actual = c.args().size() - 2;
            if (expected != actual) {
                violations.add(c.file().getFileName() + ":" + c.line()
                        + " -> " + reason + " expects " + expected
                        + " args, call passes " + actual);
            }
        }
        assertTrue(violations.isEmpty(),
                "refusal arg/placeholder mismatches:\n" + String.join("\n", violations));
    }

    /** No bare denial text may reach players outside refuse()/refusal(). */
    @Test
    void noBareDenialText() throws IOException {
        List<String> violations = new ArrayList<>();
        JsonObject ro = lang("ro_ro");
        for (Call c : calls()) {
            if (!List.of("tell", "tellKey", "sendFailure", "sendSystemMessage")
                    .contains(c.method())) continue;
            String text;
            if (c.method().equals("tellKey") && !c.args().isEmpty()) {
                String key = literalOf(c.args().get(0));
                text = key != null && ro.has(key) ? ro.get(key).getAsString() : null;
            } else {
                String joined = String.join(" ", c.args());
                if (joined.contains("refusal(") || joined.contains(".refuse(")) {
                    continue; // already routed through the convention
                }
                text = literalText(joined);
            }
            if (text == null || !DENIAL_RE.matcher(text).find()) continue;
            boolean whitelisted = NOTIFICATION_WHITELIST.stream()
                    .anyMatch(text::contains);
            if (!whitelisted) {
                violations.add(c.file().getFileName() + ":" + c.line()
                        + " -> denial text bypasses refusal convention: "
                        + text.substring(0, Math.min(80, text.length())));
            }
        }
        assertTrue(violations.isEmpty(),
                "bare denial messages:\n" + String.join("\n", violations));
    }

    @Test
    void langFilesStayInParity() throws IOException {
        Set<String> ro = keySet(lang("ro_ro"));
        Set<String> en = keySet(lang("en_us"));
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
