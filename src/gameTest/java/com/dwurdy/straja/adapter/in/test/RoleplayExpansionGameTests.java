package com.dwurdy.straja.adapter.in.test;

import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.bootstrap.StrajaItems;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.ArrestRecord;
import com.dwurdy.straja.domain.model.EvidenceCustodyEvent;
import com.dwurdy.straja.domain.model.EvidenceRecord;
import com.dwurdy.straja.domain.model.EvidenceStatus;
import com.dwurdy.straja.domain.model.RestraintMode;
import com.dwurdy.straja.domain.model.GuardState;
import java.util.Map;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Minecraft-boundary coverage for the RP expansion. The dedicated GameTest
 * source set keeps this runtime-only verification out of the distributable.
 * The service suite covers
 * edge cases; this batch proves that the registered server surface can drive
 * the same state transitions with real ServerPlayer and SavedData adapters.
 */
@GameTestHolder("straja_rp")
@PrefixGameTestTemplate(false)
public final class RoleplayExpansionGameTests {
    private RoleplayExpansionGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void whistleCreatesOneAssistanceIncident(GameTestHelper helper) {
        StrajaRuntime runtime = runtime(helper);
        if (runtime == null) return;
        PlayerGateway guard = qualify(runtime, helper.makeMockServerPlayerInLevel(), 3, true);
        long before = runtime.incidents().active().stream()
                .filter(i -> guard.uuid().toString().equals(i.createdByUuid)
                        && i.type == com.dwurdy.straja.domain.model.IncidentType.GUARD_ASSISTANCE)
                .count();
        boolean first = runtime.expansionRoleplay().useWhistle(guard);
        boolean duplicate = runtime.expansionRoleplay().useWhistle(guard);
        long after = runtime.incidents().active().stream()
                .filter(i -> guard.uuid().toString().equals(i.createdByUuid)
                        && i.type == com.dwurdy.straja.domain.model.IncidentType.GUARD_ASSISTANCE)
                .count();
        helper.assertTrue(first, "on-duty whistle should create an assistance incident");
        helper.assertFalse(duplicate, "the whistle cooldown should reject a duplicate packet");
        helper.assertValueEqual(before + 1, after, "one whistle should create one incident");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void cuffsToggleEscortHardAndClear(GameTestHelper helper) {
        StrajaRuntime runtime = runtime(helper);
        if (runtime == null) return;
        ServerPlayer guardEntity = helper.makeMockServerPlayerInLevel();
        ServerPlayer targetEntity = helper.makeMockServerPlayerInLevel();
        guardEntity.setPos(0, 1, 0);
        targetEntity.setPos(1, 1, 0);
        PlayerGateway guard = qualify(runtime, guardEntity, 3, true);
        PlayerGateway target = gateway(runtime, targetEntity);
        guardEntity.getInventory().add(new ItemStack(StrajaItems.CUFFS.get()));
        var applied = runtime.custody().applyCuffsDirect(guard, target, "gametest");
        helper.assertTrue(applied.ok(), "authorized guard should apply cuffs: " + applied.reason());
        helper.assertValueEqual(RestraintMode.ESCORT,
                runtime.context().custody().read().states.get(target.uuid().toString()).restraintMode,
                "new cuffs start in escort mode");
        helper.assertTrue(runtime.custodyRoleplay().toggleRestraintMode(guard, target),
                "authorized guard should toggle restraint mode");
        helper.assertValueEqual(RestraintMode.HARD,
                runtime.context().custody().read().states.get(target.uuid().toString()).restraintMode,
                "toggle should enter hard mode");
        guardEntity.getInventory().setItem(0, new ItemStack(StrajaItems.CUFF_KEY.get()));
        guardEntity.getInventory().selected = 0;
        helper.assertTrue(runtime.custodyRoleplay().release(guard, target),
                "the same authoritative key should release cuffs");
        var released = runtime.context().custody().read().states.get(target.uuid().toString());
        helper.assertValueEqual(RestraintMode.ESCORT, released.restraintMode,
                "release should reset the mode safely");
        helper.assertFalse(runtime.custodyRoleplay().isCuffed(target),
                "release should clear the cuff projection");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void searchConfiscatesExactlyOneAuthoritativeStack(GameTestHelper helper) {
        StrajaRuntime runtime = runtime(helper);
        if (runtime == null) return;
        ServerPlayer guardEntity = helper.makeMockServerPlayerInLevel();
        ServerPlayer targetEntity = helper.makeMockServerPlayerInLevel();
        guardEntity.setPos(0, 1, 0);
        targetEntity.setPos(1, 1, 0);
        PlayerGateway guard = qualify(runtime, guardEntity, 3, true);
        PlayerGateway target = gateway(runtime, targetEntity);
        guardEntity.getInventory().add(new ItemStack(StrajaItems.CUFFS.get()));
        var applied = runtime.custody().applyCuffsDirect(guard, target, "gametest");
        helper.assertTrue(applied.ok(), "setup cuffs should succeed: " + applied.reason());
        targetEntity.getInventory().setItem(5, new ItemStack(Items.EMERALD, 3));
        var view = runtime.expansionRoleplay().beginSearch(guard, target);
        helper.assertTrue(view != null && view.slots().stream()
                        .anyMatch(slot -> slot.item().id().equals("minecraft:emerald")),
                "search should expose a read-only emerald slot");
        int slot = view.slots().stream()
                .filter(candidate -> candidate.item().id().equals("minecraft:emerald"))
                .findFirst().orElseThrow().slot();
        helper.assertTrue(runtime.expansionRoleplay().confiscate(guard, view.token(), slot, 3,
                        "GameTest", ""), "the exact stack should be confiscated");
        helper.assertValueEqual(0, targetEntity.getInventory().getItem(slot).getCount(),
                "confiscation should remove the exact stack from the target");
        var store = runtime.context().evidence().read();
        EvidenceRecord record = store.records.values().stream()
                .filter(e -> e != null && target.uuid().toString().equals(e.sourcePlayerUuid)
                        && "minecraft:emerald".equals(e.itemId))
                .reduce((first, second) -> second).orElse(null);
        helper.assertTrue(record != null && record.amount == 3
                        && record.status == EvidenceStatus.IN_GUARD_CUSTODY,
                "evidence must preserve the authoritative item and custody status");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void evidenceRoundTripDoesNotDuplicate(GameTestHelper helper) {
        StrajaRuntime runtime = runtime(helper);
        if (runtime == null) return;
        var data = runtime.context().evidence().read();
        String id = "GT-E-" + runtime.context().ids().token();
        EvidenceRecord record = new EvidenceRecord();
        record.id = id;
        record.itemId = "minecraft:paper";
        record.amount = 1;
        record.itemData = new java.util.LinkedHashMap<>(Map.of("GameTest", "roundtrip"));
        record.status = EvidenceStatus.DEPOSITED;
        record.custodyHistory.add(new EvidenceCustodyEvent());
        data.records.put(id, record);
        int expected = data.records.size();
        runtime.context().evidence().write(data);
        var roundTrip = runtime.context().evidence().read();
        runtime.context().evidence().write(roundTrip);
        var secondRead = runtime.context().evidence().read();
        helper.assertValueEqual(expected, secondRead.records.size(),
                "evidence persistence must not duplicate records");
        helper.assertTrue(secondRead.records.containsKey(id)
                        && "roundtrip".equals(secondRead.records.get(id).itemData.get("GameTest"))
                        && secondRead.records.get(id).custodyHistory.size() == 1,
                "serialized evidence and its custody chain must survive the round trip");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void finalDeathAppliesOneReputationEvent(GameTestHelper helper) {
        StrajaRuntime runtime = runtime(helper);
        if (runtime == null) return;
        ServerPlayer killerEntity = helper.makeMockServerPlayerInLevel();
        ServerPlayer victimEntity = helper.makeMockServerPlayerInLevel();
        PlayerGateway killer = gateway(runtime, killerEntity);
        PlayerGateway victim = gateway(runtime, victimEntity);
        int before = runtime.reputation().state(killer).score;
        int eventsBefore = (int) runtime.context().reputation().read().events.values().stream()
                .filter(e -> e != null && killer.uuid().toString().equals(e.playerUuid)).count();
        runtime.expansionRoleplay().recordFinalDeath(killer, victim);
        runtime.expansionRoleplay().recordFinalDeath(killer, victim);
        int after = runtime.reputation().state(killer).score;
        int eventsAfter = (int) runtime.context().reputation().read().events.values().stream()
                .filter(e -> e != null && killer.uuid().toString().equals(e.playerUuid)).count();
        helper.assertValueEqual(before + runtime.policies().reputationOrdinaryKillDelta,
                after, "final death should apply the configured ordinary penalty once");
        helper.assertValueEqual(eventsBefore + 1, eventsAfter,
                "duplicate death delivery must not create a second ledger event");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void jailerHandoffCreatesOneArrestRecord(GameTestHelper helper) {
        StrajaRuntime runtime = runtime(helper);
        if (runtime == null) return;
        PlayerGateway guard = qualify(runtime, helper.makeMockServerPlayerInLevel(), 3, true);
        ServerPlayer detaineeEntity = helper.makeMockServerPlayerInLevel();
        ServerPlayer jailerEntity = helper.makeMockServerPlayerInLevel();
        PlayerGateway detainee = gateway(runtime, detaineeEntity);
        PlayerGateway jailer = qualify(runtime, jailerEntity, 4, true);
        var sentence = runtime.prison().arrest(detainee, "GT-FINE-" + detainee.uuid(), 1,
                guard, "GT-TASK-" + detainee.uuid());
        helper.assertTrue(sentence != null, "an active sentence should be created for handoff");
        helper.assertTrue(runtime.expansionRoleplay().finalizeArrest(
                        jailer, detainee.uuid().toString(), sentence.id, "GameTest handoff"),
                "the jailer handoff should finalize the arrest record");
        long records = runtime.arrestRecords().recordsFor(jailer).stream()
                .filter(record -> record != null && sentence.id.equals(record.sentenceId)).count();
        helper.assertValueEqual(1L, records, "one sentence should produce one arrest record");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void sentenceCompletionRehabilitatesOnce(GameTestHelper helper) {
        StrajaRuntime runtime = runtime(helper);
        if (runtime == null) return;
        PlayerGateway subject = gateway(runtime, helper.makeMockServerPlayerInLevel());
        runtime.reputation().apply(subject, subject, "GT_NEGATIVE",
                "GT-" + subject.uuid(), -120, "GameTest setup", false);
        runtime.reputation().completeSentence(subject, "GT-SENTENCE-" + subject.uuid());
        runtime.reputation().completeSentence(subject, "GT-SENTENCE-" + subject.uuid());
        helper.assertValueEqual(-60, runtime.reputation().state(subject).score,
                "sentence rehabilitation should be capped by idempotency");
        long events = runtime.context().reputation().read().events.values().stream()
                .filter(e -> e != null && "SENTENCE_SERVED".equals(e.sourceType)
                        && subject.uuid().toString().equals(e.playerUuid))
                .filter(e -> e.sourceRecordId.equals("GT-SENTENCE-" + subject.uuid())).count();
        helper.assertValueEqual(1L, events,
                "a sentence completion should create one rehabilitation event");
        helper.succeed();
    }

    private static StrajaRuntime runtime(GameTestHelper helper) {
        StrajaRuntime runtime = StrajaRuntime.get();
        if (runtime == null) helper.fail("Straja runtime is not available");
        return runtime;
    }

    private static PlayerGateway gateway(StrajaRuntime runtime, ServerPlayer player) {
        return new MinecraftPlayerGateway(runtime.server(), player.getUUID());
    }

    private static PlayerGateway qualify(StrajaRuntime runtime, ServerPlayer player,
                                         int rank, boolean duty) {
        PlayerGateway gateway = gateway(runtime, player);
        GuardState state = runtime.players().state(gateway);
        state.rank = rank;
        state.invited = true;
        state.duty = duty;
        state.fired = false;
        state.suspended = false;
        state.resigned = false;
        runtime.players().save(gateway.uuid(), state);
        return gateway;
    }
}
