package com.dwurdy.straja.gametest;

import com.dwurdy.straja.adapter.in.test.VirtualPlayerGateway;
import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.domain.model.BoloRecord;
import com.dwurdy.straja.domain.model.BoloStatus;
import com.dwurdy.straja.domain.model.CheckpointMode;
import com.dwurdy.straja.domain.model.CrossingOutcome;
import com.dwurdy.straja.domain.model.InspectionLedgerEntry;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.LawBounds;
import com.dwurdy.straja.domain.model.LawCheckpointRecord;
import com.dwurdy.straja.domain.model.PrisonerRegisterRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.PushbackPoint;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * LAW-008 live acceptance coverage. These tests drive the real server tick
 * pipeline — {@code CheckpointService.tick()} scans every 5 ticks on the
 * running GameTest server — so crossings are exercised against real
 * ServerPlayer entities, real inventories, and the real SavedData stores,
 * not the unit-test fakes.
 *
 * <p>Each test anchors its checkpoint bounds to its own structure origin so
 * concurrent batch members never overlap a foreign site. Movement between
 * scans uses {@code runAfterDelay(7)} — the 5-tick scan cadence is
 * guaranteed to fire at least once inside any 7-tick window.
 *
 * <p>Cleanup runs inside the scheduled steps, not in a try/finally:
 * {@code runAfterDelay} only schedules, so a finally would tear the site
 * down in the same tick it was saved. Every step is wrapped so a failed
 * assertion still cleans its stores, and each test de-registers its own ids
 * at start — the GameTest world's SavedData persists across runs, so a
 * crashed run would otherwise leave stale cells/sites behind.
 */
@GameTestHolder("straja")
@PrefixGameTestTemplate(false)
public final class LawAcceptanceGameTests {
    private LawAcceptanceGameTests() {}

    private static StrajaRuntime runtime(GameTestHelper helper) {
        var runtime = StrajaRuntime.get();
        helper.assertTrue(runtime != null, "StrajaRuntime is not initialised on the game-test server");
        return runtime;
    }

    private static MinecraftPlayerGateway gateway(ServerPlayer player) {
        return new MinecraftPlayerGateway(player.getServer(), player.getUUID());
    }

    @SuppressWarnings("removal")
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    /**
     * Schedules {@code body} after {@code ticks}; on any failure the shared
     * cleanup still runs so a broken step does not leak test state into the
     * shared SavedData stores, then the failure propagates to the framework.
     */
    private static void step(GameTestHelper helper, int ticks, Runnable body,
                             Runnable cleanup) {
        helper.runAfterDelay(ticks, () -> {
            try {
                body.run();
            } catch (RuntimeException | AssertionError error) {
                try {
                    cleanup.run();
                } catch (RuntimeException ignored) {
                    // cleanup failure must not mask the real assertion
                }
                throw error;
            }
        });
    }

    private static void saveSite(StrajaRuntime runtime, LawCheckpointRecord site) {
        var store = runtime.context().lawCheckpoints().read();
        store.put(site);
        runtime.context().lawCheckpoints().write(store);
    }

    private static void removeSite(StrajaRuntime runtime, String siteId) {
        var store = runtime.context().lawCheckpoints().read();
        store.checkpoints().remove(siteId);
        runtime.context().lawCheckpoints().write(store);
    }

    private static void removeCell(StrajaRuntime runtime, String cellId) {
        var prison = runtime.context().prison().read();
        prison.cells.removeIf(c -> c != null && cellId.equals(c.id));
        prison.assignments.remove(cellId);
        runtime.context().prison().write(prison);
    }

    private static void removeCamp(StrajaRuntime runtime, String campId) {
        var camps = runtime.context().laborCamps().read();
        camps.remove(campId);
        runtime.context().laborCamps().write(camps);
    }

    private static void removeBolo(StrajaRuntime runtime, String boloId) {
        var bolos = runtime.context().bolos().read();
        bolos.records.removeIf(b -> b != null && boloId.equals(b.id));
        runtime.context().bolos().write(bolos);
    }

    private static void removeRegister(StrajaRuntime runtime, String uuid) {
        var reg = runtime.context().prisonerRegister().read();
        reg.remove(uuid);
        runtime.context().prisonerRegister().write(reg);
    }

    /**
     * Clears every custody artifact of a test player: sentence, waitlist
     * claim, cell assignment, prisoner-register row, and cuff/bound state.
     */
    private static void removeCustodyState(StrajaRuntime runtime, String uuid) {
        var prison = runtime.context().prison().read();
        prison.sentences.removeIf(s -> s != null && uuid.equals(s.targetUuid));
        prison.waitlist.removeIf(w -> w != null && uuid.equals(w.targetUuid));
        prison.assignments.values().removeIf(a -> a != null && uuid.equals(a.targetUuid));
        runtime.context().prison().write(prison);
        var custody = runtime.context().custody().read();
        custody.cuffed.remove(uuid);
        custody.bound.remove(uuid);
        custody.states.remove(uuid);
        custody.headSacks.remove(uuid);
        custody.cuffRequests.values().removeIf(r -> r != null
                && (uuid.equals(r.targetUuid) || uuid.equals(r.issuerUuid)));
        runtime.context().custody().write(custody);
        removeRegister(runtime, uuid);
    }

    /**
     * Drops leftovers keyed to departed mock players. The GameTest world's
     * SavedData survives across runs and every mock player is named
     * {@code test-mock-player} — matching on that name AND an offline uuid
     * drops crashed-run fixtures while never touching a concurrent test's
     * live records or any real-named fixture.
     */
    private static void purgeStaleFixtures(StrajaRuntime runtime, GameTestHelper helper) {
        var playerList = helper.getLevel().getServer().getPlayerList();
        java.util.function.Predicate<String> offline = uuid -> {
            try {
                return uuid == null || playerList.getPlayer(UUID.fromString(uuid)) == null;
            } catch (RuntimeException badUuid) {
                return true;
            }
        };
        java.util.function.Predicate<String> staleMock = target ->
                "test-mock-player".equalsIgnoreCase(target);
        var prison = runtime.context().prison().read();
        prison.sentences.removeIf(s -> s != null && staleMock.test(s.target)
                && offline.test(s.targetUuid));
        prison.waitlist.removeIf(w -> w != null && staleMock.test(w.target)
                && offline.test(w.targetUuid));
        prison.assignments.values().removeIf(a -> a != null && staleMock.test(a.target)
                && offline.test(a.targetUuid));
        runtime.context().prison().write(prison);
        var reg = runtime.context().prisonerRegister().read();
        reg.prisoners().values().removeIf(r -> r != null
                && staleMock.test(r.detaineeName) && offline.test(r.detaineeUuid));
        runtime.context().prisonerRegister().write(reg);
        // #231: bounty fixtures from crashed runs — same name+offline gate.
        var bounties = runtime.context().bounties().read();
        bounties.records.removeIf(r -> r != null
                && staleMock.test(r.targetName) && offline.test(r.targetUuid));
        bounties.surrenders.keySet().removeIf(offline::test);
        runtime.context().bounties().write(bounties);
        // #237: debt fixtures — fine rows keyed to departed mock targets.
        var staleFines = runtime.context().fines().read();
        staleFines.fines.removeIf(f -> f != null
                && staleMock.test(f.target) && offline.test(f.targetUuid));
        runtime.context().fines().write(staleFines);
        // #242: protocol tester entries from crashed runs.
        runtime.protocol().purgeTesters(staleMock);
    }

    private static List<InspectionLedgerEntry> entriesFor(StrajaRuntime runtime,
                                                          String siteId, String uuid) {
        return runtime.context().inspectionLedger().read().entries().stream()
                .filter(e -> e != null && siteId.equals(e.checkpointId)
                        && uuid.equals(e.playerUuid))
                .toList();
    }

    /**
     * AT1 + AT4: a clean player crossing stage 1 is logged PASS with a full
     * inventory snapshot (hotbar + nested bundle contents + offhand); a
     * contraband carrier is warned at stage 1, then passes stage 2 cleanly
     * once the goods are dropped.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void borderPostCrossingLedger(GameTestHelper helper) {
        var runtime = runtime(helper);
        String dim = helper.getLevel().dimension().location().toString();
        BlockPos anchor = helper.absolutePos(new BlockPos(0, 1, 0));

        Runnable cleanup = () -> removeSite(runtime, "at1_border");
        purgeStaleFixtures(runtime, helper);
        cleanup.run(); // clear leftovers from a crashed previous run

        var site = new LawCheckpointRecord();
        site.id = "at1_border";
        site.name = "AT1 Border";
        site.dimension = dim;
        site.mode = CheckpointMode.ARREST;
        // GameTest "empty" structures are 1x1 — neighbor anchors sit only ~6
        // blocks away in x and ~7 in z, so a same-level box anywhere near the
        // anchor can overlap a foreign test's site. Every acceptance test
        // parks its boxes on its own y-band instead: foreign boxes live at
        // anchor.y±6, these live far above, so the pipelines never interfere.
        int by = anchor.getY() + 12;
        site.stage1 = LawBounds.of(dim, anchor.getX(), by, anchor.getZ(),
                anchor.getX() + 8, by + 6, anchor.getZ() + 8);
        site.stage2 = LawBounds.of(dim, anchor.getX(), by, anchor.getZ() + 9,
                anchor.getX() + 8, by + 6, anchor.getZ() + 16);
        // Airborne pushback — repelled players hover above every box and are
        // teleported back inside by the next step before they drift down.
        site.pushback = PushbackPoint.at(dim, anchor.getX() + 4, anchor.getY() + 10,
                anchor.getZ() - 10, 180f);
        // Site-local ban — the global list is shared with other tests whose
        // cleanup could drop the flag mid-crossing.
        site.localIllegalItems.add("minecraft:tnt");
        saveSite(runtime, site);

        ServerPlayer player = mockPlayer(helper);
        String uuid = player.getUUID().toString();
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
        var bundle = new ItemStack(Items.BUNDLE, 1);
        bundle.set(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS,
                new net.minecraft.world.item.component.BundleContents(
                        List.of(new ItemStack(Items.EMERALD, 3))));
        player.getInventory().setItem(1, bundle);
        player.getInventory().offhand.set(0, new ItemStack(Items.TORCH, 1));
        // Staging hangs the player above all checkpoint boxes — the first
        // scan seeds prev-position outside, then the step drops them inside.
        player.teleportTo(anchor.getX() + 4, by + 10, anchor.getZ() + 4);

        // Seed prev-position outside the gate, then walk into stage 1.
        step(helper, 7, () -> {
            player.teleportTo(anchor.getX() + 4, by + 2, anchor.getZ() + 4);
            step(helper, 7, () -> {
                var entries = entriesFor(runtime, "at1_border", uuid);
                helper.assertFalse(entries.isEmpty(),
                        "stage-1 crossing must write a ledger entry");
                var pass = entries.get(entries.size() - 1);
                helper.assertTrue(pass.outcome == CrossingOutcome.PASS,
                        "clean player must PASS stage 1 — got " + pass.outcome);
                // AT4: snapshot covers main, nested bundle rows, and offhand.
                var slots = pass.inventorySnapshot.stream()
                        .map(s -> s.slot + "=" + s.itemId).toList();
                helper.assertTrue(slots.stream().anyMatch(s -> s.contains("minecraft:diamond")),
                        "snapshot must capture the hotbar stack — got " + slots);
                helper.assertTrue(slots.stream().anyMatch(s -> s.contains(">")),
                        "snapshot must include nested bundle rows — got " + slots);
                helper.assertTrue(slots.stream().anyMatch(s -> s.startsWith("offhand")),
                        "snapshot must include the offhand slot — got " + slots);

                // Contraband carrier: stage-1 warns, does not arrest.
                player.getInventory().setItem(2, new ItemStack(Items.TNT, 1));
                player.teleportTo(anchor.getX() + 4, by + 10, anchor.getZ() + 4);
                step(helper, 7, () -> {
                    player.teleportTo(anchor.getX() + 4, by + 2, anchor.getZ() + 4);
                    step(helper, 7, () -> {
                        var warns = entriesFor(runtime, "at1_border", uuid);
                        var warn = warns.get(warns.size() - 1);
                        helper.assertTrue(warn.outcome == CrossingOutcome.WARN,
                                "contraband at stage 1 must WARN — got " + warn.outcome);
                        helper.assertTrue(warn.contrabandSummary.stream()
                                        .anyMatch(l -> l.contains("minecraft:tnt")),
                                "warn entry must name the banned stack");
                        var rec = runtime.context().prisonerRegister().read().prisoner(uuid);
                        helper.assertTrue(rec == null
                                        || (rec.status != PrisonerStatus.IN_CELL
                                        && rec.status != PrisonerStatus.IN_CAMP
                                        && rec.status != PrisonerStatus.ESCORTED),
                                "a warned carrier is not in custody");

                        // Drop the contraband and walk stage 2 — clean pass.
                        player.getInventory().clearContent();
                        player.teleportTo(anchor.getX() + 4, by + 10, anchor.getZ() + 12);
                        step(helper, 7, () -> {
                            player.teleportTo(anchor.getX() + 4, by + 2,
                                    anchor.getZ() + 12);
                            step(helper, 7, () -> {
                                var last = entriesFor(runtime, "at1_border", uuid);
                                var stage2 = last.get(last.size() - 1);
                                helper.assertTrue(stage2.outcome == CrossingOutcome.PASS,
                                        "emptied carrier must PASS stage 2 — got "
                                                + stage2.outcome);
                                cleanup.run();
                                helper.succeed();
                            }, cleanup);
                        }, cleanup);
                    }, cleanup);
                }, cleanup);
            }, cleanup);
        }, cleanup);
    }

    /**
     * AT2: a MINER carrying camp ore is repelled at the mine exit with the
     * quartermaster remedy (carry-ban DENY row), passes once the ore is gone,
     * and a PRISONER with empty pockets is still repelled by role ban.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void mineGateCarryBanAndRoleBan(GameTestHelper helper) {
        var runtime = runtime(helper);
        String dim = helper.getLevel().dimension().location().toString();
        BlockPos anchor = helper.absolutePos(new BlockPos(0, 1, 0));

        purgeStaleFixtures(runtime, helper);
        ServerPlayer miner = mockPlayer(helper);
        String minerUuid = miner.getUUID().toString();
        ServerPlayer prisoner = mockPlayer(helper);
        String prisonerUuid = prisoner.getUUID().toString();

        Runnable cleanup = () -> {
            removeSite(runtime, "at2_mine");
            removeCustodyState(runtime, prisonerUuid);
        };
        cleanup.run();

        var site = new LawCheckpointRecord();
        site.id = "at2_mine";
        site.name = "AT2 Mine Gate";
        site.dimension = dim;
        site.mode = CheckpointMode.DENY;
        int by = anchor.getY() + 24;
        site.stage1 = LawBounds.of(dim, anchor.getX(), by, anchor.getZ(),
                anchor.getX() + 8, by + 6, anchor.getZ() + 8);
        // Airborne pushback — repelled players hover above every foreign box;
        // they fall barely a block inside the assert window, keeping z exact.
        site.pushback = PushbackPoint.at(dim, anchor.getX() + 4, anchor.getY() + 10,
                anchor.getZ() - 10, 180f);
        site.roleCarryBans.put("MINER", new java.util.ArrayList<>(List.of("minecraft:iron_ore")));
        site.roleBans.add("PRISONER");
        saveSite(runtime, site);

        runtime.v2Personnel().enrollProfessional(minerUuid, "miner");
        miner.getInventory().setItem(0, new ItemStack(Items.IRON_ORE, 12));
        miner.teleportTo(anchor.getX() + 4, by + 10, anchor.getZ() + 3);

        var reg = runtime.context().prisonerRegister().read();
        var rec = new PrisonerRegisterRecord(prisonerUuid,
                prisoner.getGameProfile().getName(), "camp detail");
        rec.status = PrisonerStatus.IN_CAMP;
        reg.put(rec);
        runtime.context().prisonerRegister().write(reg);
        // A distinct lane — two players at one spot push each other out.
        prisoner.teleportTo(anchor.getX() + 4, by + 10, anchor.getZ() + 6);

        step(helper, 7, () -> {
            miner.teleportTo(anchor.getX() + 4, by + 2, anchor.getZ() + 3);
            prisoner.teleportTo(anchor.getX() + 4, by + 2, anchor.getZ() + 6);
            step(helper, 7, () -> {
                var minerEntries = entriesFor(runtime, "at2_mine", minerUuid);
                helper.assertFalse(minerEntries.isEmpty(),
                        "miner crossing must be logged");
                var deny = minerEntries.get(minerEntries.size() - 1);
                helper.assertTrue(deny.outcome == CrossingOutcome.DENY,
                        "carry-ban violation at a DENY gate must repel — got "
                                + deny.outcome);
                helper.assertTrue(deny.contrabandSummary.stream()
                                .anyMatch(l -> l.contains("MINER")),
                        "the deny entry must attribute the MINER carry ban — got "
                                + deny.contrabandSummary);
                helper.assertTrue(Math.abs(miner.getZ() - (anchor.getZ() - 10)) < 1.0,
                        "repelled miner must land on the pushback point, z="
                                + miner.getZ());

                var prisonerEntries = entriesFor(runtime, "at2_mine", prisonerUuid);
                helper.assertFalse(prisonerEntries.isEmpty(),
                        "prisoner crossing must be logged");
                var prisonerDeny = prisonerEntries.get(prisonerEntries.size() - 1);
                helper.assertTrue(prisonerDeny.outcome == CrossingOutcome.DENY,
                        "a naked prisoner must still be repelled — got "
                                + prisonerDeny.outcome);
                helper.assertTrue(prisonerDeny.contrabandSummary.isEmpty(),
                        "role-ban repel lists no items — got "
                                + prisonerDeny.contrabandSummary);

                // The same miner, pockets emptied (sold to the quartermaster), passes.
                miner.getInventory().clearContent();
                miner.teleportTo(anchor.getX() + 4, by + 10, anchor.getZ() + 3);
                step(helper, 7, () -> {
                    miner.teleportTo(anchor.getX() + 4, by + 2, anchor.getZ() + 3);
                    step(helper, 7, () -> {
                        var clean = entriesFor(runtime, "at2_mine", minerUuid);
                        var last = clean.get(clean.size() - 1);
                        helper.assertTrue(last.outcome == CrossingOutcome.PASS,
                                "miner without ore must PASS — got " + last.outcome);
                        cleanup.run();
                        helper.succeed();
                    }, cleanup);
                }, cleanup);
            }, cleanup);
        }, cleanup);
    }

    /**
     * AT3 + AT4: a port gate in ARREST mode takes a contraband carrier
     * straight into custody on the stage-2 box, and the arrest record keeps
     * the exact pre-seizure inventory snapshot.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void portGateArrestMode(GameTestHelper helper) {
        var runtime = runtime(helper);
        String dim = helper.getLevel().dimension().location().toString();
        BlockPos anchor = helper.absolutePos(new BlockPos(0, 1, 0));

        Runnable cleanup = () -> {
            removeSite(runtime, "at3_port");
            removeCell(runtime, "at3_cell");
        };
        purgeStaleFixtures(runtime, helper);
        cleanup.run();

        // A cell exists so the lawful arrest lands somewhere real.
        var prison = runtime.context().prison().read();
        var cell = new com.dwurdy.straja.domain.model.Cell();
        cell.id = "at3_cell";
        cell.dimension = dim;
        cell.minX = anchor.getX() - 30; cell.minY = anchor.getY() - 5;
        cell.minZ = anchor.getZ() - 30;
        cell.maxX = anchor.getX() - 25; cell.maxY = anchor.getY() + 5;
        cell.maxZ = anchor.getZ() - 25;
        cell.doorX = anchor.getX() - 27; cell.doorY = anchor.getY(); cell.doorZ = anchor.getZ() - 27;
        prison.cells.add(cell);
        runtime.context().prison().write(prison);

        var site = new LawCheckpointRecord();
        site.id = "at3_port";
        site.name = "AT3 Port Gate";
        site.dimension = dim;
        site.mode = CheckpointMode.ARREST;
        int by = anchor.getY() + 36;
        site.stage2 = LawBounds.of(dim, anchor.getX(), by, anchor.getZ(),
                anchor.getX() + 8, by + 6, anchor.getZ() + 8);
        site.pushback = PushbackPoint.at(dim, anchor.getX() + 4, anchor.getY() + 10,
                anchor.getZ() - 10, 180f);
        site.localIllegalItems.add("minecraft:tnt");
        saveSite(runtime, site);

        ServerPlayer carrier = mockPlayer(helper);
        String uuid = carrier.getUUID().toString();
        carrier.getInventory().setItem(0, new ItemStack(Items.TNT, 1));
        carrier.teleportTo(anchor.getX() + 4, by + 10, anchor.getZ() + 4);

        Runnable fullCleanup = () -> {
            cleanup.run();
            removeCustodyState(runtime, uuid);
        };
        step(helper, 7, () -> {
            carrier.teleportTo(anchor.getX() + 4, by + 2, anchor.getZ() + 4);
            step(helper, 7, () -> {
                var entries = entriesFor(runtime, "at3_port", uuid);
                helper.assertFalse(entries.isEmpty(), "the arrest crossing must be logged");
                var entry = entries.get(entries.size() - 1);
                helper.assertTrue(entry.outcome == CrossingOutcome.ARREST,
                        "contraband in an ARREST gate must arrest — got " + entry.outcome);
                var rec = runtime.context().prisonerRegister().read().prisoner(uuid);
                helper.assertTrue(rec != null && !rec.status.isReleased(),
                        "the carrier must be in custody — got "
                                + (rec == null ? "no record" : rec.status));
                helper.assertTrue(rec.arrestSnapshot != null
                                && !rec.arrestSnapshot.isEmpty(),
                        "the arrest record must keep the pre-seizure snapshot");
                fullCleanup.run();
                helper.succeed();
            }, fullCleanup);
        }, fullCleanup);
    }

    /**
     * AT6: a wanted suspect is arrested on first contact at an arresting
     * gate (mark resolved), while a wanted suspect at a DENY gate is
     * repelled with the sighting logged and the mark left active.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void wantedOnSightArrestAndDenyRepel(GameTestHelper helper) {
        var runtime = runtime(helper);
        String dim = helper.getLevel().dimension().location().toString();
        BlockPos anchor = helper.absolutePos(new BlockPos(0, 1, 0));

        purgeStaleFixtures(runtime, helper);
        ServerPlayer hunted = mockPlayer(helper);
        String huntedUuid = hunted.getUUID().toString();
        ServerPlayer hunted2 = mockPlayer(helper);
        String hunted2Uuid = hunted2.getUUID().toString();

        Runnable cleanup = () -> {
            removeSite(runtime, "at6_arrest");
            removeSite(runtime, "at6_deny");
            removeBolo(runtime, "at6-bolo");
            removeBolo(runtime, "at6-bolo-2");
            removeCustodyState(runtime, huntedUuid);
            removeCustodyState(runtime, hunted2Uuid);
        };
        cleanup.run();

        var arrest = new LawCheckpointRecord();
        arrest.id = "at6_arrest";
        arrest.name = "AT6 Arrest Gate";
        arrest.dimension = dim;
        arrest.mode = CheckpointMode.ARREST;
        int ay = anchor.getY() + 48;
        arrest.stage2 = LawBounds.of(dim, anchor.getX(), ay, anchor.getZ(),
                anchor.getX() + 8, ay + 6, anchor.getZ() + 8);
        arrest.pushback = PushbackPoint.at(dim, anchor.getX() + 4, anchor.getY() + 10,
                anchor.getZ() - 10, 180f);
        saveSite(runtime, arrest);

        var deny = new LawCheckpointRecord();
        deny.id = "at6_deny";
        deny.name = "AT6 Deny Gate";
        deny.dimension = dim;
        deny.mode = CheckpointMode.DENY;
        // A second gate on its own y-band — same x/z column, disjoint in y.
        int dy = anchor.getY() + 60;
        deny.stage1 = LawBounds.of(dim, anchor.getX(), dy, anchor.getZ(),
                anchor.getX() + 8, dy + 6, anchor.getZ() + 8);
        deny.pushback = PushbackPoint.at(dim, anchor.getX() + 4, anchor.getY() + 10,
                anchor.getZ() - 10, 180f);
        saveSite(runtime, deny);

        var bs = runtime.context().bolos().read();
        var bolo = new BoloRecord();
        bolo.id = "at6-bolo";
        bolo.subjectUuid = huntedUuid;
        // Unique subject names — every mock player is "test-mock-player" and a
        // concurrent prisoner's release resolves marks by name fallback.
        bolo.subjectName = "at6-hunted";
        bolo.status = BoloStatus.ACTIVE;
        bs.records.add(bolo);
        runtime.context().bolos().write(bs);
        helper.assertTrue(runtime.wanted().isWanted(hunted.getUUID()),
                "setup: an active BOLO makes the suspect wanted");
        hunted.teleportTo(anchor.getX() + 4, ay + 10, anchor.getZ() + 4);

        bs = runtime.context().bolos().read();
        var bolo2 = new BoloRecord();
        bolo2.id = "at6-bolo-2";
        bolo2.subjectUuid = hunted2Uuid;
        bolo2.subjectName = "at6-hunted-2";
        bolo2.status = BoloStatus.ACTIVE;
        bs.records.add(bolo2);
        runtime.context().bolos().write(bs);
        hunted2.teleportTo(anchor.getX() + 4, dy + 10, anchor.getZ() + 4);

        step(helper, 7, () -> {
            hunted.teleportTo(anchor.getX() + 4, ay + 2, anchor.getZ() + 4);
            hunted2.teleportTo(anchor.getX() + 4, dy + 2, anchor.getZ() + 4);
            step(helper, 7, () -> {
                // AT6a: arrested on sight at the arresting gate, mark resolved.
                var arrestEntries = entriesFor(runtime, "at6_arrest", huntedUuid);
                helper.assertFalse(arrestEntries.isEmpty(),
                        "wanted crossing at an ARREST gate must be logged");
                var arrestEntry = arrestEntries.get(arrestEntries.size() - 1);
                helper.assertTrue(arrestEntry.outcome == CrossingOutcome.ARREST,
                        "wanted suspect must be arrested on sight — got "
                                + arrestEntry.outcome);
                helper.assertTrue(!runtime.wanted().isWanted(hunted.getUUID()),
                        "the wanted mark must clear immediately on arrest");
                var resolved = runtime.context().bolos().read().records.stream()
                        .filter(b -> "at6-bolo".equals(b.id)).findFirst().orElseThrow();
                helper.assertTrue(resolved.status == BoloStatus.RESOLVED,
                        "arrest must record the BOLO as RESOLVED");

                // AT6b: repelled at the deny gate, sighting logged, mark stays.
                var denyEntries = entriesFor(runtime, "at6_deny", hunted2Uuid);
                helper.assertFalse(denyEntries.isEmpty(),
                        "wanted crossing at a DENY gate must be logged");
                var denyEntry = denyEntries.get(denyEntries.size() - 1);
                helper.assertTrue(denyEntry.outcome == CrossingOutcome.DENY,
                        "wanted suspect at a deny gate must be repelled — got "
                                + denyEntry.outcome);
                helper.assertTrue("vânat reperat la poartă".equals(denyEntry.detail),
                        "the sighting must be logged as a wanted repel — got "
                                + denyEntry.detail);
                helper.assertTrue(runtime.wanted().isWanted(hunted2.getUUID()),
                        "a repelled suspect stays wanted");
                cleanup.run();
                helper.succeed();
            }, cleanup);
        }, cleanup);
    }

    /**
     * AT5: the quartermaster pays in the four-coin ladder with an optimal
     * denomination breakdown, goods land in the desk chests, the ledger
     * records the sale, and a modified tier ratio still computes cleanly.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void quartermasterFourCoinEconomy(GameTestHelper helper) {
        var runtime = runtime(helper);
        ServerLevel level = helper.getLevel();
        String dim = level.dimension().location().toString();

        var policies = runtime.context().policies();
        // Restore the TOML-resolved baseline (not the captured live map) so a
        // crashed run's custom ladder can never become the "original" state.
        var baseline = com.dwurdy.straja.config.StrajaServerConfig.toPolicies();

        var chestA = helper.absolutePos(new BlockPos(2, 1, 2));
        var chestB = helper.absolutePos(new BlockPos(3, 1, 2));
        level.setBlock(chestA, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(chestB, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
        // Leave exactly one free slot in chest A so overflow must continue
        // into chest B — proves the sequential-fill contract on real blocks.
        var containerA = (net.minecraft.world.Container) level.getBlockEntity(chestA);
        for (int i = 1; i < containerA.getContainerSize(); i++) {
            containerA.setItem(i, new ItemStack(Items.DIRT, 64));
        }
        var deskPos = helper.absolutePos(new BlockPos(1, 1, 1));

        Runnable cleanup = () -> {
            policies.coinItemIds.clear();
            policies.coinItemIds.putAll(baseline.coinItemIds);
            policies.coinTierRatio = baseline.coinTierRatio;
            var desks = runtime.context().merchantDesks().read();
            desks.remove("at5_qm");
            desks.remove("at5_qm10");
            desks.trades().removeIf(e -> "at5_qm".equals(e.deskId)
                    || "at5_qm10".equals(e.deskId));
            runtime.context().merchantDesks().write(desks);
        };
        // The sell path is fully synchronous — no tick waits — so a plain
        // try/finally is correct here, unlike the crossing tests.
        try {
            cleanup.run(); // drop leftovers + restore policies from a crashed run

            // Vanilla stand-ins; the keys are the tier VALUES (Bronze-equiv).
            // Applied AFTER cleanup() — the restore pass above must not clobber
            // the ladder this test is proving.
            policies.coinTierRatio = 64;
            policies.coinItemIds.clear();
            policies.coinItemIds.put(1, "minecraft:iron_nugget");
            policies.coinItemIds.put(64, "minecraft:gold_nugget");
            policies.coinItemIds.put(4096, "minecraft:emerald");
            policies.coinItemIds.put(262144, "minecraft:diamond");

            var desks = runtime.context().merchantDesks().read();
            var desk = new com.dwurdy.straja.domain.model.MerchantDeskRecord();
            desk.id = "at5_qm";
            desk.dimension = dim;
            desk.deskPos = new com.dwurdy.straja.domain.model.StoragePoint(
                    dim, deskPos.getX(), deskPos.getY(), deskPos.getZ());
            desk.chests.add(new com.dwurdy.straja.domain.model.StoragePoint(
                    dim, chestA.getX(), chestA.getY(), chestA.getZ()));
            desk.chests.add(new com.dwurdy.straja.domain.model.StoragePoint(
                    dim, chestB.getX(), chestB.getY(), chestB.getZ()));
            desk.sellTable.put("minecraft:raw_iron", 1); // 129 ore = 2 brass + 1 bronze at 1:64
            desks.put(desk);
            runtime.context().merchantDesks().write(desks);

            var seller = new VirtualPlayerGateway("at5_seller");
            seller.moveTo(deskPos.getX(), deskPos.getY(), deskPos.getZ());
            seller.giveVerified(ItemSpec.of("minecraft:raw_iron", 129));

            helper.assertTrue(runtime.desks().sell(seller, "at5_qm", null, 0),
                    "the sale must complete: " + seller.messageLog());
            helper.assertTrue(seller.inventory().countOf("minecraft:gold_nugget") == 2
                            && seller.inventory().countOf("minecraft:iron_nugget") == 1
                            && seller.inventory().countOf("minecraft:emerald") == 0,
                    "129 base units must pay as 2 brass + 1 bronze — optimal breakdown");

            long chestIron = 0;
            var containerB = (net.minecraft.world.Container) level.getBlockEntity(chestB);
            for (int i = 0; i < containerA.getContainerSize(); i++) {
                if (containerA.getItem(i).is(Items.RAW_IRON))
                    chestIron += containerA.getItem(i).getCount();
            }
            for (int i = 0; i < containerB.getContainerSize(); i++) {
                if (containerB.getItem(i).is(Items.RAW_IRON))
                    chestIron += containerB.getItem(i).getCount();
            }
            helper.assertTrue(chestIron == 129,
                    "all sold ore lands across the desk chests — got " + chestIron);
            helper.assertTrue(containerB.getItem(0).is(Items.RAW_IRON)
                            || chestIron > 64,
                    "overflow must continue into the second chest");

            var trades = runtime.context().merchantDesks().read().trades();
            helper.assertTrue(trades.stream().anyMatch(e -> "at5_qm".equals(e.deskId)
                            && e.baseUnits == 129),
                    "the ledger must record the sale in base units");

            // Modified tier ratio (1:10): a fresh desk pays cleanly without
            // truncation — the same ledger math at a custom TOML ladder.
            policies.coinTierRatio = 10;
            policies.coinItemIds.clear();
            policies.coinItemIds.put(1, "minecraft:iron_nugget");
            policies.coinItemIds.put(10, "minecraft:gold_nugget");
            policies.coinItemIds.put(100, "minecraft:emerald");
            policies.coinItemIds.put(1000, "minecraft:diamond");
            var desks2 = runtime.context().merchantDesks().read();
            var desk2 = new com.dwurdy.straja.domain.model.MerchantDeskRecord();
            desk2.id = "at5_qm10";
            desk2.dimension = dim;
            desk2.deskPos = new com.dwurdy.straja.domain.model.StoragePoint(
                    dim, deskPos.getX(), deskPos.getY(), deskPos.getZ());
            desk2.chests.add(new com.dwurdy.straja.domain.model.StoragePoint(
                    dim, chestA.getX(), chestA.getY(), chestA.getZ()));
            desk2.chests.add(new com.dwurdy.straja.domain.model.StoragePoint(
                    dim, chestB.getX(), chestB.getY(), chestB.getZ()));
            desk2.sellTable.put("minecraft:coal", 1); // 25 coal = 2 brass + 5 bronze at 1:10
            desks2.put(desk2);
            runtime.context().merchantDesks().write(desks2);
            var seller2 = new VirtualPlayerGateway("at5_seller2");
            seller2.moveTo(deskPos.getX(), deskPos.getY(), deskPos.getZ());
            seller2.giveVerified(ItemSpec.of("minecraft:coal", 25));
            helper.assertTrue(runtime.desks().sell(seller2, "at5_qm10", null, 0),
                    "custom-ratio sale must complete: " + seller2.messageLog());
            helper.assertTrue(seller2.inventory().countOf("minecraft:gold_nugget") == 2
                            && seller2.inventory().countOf("minecraft:iron_nugget") == 5,
                    "25 base units at 1:10 must pay 2x10 + 5x1");
            helper.succeed();
        } finally {
            cleanup.run();
        }
    }

    /**
     * AT7 (camp exit): a camp prisoner repelled at the camp's exit gate loses
     * the banned ore they tried to smuggle out — the confiscated stacks route
     * into evidence rather than back into pockets.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void laborCampExitConfiscation(GameTestHelper helper) {
        var runtime = runtime(helper);
        String dim = helper.getLevel().dimension().location().toString();
        BlockPos anchor = helper.absolutePos(new BlockPos(0, 1, 0));

        purgeStaleFixtures(runtime, helper);
        ServerPlayer prisoner = mockPlayer(helper);
        String uuid = prisoner.getUUID().toString();

        Runnable cleanup = () -> {
            removeSite(runtime, "at7_exit");
            removeCamp(runtime, "at7_camp");
            removeCustodyState(runtime, uuid);
        };
        cleanup.run();

        var commissioner = new VirtualPlayerGateway("dwurdy");
        commissioner.setOp(true);
        commissioner.setDimension(dim);
        var min = helper.absolutePos(new BlockPos(0, -60, 0));
        var max = helper.absolutePos(new BlockPos(10, 10, 10));
        helper.assertTrue(runtime.laborCamps().register(commissioner, "at7_camp", "AT7 Camp",
                min.getX() + "," + min.getY() + "," + min.getZ(),
                max.getX() + "," + max.getY() + "," + max.getZ()),
                "camp registration must succeed: " + commissioner.messageLog());
        var campStore = runtime.context().laborCamps().read();
        var camp = campStore.camp("at7_camp");
        camp.exitCheckpointId = "at7_exit";
        campStore.put(camp);
        runtime.context().laborCamps().write(campStore);

        var site = new LawCheckpointRecord();
        site.id = "at7_exit";
        site.name = "AT7 Camp Exit";
        site.dimension = dim;
        site.mode = CheckpointMode.DENY;
        int by = anchor.getY() + 72;
        site.stage1 = LawBounds.of(dim, anchor.getX() + 2, by,
                anchor.getZ() + 2, anchor.getX() + 9, by + 6, anchor.getZ() + 9);
        site.pushback = PushbackPoint.at(dim, anchor.getX() + 5, anchor.getY() + 10,
                anchor.getZ() - 5, 180f);
        site.roleCarryBans.put("PRISONER",
                new java.util.ArrayList<>(List.of("minecraft:iron_ore")));
        // Confiscated cargo routes into the site's physical evidence chest —
        // confiscateItems fills StoragePoints, it does not write evidence
        // records (those belong to the arrest-seizure chain).
        var evidencePos = helper.absolutePos(new BlockPos(4, 1, 14));
        helper.getLevel().setBlock(evidencePos,
                net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
        site.evidenceChests.add(new com.dwurdy.straja.domain.model.StoragePoint(
                dim, evidencePos.getX(), evidencePos.getY(), evidencePos.getZ()));
        saveSite(runtime, site);

        var reg = runtime.context().prisonerRegister().read();
        var rec = new PrisonerRegisterRecord(uuid,
                prisoner.getGameProfile().getName(), "labor");
        rec.status = PrisonerStatus.IN_CAMP;
        rec.assignedCampId = "at7_camp";
        reg.put(rec);
        runtime.context().prisonerRegister().write(reg);
        prisoner.getInventory().setItem(0, new ItemStack(Items.IRON_ORE, 5));
        prisoner.teleportTo(anchor.getX() + 5, by + 10, anchor.getZ() + 5);

        step(helper, 7, () -> {
            prisoner.teleportTo(anchor.getX() + 5, by + 2, anchor.getZ() + 5);
            step(helper, 7, () -> {
                var entries = entriesFor(runtime, "at7_exit", uuid);
                helper.assertFalse(entries.isEmpty(),
                        "the camp-exit crossing must be logged");
                var entry = entries.get(entries.size() - 1);
                helper.assertTrue(entry.outcome == CrossingOutcome.DENY,
                        "camp prisoner at the exit gate must be repelled — got "
                                + entry.outcome);
                helper.assertTrue(prisoner.getInventory()
                                .countItem(Items.IRON_ORE) == 0,
                        "banned ore must be confiscated out of the pockets");
                var evidenceChest = (net.minecraft.world.Container)
                        helper.getLevel().getBlockEntity(evidencePos);
                int seized = 0;
                for (int i = 0; i < evidenceChest.getContainerSize(); i++) {
                    var st = evidenceChest.getItem(i);
                    if (st.is(Items.IRON_ORE)) seized += st.getCount();
                }
                helper.assertTrue(seized == 5,
                        "confiscated ore must land in the evidence chest — got "
                                + seized);
                cleanup.run();
                helper.succeed();
            }, cleanup);
        }, cleanup);
    }

    /**
     * AT8: a cuffed suspect beside their officer passes a deny gate that
     * repels unescorted prisoners, cuffing suppresses sprinting, and uncuff
     * inside the cell restores custody while uncuff outside marks fugitive.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void escortBypassAndUncuffOutcome(GameTestHelper helper) {
        var runtime = runtime(helper);
        String dim = helper.getLevel().dimension().location().toString();
        BlockPos anchor = helper.absolutePos(new BlockPos(0, 1, 0));

        purgeStaleFixtures(runtime, helper);
        ServerPlayer officer = mockPlayer(helper);
        ServerPlayer suspect = mockPlayer(helper);
        String suspectUuid = suspect.getUUID().toString();
        String officerUuid = officer.getUUID().toString();

        Runnable cleanup = () -> {
            removeSite(runtime, "at8_gate");
            removeCell(runtime, "at8_cell");
            removeCustodyState(runtime, suspectUuid);
            removeCustodyState(runtime, officerUuid);
        };
        cleanup.run();

        // Cell the prisoner belongs to — uncuff inside it restores custody.
        var prison = runtime.context().prison().read();
        var cell = new com.dwurdy.straja.domain.model.Cell();
        cell.id = "at8_cell";
        cell.dimension = dim;
        cell.minX = anchor.getX() - 30; cell.minY = anchor.getY() - 5;
        cell.minZ = anchor.getZ() - 30;
        cell.maxX = anchor.getX() - 24; cell.maxY = anchor.getY() + 5;
        cell.maxZ = anchor.getZ() - 24;
        cell.doorX = anchor.getX() - 27; cell.doorY = anchor.getY(); cell.doorZ = anchor.getZ() - 27;
        prison.cells.add(cell);
        runtime.context().prison().write(prison);

        var site = new LawCheckpointRecord();
        site.id = "at8_gate";
        site.name = "AT8 Deny Gate";
        site.dimension = dim;
        site.mode = CheckpointMode.DENY;
        int by = anchor.getY() + 84;
        site.stage1 = LawBounds.of(dim, anchor.getX(), by, anchor.getZ(),
                anchor.getX() + 8, by + 6, anchor.getZ() + 8);
        site.pushback = PushbackPoint.at(dim, anchor.getX() + 4, anchor.getY() + 10,
                anchor.getZ() - 10, 180f);
        site.roleBans.add("PRISONER");
        saveSite(runtime, site);

        var officerGw = qualifyOfficer(runtime, officer);
        var suspectGw = gateway(suspect);

        var commissioner = new VirtualPlayerGateway("dwurdy");
        commissioner.setOp(true);
        commissioner.setDimension(dim);
        // Book the suspect into a cell so "inside custody" has meaning. The
        // cell pool is shared with concurrent tests, so the assignment may
        // land anywhere — resolve the record and use its real bounds.
        var sentence = runtime.prison().arrest(suspectGw, null, 1, commissioner, "at8-booking");
        helper.assertTrue(sentence != null && !sentence.cellId.isEmpty(),
                "the suspect must be booked into a cell");
        var assigned = runtime.context().prison().read().cell(sentence.cellId);
        helper.assertTrue(assigned != null && dim.equals(assigned.dimension),
                "the assigned cell must resolve in this dimension");
        double cellX = (assigned.minX + assigned.maxX) / 2.0 + 0.5;
        double cellY = assigned.minY + 1.0;
        double cellZ = (assigned.minZ + assigned.maxZ) / 2.0 + 0.5;
        var booked = runtime.context().prisonerRegister().read().prisoner(suspectUuid);
        helper.assertTrue(booked != null,
                "booking must create a prisoner-register record");

        officer.getInventory().add(new ItemStack(
                com.dwurdy.straja.bootstrap.StrajaItems.CUFFS.get()));
        suspect.teleportTo(cellX, cellY, cellZ);
        officer.teleportTo(cellX, cellY, cellZ + 1);
        var applied = runtime.custody().applyCuffsDirect(officerGw, suspectGw, "at8");
        helper.assertTrue(applied.ok(), "the officer must be able to cuff: "
                + applied.reason());
        helper.assertTrue(runtime.custodyRoleplay().isCuffed(suspectGw),
                "the suspect must be cuffed for escort bypass");

        // Escorted suspect walks into a PRISONER-banned deny gate beside
        // the officer — the escort bypass passes them, no repel.
        step(helper, 7, () -> {
            suspect.teleportTo(anchor.getX() + 4, by + 2, anchor.getZ() + 4);
            officer.teleportTo(anchor.getX() + 5, by + 2, anchor.getZ() + 4);
            step(helper, 7, () -> {
                var entries = entriesFor(runtime, "at8_gate", suspectUuid);
                helper.assertTrue(entries.stream()
                                .noneMatch(e -> e.outcome == CrossingOutcome.DENY),
                        "an escorted suspect beside the officer must not be repelled");
                helper.assertTrue(suspect.getZ() > anchor.getZ(),
                        "the suspect must stay inside the gate zone, z="
                                + suspect.getZ());

                // Custody suppresses sprinting while cuffed.
                suspect.setSprinting(true);
                step(helper, 6, () -> {
                    helper.assertFalse(suspect.isSprinting(),
                            "a cuffed suspect cannot sprint");

                    // Uncuff inside the cell first — normal custody resumes.
                    officer.getInventory().setItem(0, new ItemStack(
                            com.dwurdy.straja.bootstrap.StrajaItems.CUFF_KEY.get()));
                    officer.getInventory().selected = 0;
                    suspect.teleportTo(cellX, cellY, cellZ);
                    officer.teleportTo(cellX, cellY, cellZ + 1);
                    helper.assertTrue(runtime.custodyRoleplay()
                                    .release(officerGw, suspectGw),
                            "the officer's cuff key must release inside the cell");
                    var inside = runtime.context().prisonerRegister().read()
                            .prisoner(suspectUuid);
                    var liveSentence = runtime.context().prison().read()
                            .sentences.stream()
                            .filter(s -> suspectUuid.equals(s.targetUuid)
                                    && "ACTIVE".equals(s.status))
                            .findFirst().orElse(null);
                    var liveCell = liveSentence == null || liveSentence.cellId == null
                            || liveSentence.cellId.isEmpty()
                            ? null : runtime.context().prison().read().cell(liveSentence.cellId);
                    helper.assertTrue(inside != null
                                    && inside.status == PrisonerStatus.IN_CELL,
                            "uncuff inside custody must restore the cell — got "
                                    + (inside == null ? "no record" : inside.status)
                                    + " | sentence=" + (liveSentence == null ? "null"
                                            : liveSentence.id + " cell=" + liveSentence.cellId)
                                    + " liveCell=" + (liveCell == null ? "null" : liveCell.id)
                                    + " pos=" + suspect.getX() + "," + suspect.getY() + "," + suspect.getZ()
                                    + " cellBounds=" + (assigned.minX) + ".." + (assigned.maxX) + ","
                                    + assigned.minY + ".." + assigned.maxY + ","
                                    + assigned.minZ + ".." + assigned.maxZ
                                    + " camp=" + (inside == null ? "?" : inside.assignedCampId));

                    // Re-cuff and uncuff outside custody → fugitive again.
                    // The key was consumed by the first release — the officer
                    // holds a fresh one.
                    officer.getInventory().add(new ItemStack(
                            com.dwurdy.straja.bootstrap.StrajaItems.CUFFS.get()));
                    var reapplied = runtime.custody()
                            .applyCuffsDirect(officerGw, suspectGw, "at8-re");
                    helper.assertTrue(reapplied.ok(), "re-cuff must succeed: "
                            + reapplied.reason());
                    // "Outside custody" — airborne above every cell and site.
                    suspect.teleportTo(anchor.getX() + 4, anchor.getY() + 110, anchor.getZ() + 4);
                    officer.teleportTo(anchor.getX() + 4, anchor.getY() + 110, anchor.getZ() + 5);
                    officer.getInventory().setItem(0, new ItemStack(
                            com.dwurdy.straja.bootstrap.StrajaItems.CUFF_KEY.get()));
                    officer.getInventory().selected = 0;
                    helper.assertTrue(runtime.custodyRoleplay()
                                    .release(officerGw, suspectGw),
                            "the officer's cuff key must release outside");
                    var rec = runtime.context().prisonerRegister().read()
                            .prisoner(suspectUuid);
                    helper.assertTrue(rec != null
                                    && rec.status == PrisonerStatus.FUGITIVE,
                            "uncuff outside custody must mark FUGITIVE — got "
                                    + (rec == null ? "no record" : rec.status));
                    cleanup.run();
                    helper.succeed();
                }, cleanup);
            }, cleanup);
        }, cleanup);
    }

    /**
     * LAW-008 TOML proof: the GameTest server loads config/straja-server.toml
     * the same way a dedicated server does — the [economy] section must parse
     * into the resolved policy map with the default 1:64 ladder and Ady's
     * Decorations coin items.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void tomlCoinEconomyDefaults(GameTestHelper helper) {
        var runtime = runtime(helper);
        int ratio = com.dwurdy.straja.config.StrajaServerConfig.COIN_TIER_RATIO.get();
        helper.assertTrue(ratio == 64,
                "TOML [economy] tierRatio must default to 64 — got " + ratio);
        var coins = runtime.context().policies().coinItemIds;
        helper.assertTrue("adys_decorations:bronze_coin".equals(coins.get(1)),
                "tier 1 must be adys_decorations:bronze_coin — got " + coins.get(1));
        helper.assertTrue("adys_decorations:brass_coin".equals(coins.get(64)),
                "tier 64 must be adys_decorations:brass_coin — got " + coins.get(64));
        helper.assertTrue("adys_decorations:silver_coin".equals(coins.get(4096)),
                "tier 4096 must be adys_decorations:silver_coin — got " + coins.get(4096));
        helper.assertTrue("adys_decorations:gold_coin".equals(coins.get(262144)),
                "tier 262144 must be adys_decorations:gold_coin — got " + coins.get(262144));
        helper.assertTrue(runtime.context().policies().coinTierRatio == 64,
                "resolved policy ratio must be 64");
        helper.succeed();
    }

    /**
     * LAW-008 migration proof: legacy KubeJS persistent-data blobs ingest into
     * the live SavedData-backed stores — a checkpoint site lands in
     * law_checkpoints and an offline jailed name lands in prisoner_register —
     * and a second run adds no duplicates (fail-closed idempotency).
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void kubeJsMigrationIngestsLiveStores(GameTestHelper helper) {
        var runtime = runtime(helper);
        String legacyKey = com.dwurdy.straja.domain.model.PrisonerRegisterStore
                .legacyUuid("at8mig_smuggler");
        Runnable cleanup = () -> {
            removeSite(runtime, "at8mig_port");
            removeRegister(runtime, legacyKey);
            var store = runtime.context().lawCheckpoints().read();
            store.globalIllegalItems().remove("minecraft:lapis_lazuli");
            store.globalBans().remove("at8mig_banned");
            runtime.context().lawCheckpoints().write(store);
        };
        cleanup.run();
        try {
            java.util.Map<String, String> data = new java.util.LinkedHashMap<>();
            data.put("portCheckpointCfg", """
                {"contraband":{"minecraft:lapis_lazuli":true},
                 "banned":{"at8mig_banned":true},
                 "sites":{"at8mig_port":{
                   "denyTarget":{"dim":"minecraft:overworld","x":5.5,"y":61.0,"z":-2.5,"yaw":180.0},
                   "evidence":[{"dim":"minecraft:overworld","x":20,"y":60,"z":20}],
                   "cb":{"minecraft:raw_copper":true}}}}
                """);
            data.put("strajaPrisonJail", """
                {"jailed":{"at8mig_smuggler":{"t":1700000000000,"reason":"contraband",
                    "status":"jailed","arrests":2}},"fines":{"at8mig_smuggler":128}}
                """);
            var report = runtime.migration().migrateServer(data);
            helper.assertTrue(report.ok(),
                    "legacy blobs must ingest — " + String.join(" | ", report.lines()));

            var site = runtime.context().lawCheckpoints().read()
                    .checkpoint("at8mig_port");
            helper.assertTrue(site != null,
                    "legacy site must land in law_checkpoints");
            helper.assertTrue(site.pushback != null
                            && site.pushback.yaw() == 180f,
                    "denyTarget must migrate to the pushback point");
            helper.assertTrue(site.evidenceChests.size() == 1,
                    "evidence chest must migrate");
            helper.assertTrue(site.localIllegalItems.contains("minecraft:raw_copper"),
                    "site cb=true must become a local ban");
            var rec = runtime.context().prisonerRegister().read()
                    .prisoner(legacyKey);
            helper.assertTrue(rec != null
                            && rec.status == PrisonerStatus.IN_CELL,
                    "legacy jailed name must land IN_CELL under legacyUuid — got "
                            + (rec == null ? "no record" : rec.status));
            helper.assertTrue(rec != null && rec.outstandingFines == 128,
                    "legacy fine must join on the prisoner name");

            int siteCount = runtime.context().lawCheckpoints().read()
                    .checkpoints().size();
            var rerun = runtime.migration().migrateServer(data);
            helper.assertTrue(rerun.ok(), "re-run must stay fail-closed");
            helper.assertTrue(runtime.context().lawCheckpoints().read()
                            .checkpoints().size() == siteCount,
                    "idempotent re-run must not duplicate sites");
            cleanup.run();
            helper.succeed();
        } finally {
            cleanup.run();
        }
    }

    /**
     * AT9 (#231): a state-issued bounty turns a wanted player into lawful
     * civilian prey — the rope only bites on a downed/surrendered captive,
     * dragging them across an ARREST gate runs the full arrest pipeline,
     * and the capture pays the hunter while a 2x bail fine lands on the
     * prisoner.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void bountyCaptureDelivery(GameTestHelper helper) {
        var runtime = runtime(helper);
        ServerLevel level = helper.getLevel();
        String dim = level.dimension().location().toString();
        BlockPos anchor = helper.absolutePos(new BlockPos(0, 2, 0));
        purgeStaleFixtures(runtime, helper);

        ServerPlayer hunter = mockPlayer(helper);
        ServerPlayer crook = mockPlayer(helper);
        var hunterGw = gateway(hunter);
        var crookGw = gateway(crook);
        String crookUuid = crook.getUUID().toString();

        Runnable cleanup = () -> {
            removeSite(runtime, "at9_intake");
            removeCell(runtime, "at9_cell");
            var store = runtime.context().bounties().read();
            var rec = store.records.stream()
                    .filter(r -> r != null && crookUuid.equals(r.targetUuid))
                    .findFirst().orElse(null);
            String boloId = rec == null ? "at9-linked" : rec.linkedBoloId;
            String fineId = rec == null ? "" : rec.linkedFineId;
            store.records.removeIf(r -> r != null && crookUuid.equals(r.targetUuid));
            runtime.context().bounties().write(store);
            if (boloId != null && !boloId.isBlank()) removeBolo(runtime, boloId);
            if (fineId != null && !fineId.isBlank()) {
                var fines = runtime.context().fines().read();
                fines.fines.removeIf(f -> f != null && fineId.equals(f.id));
                runtime.context().fines().write(fines);
            }
            removeCustodyState(runtime, crookUuid);
            removeRegister(runtime, crookUuid);
        };
        cleanup.run();

        // A dedicated cell on this test's y-band: the arrest must land a real
        // custody slot without starving concurrent tests' shared fixtures.
        var prison = runtime.context().prison().read();
        var cell = new com.dwurdy.straja.domain.model.Cell();
        cell.id = "at9_cell";
        cell.dimension = dim;
        cell.minX = anchor.getX() - 30; cell.minY = anchor.getY() + 90;
        cell.minZ = anchor.getZ() - 30;
        cell.maxX = anchor.getX() - 25; cell.maxY = anchor.getY() + 98;
        cell.maxZ = anchor.getZ() - 25;
        cell.doorX = anchor.getX() - 27; cell.doorY = anchor.getY() + 94;
        cell.doorZ = anchor.getZ() - 27;
        prison.cells.add(cell);
        runtime.context().prison().write(prison);

        int ay = anchor.getY() + 96;
        var intake = new LawCheckpointRecord();
        intake.id = "at9_intake";
        intake.name = "AT9 Intake";
        intake.dimension = dim;
        intake.mode = CheckpointMode.ARREST;
        intake.stage1 = LawBounds.of(dim, anchor.getX(), ay, anchor.getZ(),
                anchor.getX() + 8, ay + 6, anchor.getZ() + 8);
        intake.stage2 = LawBounds.of(dim, anchor.getX() + 2, ay + 1,
                anchor.getZ() + 2, anchor.getX() + 6, ay + 5, anchor.getZ() + 6);
        intake.pushback = PushbackPoint.at(dim, anchor.getX() + 4,
                anchor.getY() + 10, anchor.getZ() - 10, 180f);
        saveSite(runtime, intake);

        // Inspector-rank issuer posts the bounty; the linked BOLO makes the
        // crook wanted-on-sight at arrest gates.
        ServerPlayer issuer = mockPlayer(helper);
        var issuerGw = gateway(issuer);
        var issuerState = runtime.players().state(issuerGw);
        issuerState.rank = 4; // INSPECTOR
        runtime.players().save(issuer.getUUID(), issuerState);
        var bounty = runtime.bounties().post(issuerGw, crookGw, 128, "at9 reason");
        helper.assertTrue(bounty != null, "an Inspector must be able to post a bounty");
        helper.assertTrue(runtime.wanted().isWanted(crook.getUUID()),
                "posting a bounty must make the target wanted-on-sight");
        // Unique subject name: every mock is "test-mock-player" and a
        // concurrent prisoner's release resolves marks by name fallback —
        // a shared name would let a foreign release kill this BOLO early.
        var linkedStore = runtime.context().bolos().read();
        var linked = linkedStore.find(bounty.linkedBoloId);
        if (linked != null) {
            linked.subjectName = "at9-crook";
            runtime.context().bolos().write(linkedStore);
        }

        // A conscious bountied target cannot be roped — the fight must be won.
        hunterGw.give(ItemSpec.of("straja:rope", 1));
        hunterGw.selectSlot(0);
        hunter.teleportTo(anchor.getX() + 4, ay + 10, anchor.getZ() + 4);
        crook.teleportTo(anchor.getX() + 5, ay + 10, anchor.getZ() + 4);
        helper.assertTrue(!runtime.custody().applyRope(hunterGw, crookGw),
                "a conscious bountied target must refuse the rope");

        // Downed, the capture is lawful — the rope marks the record for the
        // bounty pipeline.
        helper.assertTrue(runtime.custody().startDowned(crookGw, hunterGw,
                "at9 knockout") != null, "the crook must go down");
        helper.assertTrue(runtime.custody().applyRope(hunterGw, crookGw),
                "a downed bountied target must accept the rope");
        var boundRec = runtime.context().custody().read().bound.get(crookUuid);
        helper.assertTrue(boundRec != null
                        && "bounty_capture".equals(boundRec.reason),
                "the bound record must carry the bounty-capture reason — got "
                        + (boundRec == null ? "null" : boundRec.reason));

        // The captive crosses the intake gate — wanted-on-sight runs the full
        // arrest, which resolves the bounty and pays the hunter.
        step(helper, 7, () -> {
            crook.teleportTo(anchor.getX() + 4, ay + 2, anchor.getZ() + 4);
            step(helper, 14, () -> {
                var entries = entriesFor(runtime, "at9_intake", crookUuid);
                helper.assertFalse(entries.isEmpty(),
                        "the captive's delivery crossing must be logged");
                var last = entries.get(entries.size() - 1);
                helper.assertTrue(last.outcome == CrossingOutcome.ARREST,
                        "a bound bountied captive must be fully arrested at the "
                                + "gate — got " + last.outcome);
                var resolved = runtime.context().bounties().read()
                        .records.stream()
                        .filter(r -> crookUuid.equals(r.targetUuid))
                        .findFirst().orElseThrow();
                helper.assertTrue(resolved.status
                                == com.dwurdy.straja.domain.model.BountyStatus.CAPTURED,
                        "gate arrest must capture the bounty — got "
                                + resolved.status);
                helper.assertTrue(hunter.getUUID().toString()
                                .equals(resolved.hunterUuid),
                        "the binding hunter must be credited — got "
                                + resolved.hunterUuid);
                var fine = runtime.context().fines().read()
                        .find(resolved.linkedFineId);
                helper.assertTrue(fine != null && fine.amount == 256
                                && "IN_SENTENCE".equals(fine.status),
                        "the prisoner must owe a 2x in-sentence bail fine — got "
                                + (fine == null ? "null" : fine.amount + "/" + fine.status));
                cleanup.run();
                helper.succeed();
            }, cleanup);
        }, cleanup);
    }

    /**
     * AT10 (#237 DEBT): an in-custody debtor cannot hold coins or walk free.
     * A jailer release runs the debt gate, which levies the seized personal
     * locker first — the arrest-time seizure poured the prisoner's coin
     * stacks into a real pool chest, exactly like a live arrest — applies
     * the take oldest-first onto {@code paidAmount}, then the
     * still-over-threshold balance diverts the release to the default camp
     * ({@code onBlocked=CAMP}, the shipped default). A third-party
     * {@code /straja debt pay} settles the remainder — both fines flip
     * PAID — and the next release completes.
     *
     * <p>Fully synchronous like AT5: arrest, levy, gate and contribution
     * are direct service calls with no tick waits, so a plain try/finally
     * cleans up instead of scheduled steps.
     *
     * <p>Runs in its own {@code debtAt10} batch so it cannot perturb the
     * forty concurrent defaultBatch tests: it temporarily rewrites the
     * shared {@code runtime.context().policies()} (coin ladder, debt
     * thresholds, {@code bountyDefaultCampId=at10_camp}), and vanilla batch
     * boundaries are the only framework mechanism that serialises tests —
     * batches run sequentially while tests inside a batch run concurrently.
     */
    @GameTest(template = "empty", timeoutTicks = 200, batch = "debtAt10")
    public static void debtGateAndContributions(GameTestHelper helper) {
        var runtime = runtime(helper);
        ServerLevel level = helper.getLevel();
        String dim = level.dimension().location().toString();
        BlockPos anchor = helper.absolutePos(new BlockPos(0, 2, 0));

        purgeStaleFixtures(runtime, helper);
        ServerPlayer prisoner = mockPlayer(helper);
        ServerPlayer jailer = mockPlayer(helper);
        String uuid = prisoner.getUUID().toString();
        String jailerUuid = jailer.getUUID().toString();

        var policies = runtime.context().policies();
        var baseline = com.dwurdy.straja.config.StrajaServerConfig.toPolicies();
        var lockerPos = helper.absolutePos(new BlockPos(3, 1, 2));
        var lockerPoint = new com.dwurdy.straja.domain.model.StoragePoint(
                dim, lockerPos.getX(), lockerPos.getY(), lockerPos.getZ());

        Runnable cleanup = () -> {
            policies.coinItemIds.clear();
            policies.coinItemIds.putAll(baseline.coinItemIds);
            policies.coinTierRatio = baseline.coinTierRatio;
            policies.debtEnabled = baseline.debtEnabled;
            policies.debtReleaseBlockThreshold = baseline.debtReleaseBlockThreshold;
            policies.debtOnBlocked = baseline.debtOnBlocked;
            policies.bountyDefaultCampId = baseline.bountyDefaultCampId;
            var prisonData = runtime.context().prison().read();
            prisonData.lockerPool.removeIf(p -> p != null
                    && lockerPoint.key().equals(p.key()));
            runtime.context().prison().write(prisonData);
            var fineData = runtime.context().fines().read();
            fineData.fines.removeIf(f -> f != null && uuid.equals(f.targetUuid));
            runtime.context().fines().write(fineData);
            removeCell(runtime, "at10_cell");
            removeCamp(runtime, "at10_camp");
            removeCustodyState(runtime, uuid);
            removeCustodyState(runtime, jailerUuid);
            if (level.getBlockEntity(lockerPos)
                    instanceof net.minecraft.world.Container chest) {
                chest.clearContent();
            }
            level.setBlock(lockerPos,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        };
        try {
            cleanup.run(); // drop leftovers + restore policies from a crashed run

            // Vanilla stand-in coin ladder — Ady's Decorations is absent
            // from the headless rig (see AT5); restored in the finally.
            policies.coinTierRatio = 64;
            policies.coinItemIds.clear();
            policies.coinItemIds.put(1, "minecraft:iron_nugget");
            policies.coinItemIds.put(64, "minecraft:gold_nugget");
            policies.coinItemIds.put(4096, "minecraft:emerald");
            policies.coinItemIds.put(262144, "minecraft:diamond");
            // The shipped [debt] defaults — pinned so a crashed run's
            // leftovers cannot flip the assertion.
            policies.debtEnabled = true;
            policies.debtReleaseBlockThreshold = 0;
            policies.debtOnBlocked = "CAMP";
            policies.bountyDefaultCampId = "at10_camp";

            // A dedicated cell on this test's band: the arrest must land a
            // real custody slot without starving the shared pool (AT9 lesson).
            var prison = runtime.context().prison().read();
            var cell = new com.dwurdy.straja.domain.model.Cell();
            cell.id = "at10_cell";
            cell.dimension = dim;
            cell.minX = anchor.getX() - 30; cell.minY = anchor.getY() + 102;
            cell.minZ = anchor.getZ() - 30;
            cell.maxX = anchor.getX() - 25; cell.maxY = anchor.getY() + 110;
            cell.maxZ = anchor.getZ() - 25;
            cell.doorX = anchor.getX() - 27; cell.doorY = anchor.getY() + 106;
            cell.doorZ = anchor.getZ() - 27;
            prison.cells.add(cell);
            // The locker pool feeds routeLocker — arrest seizure pours the
            // prisoner's coins into this chest like a live booking would.
            level.setBlock(lockerPos,
                    net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
            prison.lockerPool.add(lockerPoint);
            runtime.context().prison().write(prison);

            var commissioner = new VirtualPlayerGateway("dwurdy");
            commissioner.setOp(true);
            commissioner.setDimension(dim);
            var campMin = helper.absolutePos(new BlockPos(12, 102, 0));
            var campMax = helper.absolutePos(new BlockPos(20, 110, 8));
            helper.assertTrue(runtime.laborCamps().register(commissioner,
                            "at10_camp", "AT10 Camp",
                            campMin.getX() + "," + campMin.getY() + "," + campMin.getZ(),
                            campMax.getX() + "," + campMax.getY() + "," + campMax.getZ()),
                    "camp registration must succeed: " + commissioner.messageLog());

            // Two payable fines — issuedAt drives the oldest-first order.
            var fineData = runtime.context().fines().read();
            var oldFine = new com.dwurdy.straja.domain.model.Fine();
            oldFine.id = "at10-fine-old";
            oldFine.target = prisoner.getGameProfile().getName();
            oldFine.targetUuid = uuid;
            oldFine.law = "at10_old";
            oldFine.amount = 100;
            oldFine.status = "ISSUED";
            oldFine.issuedAt = 1000L;
            var newFine = new com.dwurdy.straja.domain.model.Fine();
            newFine.id = "at10-fine-new";
            newFine.target = oldFine.target;
            newFine.targetUuid = uuid;
            newFine.law = "at10_new";
            newFine.amount = 50;
            newFine.status = "ISSUED";
            newFine.issuedAt = 2000L;
            fineData.fines.add(oldFine);
            fineData.fines.add(newFine);
            runtime.context().fines().write(fineData);
            helper.assertTrue(runtime.debt().outstandingDebt(uuid) == 150,
                    "setup: the prisoner must owe 150 base units");

            // Custody intake: the coins ride the seizure into the locker chest.
            prisoner.getInventory().setItem(0, new ItemStack(Items.GOLD_NUGGET, 1));
            prisoner.getInventory().setItem(1, new ItemStack(Items.IRON_NUGGET, 16));
            var sentence = runtime.prison().arrest(gateway(prisoner), null, 1,
                    commissioner, "at10-booking");
            helper.assertTrue(sentence != null && !sentence.cellId.isEmpty(),
                    "booking must land a cell");
            var booked = runtime.context().prisonerRegister().read().prisoner(uuid);
            helper.assertTrue(booked != null && booked.status == PrisonerStatus.IN_CELL,
                    "the prisoner must be booked IN_CELL");
            helper.assertTrue(booked.personalLocker.stream()
                            .anyMatch(p -> lockerPoint.key().equals(p.key())),
                    "the seizure must claim the pool chest as the personal locker");
            var lockerChest = (net.minecraft.world.Container)
                    level.getBlockEntity(lockerPos);
            int banked = 0;
            for (int i = 0; i < lockerChest.getContainerSize(); i++) {
                var st = lockerChest.getItem(i);
                if (st.is(Items.GOLD_NUGGET)) banked += st.getCount() * 64;
                if (st.is(Items.IRON_NUGGET)) banked += st.getCount();
            }
            helper.assertTrue(banked == 80,
                    "the seized coins must sit in the locker chest — got " + banked);
            helper.assertTrue(prisoner.getInventory().countItem(Items.GOLD_NUGGET) == 0
                            && prisoner.getInventory().countItem(Items.IRON_NUGGET) == 0,
                    "the seizure must empty the prisoner's pockets");
            int booksAfterArrest = prisoner.getInventory().countItem(Items.WRITTEN_BOOK);

            // A non-commissioner jailer release runs the debt gate: the levy
            // drains the locker oldest-first, then the over-threshold balance
            // diverts the release into the default camp.
            var jailerGw = qualifyOfficer(runtime, jailer);
            var prisonerGw = gateway(prisoner);
            helper.assertTrue(!runtime.prison().release(jailerGw, prisonerGw, "at10"),
                    "a debtor over the threshold must not leave custody");

            var afterGate = runtime.context().fines().read();
            var oldAfter = afterGate.find("at10-fine-old");
            var newAfter = afterGate.find("at10-fine-new");
            helper.assertTrue(oldAfter != null && oldAfter.paidAmount == 80
                            && oldAfter.remaining() == 20,
                    "the levy must fill the oldest fine first — paid="
                            + (oldAfter == null ? "null" : oldAfter.paidAmount));
            helper.assertTrue(oldAfter.contributions != null
                            && oldAfter.contributions.stream()
                            .anyMatch(c -> "LEVY".equals(c.source) && c.amount == 80),
                    "the levy application must be recorded as a LEVY contribution");
            helper.assertTrue(newAfter != null && newAfter.paidAmount == 0,
                    "the newer fine must wait its turn — paid="
                            + (newAfter == null ? "null" : newAfter.paidAmount));
            int leftInLocker = 0;
            for (int i = 0; i < lockerChest.getContainerSize(); i++) {
                var st = lockerChest.getItem(i);
                if (st.is(Items.GOLD_NUGGET) || st.is(Items.IRON_NUGGET)) {
                    leftInLocker += st.getCount();
                }
            }
            helper.assertTrue(leftInLocker == 0,
                    "the levy must physically drain the locker coins — left "
                            + leftInLocker);
            var diverted = runtime.context().prisonerRegister().read().prisoner(uuid);
            helper.assertTrue(diverted != null
                            && diverted.status == PrisonerStatus.IN_CAMP
                            && "at10_camp".equals(diverted.assignedCampId),
                    "onBlocked=CAMP must divert the debtor to the default camp — got "
                            + (diverted == null ? "no record"
                            : diverted.status + "/" + diverted.assignedCampId));
            var stillServing = runtime.context().prison().read().sentences.stream()
                    .filter(s -> uuid.equals(s.targetUuid)).findFirst().orElseThrow();
            helper.assertTrue("ACTIVE".equals(stillServing.status),
                    "a debt-diverted sentence must stay ACTIVE — got "
                            + stillServing.status);
            helper.assertTrue(prisoner.getInventory().countItem(Items.WRITTEN_BOOK)
                            >= booksAfterArrest + 2,
                    "the debtor must receive the levy receipt and the transfer order");
            var auditRows = runtime.audit().tail(80).stream()
                    .filter(e -> uuid.equals(e.targetUuid)).toList();
            helper.assertTrue(auditRows.stream().anyMatch(e -> "levy".equals(e.action)
                            && e.details.contains("extracted=80")),
                    "the levy must be audited");
            helper.assertTrue(auditRows.stream().anyMatch(e -> "debt_apply".equals(e.action)
                            && e.details.contains("source=LEVY")),
                    "the levy allocation must be audited");
            helper.assertTrue(auditRows.stream().anyMatch(e -> "prison_release".equals(e.action)
                            && "REFUSED".equals(e.result)
                            && e.details.contains("debt_gate camp=at10_camp")),
                    "the gate refusal must name the divert camp");

            // Third-party debt pay: a civilian covers the 70 remaining.
            var payer = new VirtualPlayerGateway("at10_payer");
            payer.giveVerified(ItemSpec.of("minecraft:gold_nugget", 1));
            payer.giveVerified(ItemSpec.of("minecraft:iron_nugget", 20));
            var applied = runtime.debt().payContribution(payer, uuid, null,
                    "CONTRIBUTION", null);
            int paidTotal = applied.stream().mapToInt(a -> a.applied()).sum();
            helper.assertTrue(paidTotal == 70,
                    "the contribution must settle the remaining 70 — got " + paidTotal);
            var settled = runtime.context().fines().read();
            var oldSettled = settled.find("at10-fine-old");
            var newSettled = settled.find("at10-fine-new");
            helper.assertTrue(oldSettled != null && "PAID".equals(oldSettled.status)
                            && oldSettled.paidAt != null,
                    "the old fine must close PAID after the contribution");
            helper.assertTrue(newSettled != null && "PAID".equals(newSettled.status)
                            && newSettled.paidAt != null,
                    "the new fine must close PAID after the contribution");
            helper.assertTrue(oldSettled.contributions != null
                            && oldSettled.contributions.stream()
                            .anyMatch(c -> "CONTRIBUTION".equals(c.source)
                                    && "at10_payer".equals(c.payer) && c.amount == 20),
                    "the contribution trail must name the payer");
            helper.assertTrue(runtime.debt().outstandingDebt(uuid) == 0,
                    "settled debt must read zero");

            // Debt cleared — the same jailer release now completes.
            helper.assertTrue(runtime.prison().release(jailerGw, prisonerGw, "at10-out"),
                    "the gate must pass once the debt is settled");
            var freed = runtime.context().prisonerRegister().read().prisoner(uuid);
            helper.assertTrue(freed != null && freed.status.isReleased(),
                    "the settled debtor must be released — got "
                            + (freed == null ? "no record" : freed.status));
            var closed = runtime.context().prison().read().sentences.stream()
                    .filter(s -> uuid.equals(s.targetUuid)).findFirst().orElseThrow();
            helper.assertTrue("FORCED_RELEASE".equals(closed.status),
                    "the completed release must close the sentence — got "
                            + closed.status);
            helper.succeed();
        } finally {
            cleanup.run();
        }
    }

    /**
     * AT11 (#242): the tester protocol walks a mock tester cover-to-finish —
     * scripted beats land (fine, Inspector rank, suspect spawn + kit, suspect
     * fine, surrender flag), the fake suspect really joins the player list,
     * and sealing the dossier dismisses it and purges its records.
     * Own batch: it grants rank and joins a fake player — shared-batch
     * isolation like debtAt10.
     */
    @GameTest(template = "empty", batch = "protocolAt11", timeoutTicks = 200)
    public static void protocolWalkthrough(GameTestHelper helper) {
        var runtime = runtime(helper);
        var policies = runtime.context().policies();
        boolean wasEnabled = policies.protocolEnabled;
        policies.protocolEnabled = true;
        try {
            purgeStaleFixtures(runtime, helper);
            ServerPlayer tester = mockPlayer(helper);
            var gw = gateway(tester);

            runtime.protocol().start(gw);
            helper.assertTrue(countWrittenBooks(tester) == 1,
                    "start must hand the dossier cover — got " + countWrittenBooks(tester));

            runtime.protocol().next(gw); // ch1 orientarea
            runtime.protocol().next(gw); // ch2 checkpoint
            runtime.protocol().next(gw); // ch3 — scripted fine on the tester
            var testerFines = runtime.context().fines().read().fines.stream()
                    .filter(f -> tester.getUUID().toString().equals(f.targetUuid))
                    .toList();
            helper.assertTrue(testerFines.size() == 1,
                    "chapter 3 must script a fine on the tester");

            runtime.protocol().next(gw); // ch4 — Inspector rank + duty
            var state = runtime.players().state(gw);
            helper.assertTrue(state.rank == com.dwurdy.straja.domain.model.Rank.INSPECTOR.level()
                            && state.duty,
                    "chapter 4 must grant Inspector rank and duty");

            runtime.protocol().next(gw); // ch5 — suspect spawn + restraint kit
            helper.assertTrue(countItem(tester, "straja:rope") >= 1
                            && countItem(tester, "straja:cuffs") >= 1,
                    "chapter 5 must hand the restraint kit");

            var storeEntry = repoEntry(runtime, tester);
            helper.assertTrue(storeEntry != null && !storeEntry.actorUuid.isEmpty(),
                    "chapter 5 must record the spawned suspect");
            java.util.UUID actorUuid = java.util.UUID.fromString(storeEntry.actorUuid);
            helper.assertTrue(
                    helper.getLevel().getServer().getPlayerList().getPlayer(actorUuid) != null,
                    "the suspect must be a joined player, not a detached fake");

            runtime.protocol().next(gw); // ch6 — scripted fine on the suspect
            helper.assertTrue(runtime.context().fines().read().fines.stream()
                            .anyMatch(f -> actorUuid.toString().equals(f.targetUuid)),
                    "chapter 6 must fine the suspect");

            runtime.protocol().next(gw); // ch7 — surrender flag for the rope
            helper.assertTrue(runtime.context().bounties().read().surrenders
                            .containsKey(actorUuid.toString()),
                    "chapter 7 must flag the suspect as surrendered");

            runtime.protocol().next(gw); // ch8 comisia
            runtime.protocol().next(gw); // ch9 raportul
            runtime.protocol().next(gw); // seal

            var sealed = repoEntry(runtime, tester);
            helper.assertTrue(sealed != null && sealed.finished,
                    "the dossier must seal after the last chapter");
            helper.assertTrue(
                    helper.getLevel().getServer().getPlayerList().getPlayer(actorUuid) == null,
                    "sealing must dismiss the suspect");
            helper.assertTrue(runtime.context().fines().read().fines.stream()
                            .noneMatch(f -> actorUuid.toString().equals(f.targetUuid)),
                    "sealing must purge the suspect's fines");

            helper.succeed();
        } finally {
            policies.protocolEnabled = wasEnabled;
            // belt-and-braces: drop a leftover suspect if an assert failed mid-run
            purgeStaleFixtures(runtime, helper);
        }
    }

    /** Reads the persisted protocol store straight off SavedData (the repo is
     *  not part of StrajaContext — it is injected into the service directly). */
    private static com.dwurdy.straja.domain.model.ProtocolStore.Entry repoEntry(
            StrajaRuntime runtime, ServerPlayer tester) {
        var store = new com.dwurdy.straja.adapter.out.persistence.SavedStores.Protocol(
                name -> new com.dwurdy.straja.adapter.out.persistence.NbtStore(
                        com.dwurdy.straja.adapter.out.persistence.StrajaDataProvider.get(
                                runtime.server(), name)));
        return store.read().find(tester.getUUID().toString());
    }

    private static int countWrittenBooks(ServerPlayer player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(Items.WRITTEN_BOOK)) {
                count += player.getInventory().getItem(i).getCount();
            }
        }
        return count;
    }

    private static int countItem(ServerPlayer player, String itemId) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && itemId.equals(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(stack.getItem()).toString())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Officer qualification: rank 3 + duty, the same pattern the rp suite uses. */
    private static MinecraftPlayerGateway qualifyOfficer(StrajaRuntime runtime,
                                                         ServerPlayer player) {
        var gateway = gateway(player);
        var state = runtime.players().state(gateway);
        state.rank = 3;
        state.invited = true;
        state.duty = true;
        state.fired = false;
        state.suspended = false;
        state.resigned = false;
        runtime.players().save(player.getUUID(), state);
        return gateway;
    }
}
