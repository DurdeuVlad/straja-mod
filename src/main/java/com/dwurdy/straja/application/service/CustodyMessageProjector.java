package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.CustodyState;
import com.dwurdy.straja.domain.model.CustodyStatus;
import com.dwurdy.straja.domain.model.CustodyStore;
import com.dwurdy.straja.domain.model.PlayerCondition;
import com.dwurdy.straja.domain.model.RestraintStatus;
import com.dwurdy.straja.domain.model.TransportStatus;
import com.dwurdy.straja.domain.model.VisionStatus;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects custody timers into sparse action-bar updates and threshold-only
 * warnings. State-entry messages for interactive actions remain at their
 * server-side decision point; this class owns recurring status communication
 * so the tick loop never creates chat spam.
 */
public final class CustodyMessageProjector {
    private static final int[] WARNING_SECONDS = {30, 10, 5, 1};

    private final Map<String, String> lifecycle = new HashMap<>();
    private final Map<String, String> lastActionbar = new HashMap<>();
    private final Map<String, Long> previousSeconds = new HashMap<>();
    private final Set<String> warnings = new HashSet<>();

    private record Timer(String id, String label, Long deadlineAt) {}

    private record Status(String lifecycleId, String label, List<Timer> timers) {
        String actionbar(long now) {
            StringBuilder text = new StringBuilder("[Straja] ").append(label);
            for (Timer timer : timers) {
                long seconds = Math.max(0, (timer.deadlineAt - now + 999) / 1000);
                text.append(" | ").append(timer.label).append(": ").append(seconds).append("s");
            }
            return text.toString();
        }
    }

    public void reset() {
        lifecycle.clear();
        lastActionbar.clear();
        previousSeconds.clear();
        warnings.clear();
    }

    /** Emits at most one action-bar update per visible second and one warning per threshold. */
    public void sync(List<PlayerGateway> players, CustodyStore store, long now) {
        Set<String> seen = new HashSet<>();
        for (PlayerGateway player : players) {
            if (player == null || player.uuid() == null || !player.isOnline()) continue;
            String key = player.uuid().toString();
            Status status = project(key, store);
            if (status == null) {
                lifecycle.remove(key);
                lastActionbar.remove(key);
                clearWarnings(key);
                continue;
            }
            seen.add(key);
            boolean entered = !status.lifecycleId.equals(lifecycle.get(key));
            if (entered) clearWarnings(key);

            String bar = status.actionbar(now);
            if (entered || !bar.equals(lastActionbar.get(key))) {
                player.actionbar(bar);
                lastActionbar.put(key, bar);
            }
            for (Timer timer : status.timers) emitWarnings(player, key, timer, now);
            lifecycle.put(key, status.lifecycleId);
        }
        lifecycle.keySet().removeIf(key -> !seen.contains(key));
        lastActionbar.keySet().removeIf(key -> !seen.contains(key));
        warnings.removeIf(key -> !seen.contains(key.substring(0, key.indexOf('|'))));
    }

    private Status project(String key, CustodyStore store) {
        if (store == null) return null;
        CustodyState state = store.states.get(key);
        var legacyDowned = store.downed.get(key);
        if (state == null && legacyDowned == null) return null;

        if (state == null) {
            return new Status("legacy-downed:" + legacyDowned.startedAt + ":" + legacyDowned.wakesAt,
                    "Leșinat", List.of(new Timer("downed", "revenire", legacyDowned.wakesAt)));
        }

        String label;
        if (state.transport == TransportStatus.CARRIED) {
            label = "Transportat — inconștiența este pusă pe pauză";
        } else if (state.condition == PlayerCondition.RESUSCITATING) {
            label = "Resuscitare în curs — nu te mișca";
        } else if (state.condition == PlayerCondition.UNCONSCIOUS_CUSTODY) {
            label = state.custody == CustodyStatus.JAILED
                    ? "Încarcerat — inconștient" : "Inconștient în custodie";
        } else if (state.condition == PlayerCondition.DOWNED) {
            label = "Leșinat";
        } else if (state.restraint == RestraintStatus.CUFFED) {
            label = "Încătușat";
        } else if (state.restraint == RestraintStatus.ROPE_BOUND) {
            label = "Legat cu frânghia";
        } else if (state.custody == CustodyStatus.ARRESTED) {
            label = "Arestat";
        } else if (state.custody == CustodyStatus.JAILED) {
            label = "În închisoare";
        } else {
            label = "Conștient";
        }
        if (state.vision == VisionStatus.BLINDFOLDED) label += " — vedere blocată";

        var timers = new java.util.ArrayList<Timer>();
        if (state.transport == TransportStatus.CARRIED) {
            addTimer(timers, "transport", "transport", state.transportDeadlineAt);
        }
        switch (state.condition) {
            case DOWNED -> {
                if (state.transport != TransportStatus.CARRIED) {
                    addTimer(timers, "downed", "revenire", state.downedDeadlineAt);
                }
            }
            case RESUSCITATING -> addTimer(timers, "resuscitation", "resuscitare", state.resuscitationDeadlineAt);
            case UNCONSCIOUS_CUSTODY -> {
                if (state.custody == CustodyStatus.JAILED) {
                    addTimer(timers, "jail-revival", "trezire", state.jailRevivalAt);
                } else {
                    addTimer(timers, "custody", "control", state.unconsciousCustodyDeadlineAt);
                }
            }
            default -> { }
        }
        if (state.custody == CustodyStatus.ARRESTED) {
            addTimer(timers, "jail-delivery", "predare", state.jailDeliveryDeadlineAt);
        }
        String lifecycleId = state.enteredAt + ":" + state.transitionId + ":"
                + state.condition + ":" + state.custody + ":" + state.transport + ":"
                + state.restraint + ":" + state.vision;
        return new Status(lifecycleId, label, List.copyOf(timers));
    }

    private static void addTimer(List<Timer> timers, String id, String label, Long deadlineAt) {
        if (deadlineAt != null && deadlineAt > 0) timers.add(new Timer(id, label, deadlineAt));
    }

    private void emitWarnings(PlayerGateway player, String key, Timer timer, long now) {
        long seconds = Math.max(0, (timer.deadlineAt - now + 999) / 1000);
        String timerKey = key + "|" + timer.id;
        Long previous = previousSeconds.get(timerKey);
        for (int threshold : WARNING_SECONDS) {
            boolean crossed = previous == null ? seconds == threshold
                    : previous > threshold && seconds <= threshold;
            if (crossed && warnings.add(timerKey + "|" + threshold)) {
                player.tell("[Straja] Atenție: " + timer.label + " în " + threshold + " secunde.");
            }
        }
        previousSeconds.put(timerKey, seconds);
    }

    private void clearWarnings(String key) {
        warnings.removeIf(warning -> warning.startsWith(key + "|"));
        previousSeconds.keySet().removeIf(timer -> timer.startsWith(key + "|"));
    }
}
