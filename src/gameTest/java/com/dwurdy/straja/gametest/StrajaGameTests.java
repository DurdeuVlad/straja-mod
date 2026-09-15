package com.dwurdy.straja.gametest;

import com.dwurdy.straja.adapter.in.event.StrajaEvents;
import com.dwurdy.straja.adapter.in.npc.StrajaNpcEntity;
import com.dwurdy.straja.adapter.in.test.VirtualPlayerGateway;
import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.bootstrap.StrajaItems;
import com.dwurdy.straja.bootstrap.StrajaMenus;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.Rank;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Blocking GameTests run by {@code runGameTestServer} on a real NeoForge
 * dedicated server. They exercise the live composition root
 * ({@link StrajaRuntime}) and the registered {@link StrajaEvents} listeners —
 * not mocks of them — so a regression at the event boundary fails the build.
 *
 * <p>All tests share one generated air-only structure ({@code straja:empty});
 * none of them assert on terrain, so geometry stays code-defined.
 */
@GameTestHolder("straja")
@PrefixGameTestTemplate(false)
public final class StrajaGameTests {
    private StrajaGameTests() {}

    private static StrajaRuntime runtime(GameTestHelper helper) {
        var runtime = StrajaRuntime.get();
        helper.assertTrue(runtime != null, "StrajaRuntime is not initialised on the game-test server");
        return runtime;
    }

    private static ResourceLocation straja(String path) {
        return ResourceLocation.fromNamespaceAndPath("straja", path);
    }

    private static MinecraftPlayerGateway gateway(net.minecraft.server.level.ServerPlayer player) {
        return new MinecraftPlayerGateway(player.getServer(), player.getUUID());
    }

    /**
     * {@code makeMockPlayer} only produces a detached {@code Player}; the
     * event paths under test need a {@code ServerPlayer} registered in the
     * server player list, which this deprecated helper still provides. Single
     * call site keeps the future migration contained.
     */
    @SuppressWarnings("removal")
    private static net.minecraft.server.level.ServerPlayer mockPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    /** Deferred registries actually populated on a booted server. */
    @GameTest(template = "empty")
    public static void registrationsPresent(GameTestHelper helper) {
        runtime(helper);
        for (String id : List.of("order_book", "mission_carnet", "archive_folder",
                "archive_document", "carbon_paper", "archive_stamp", "official_envelope",
                "room_marker", "prison_marker", "npc_wand", "patrol_wand", "survey_rod",
                "npc_cloner", "cuffs", "cuff_key", "bolt_cutters", "crowbar", "rope",
                "head_sack", "baton", "keychain", "fine_book", "fine_notice",
                "training_manual")) {
            helper.assertTrue(BuiltInRegistries.ITEM.get(straja(id)) != Items.AIR,
                    "missing item registration straja:" + id);
        }
        helper.assertTrue(BuiltInRegistries.ENTITY_TYPE.get(straja("straja_npc"))
                        == StrajaNpcEntity.NPC.get(),
                "missing entity registration straja:straja_npc");
        helper.assertTrue(BuiltInRegistries.MENU.get(straja("form")) == StrajaMenus.FORM.get(),
                "missing menu registration straja:form");
        helper.succeed();
    }

    /** Spawn, registry adoption, reload and fail-closed unknown roles. */
    @GameTest(template = "empty")
    public static void npcRegistryLifecycle(GameTestHelper helper) {
        var runtime = runtime(helper);
        ServerLevel level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));

        // A known-role NPC self-registers through the join hook on spawn.
        var npc = StrajaNpcEntity.spawn(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                "receptionist", "skin_a", "Receptie");
        helper.assertTrue(npc.isAlive() && level.getEntity(npc.getUUID()) == npc,
                "spawned NPC must be tracked by the level");
        var registration = runtime.npcRegistry().registration(npc.getStringUUID());
        helper.assertTrue(registration != null && "receptionist".equals(registration.role()),
                "the join hook must adopt a spawned NPC into the registry");

        // Re-adoption is idempotent and never overwrites the persisted role.
        runtime.npcRegistry().adopt(npc.getStringUUID(), "jailer");
        registration = runtime.npcRegistry().registration(npc.getStringUUID());
        helper.assertTrue(registration != null && "receptionist".equals(registration.role()),
                "adopt must be idempotent for an already-registered entity");

        // Unknown roles fail closed: nothing reaches the registry.
        var bogus = StrajaNpcEntity.spawn(level, pos.getX() + 2.5, pos.getY(), pos.getZ() + 0.5,
                "not_a_role", null, null);
        helper.assertTrue(runtime.npcRegistry().registration(bogus.getStringUUID()) == null,
                "an unknown role must not be registered");

        // Role and skin survive an NBT round trip — the reload path.
        var tag = npc.saveWithoutId(new CompoundTag());
        var reloaded = StrajaNpcEntity.NPC.get().create(level);
        reloaded.load(tag);
        helper.assertTrue("receptionist".equals(reloaded.getRoleId()),
                "a reloaded NPC must keep its role");
        helper.assertTrue("skin_a".equals(reloaded.getSkin()),
                "a reloaded NPC must keep its skin");
        helper.succeed();
    }

    /** Damage-boundary semantics: deny unauthorized, cap non-lethal, down on lethal, pass vanilla hits. */
    @GameTest(template = "empty")
    public static void batonDamageRouting(GameTestHelper helper) {
        var runtime = runtime(helper);
        var attacker = mockPlayer(helper);
        var target = mockPlayer(helper);

        // An unauthorized holder never reaches the damage pipeline.
        attacker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(StrajaItems.BATON.get()));
        var denied = new LivingIncomingDamageEvent(target,
                new DamageContainer(target.damageSources().playerAttack(attacker), 4.0f));
        NeoForge.EVENT_BUS.post(denied);
        helper.assertTrue(denied.isCanceled(),
                "a baton strike by a non-guard must be canceled");

        // An enforcement-capable guard's non-lethal strike is capped so a
        // baton alone can never drop the target below 1 health.
        var attackerState = runtime.players().state(attacker.getUUID());
        attackerState.rank = Rank.GUARD.level();
        runtime.players().save(attacker.getUUID(), attackerState);
        var capped = new LivingIncomingDamageEvent(target,
                new DamageContainer(target.damageSources().playerAttack(attacker), 4.0f));
        NeoForge.EVENT_BUS.post(capped);
        helper.assertFalse(capped.isCanceled(), "a guard's non-lethal baton strike must pass through");
        helper.assertTrue(capped.getAmount()
                        == (float) runtime.custodyRoleplay().capBatonDamage(
                                target.getHealth(), target.getAbsorptionAmount()),
                "baton damage must be rewritten to the non-lethal cap");

        // A lethal baton strike becomes a knockout, not a death.
        target.setHealth(2.0f);
        var lethal = new LivingIncomingDamageEvent(target,
                new DamageContainer(target.damageSources().playerAttack(attacker), 4.0f));
        NeoForge.EVENT_BUS.post(lethal);
        helper.assertTrue(lethal.isCanceled(), "a lethal baton strike must be canceled");
        helper.assertTrue(runtime.custodyRoleplay().isDowned(gateway(target)),
                "a lethal baton strike must down the target");
        helper.assertTrue(target.getHealth() == 1.0f,
                "a downed target must be left at 1 health");

        // An ordinary unarmed hit is untouched by the custody pipeline.
        var bystander = mockPlayer(helper);
        attacker.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var plain = new LivingIncomingDamageEvent(bystander,
                new DamageContainer(bystander.damageSources().playerAttack(attacker), 4.0f));
        NeoForge.EVENT_BUS.post(plain);
        helper.assertFalse(plain.isCanceled(), "ordinary non-lethal damage must reach vanilla");
        helper.assertTrue(plain.getAmount() == 4.0f, "ordinary damage must not be modified");
        helper.succeed();
    }

    /** The per-hand interact packets must dispatch once, and the trailing
     *  item-use packet must not fire the air gesture inside the dedup window. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void interactPacketDedup(GameTestHelper helper) {
        var runtime = runtime(helper);
        ServerLevel level = helper.getLevel();
        var player = mockPlayer(helper);
        // The cloner only fires for a tool holder — an op covers the check.
        player.getServer().getPlayerList().op(player.getGameProfile());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(StrajaItems.NPC_CLONER.get()));
        var gateway = gateway(player);
        String dimension = level.dimension().location().toString();
        var pos = helper.absolutePos(new BlockPos(2, 1, 2));
        var npc = StrajaNpcEntity.spawn(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                "archivist", null, "Arhivista");

        // The offhand follow-up packet is claimed but must not dispatch twice.
        var offhand = new PlayerInteractEvent.EntityInteract(player, InteractionHand.OFF_HAND, npc);
        NeoForge.EVENT_BUS.post(offhand);
        helper.assertTrue(offhand.isCanceled(), "the offhand interact packet must be claimed");
        helper.assertTrue(runtime.adminTools()
                        .cloneSpawnAt(gateway, dimension, pos.getX(), pos.getY(), pos.getZ()) == null,
                "the offhand packet must not capture a template");

        // The main-hand packet performs the capture, exactly once.
        var mainHand = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, npc);
        NeoForge.EVENT_BUS.post(mainHand);
        helper.assertTrue(mainHand.isCanceled(), "the main-hand interact packet must be claimed");
        helper.assertTrue(runtime.adminTools()
                        .cloneSpawnAt(gateway, dimension, pos.getX(), pos.getY(), pos.getZ()) != null,
                "the main-hand packet must capture the NPC template");

        // The item-use packet trailing the interact is suppressed inside the
        // dedup window, so it cannot fire the sneak-clear air gesture.
        player.setShiftKeyDown(true);
        var trailing = new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
        NeoForge.EVENT_BUS.post(trailing);
        helper.assertFalse(trailing.isCanceled(),
                "the trailing item-use packet must be suppressed");
        helper.assertTrue(runtime.adminTools()
                        .cloneSpawnAt(gateway, dimension, pos.getX(), pos.getY(), pos.getZ()) != null,
                "the suppressed packet must not clear the captured template");

        // Outside the window the same gesture performs the clear.
        helper.runAfterDelay(3, () -> {
            var late = new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
            NeoForge.EVENT_BUS.post(late);
            helper.assertTrue(late.isCanceled(),
                    "a sneak use outside the dedup window must clear the template");
            helper.assertTrue(runtime.adminTools()
                            .cloneSpawnAt(gateway, dimension, pos.getX(), pos.getY(), pos.getZ()) == null,
                    "the captured template must be cleared");
            helper.succeed();
        });
    }

    /** A confirmed cell protects its blocks from non-admin players only. */
    @GameTest(template = "empty")
    public static void cellProtection(GameTestHelper helper) {
        var runtime = runtime(helper);
        ServerLevel level = helper.getLevel();
        String dimension = level.dimension().location().toString();

        // A cell needs a sealed shell: the selected interior plus a one-block
        // solid surround with exactly one two-block door on the boundary.
        for (int x = 0; x <= 4; x++) {
            for (int y = 0; y <= 4; y++) {
                for (int z = 0; z <= 4; z++) {
                    boolean shell = x == 0 || x == 4 || y == 0 || y == 4 || z == 0 || z == 4;
                    if (!shell || (z == 0 && x == 2 && (y == 1 || y == 2))) continue;
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        helper.setBlock(new BlockPos(2, 1, 0), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(new BlockPos(2, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));

        var commissioner = new VirtualPlayerGateway("dwurdy");
        commissioner.setOp(true);
        commissioner.setDimension(dimension);
        var cornerA = helper.absolutePos(new BlockPos(1, 1, 1));
        var cornerB = helper.absolutePos(new BlockPos(3, 3, 3));
        helper.assertFalse(runtime.adminTools().cellClick(commissioner, dimension,
                        cornerA.getX(), cornerA.getY(), cornerA.getZ()),
                "the first corner click only starts the selection");
        helper.assertTrue(runtime.adminTools().cellClick(commissioner, dimension,
                        cornerB.getX(), cornerB.getY(), cornerB.getZ()),
                "the second corner click must complete the selection");
        runtime.adminTools().cellConfirm(commissioner);

        var inside = helper.absolutePos(new BlockPos(2, 2, 2));
        var outside = helper.absolutePos(new BlockPos(7, 2, 7));
        helper.assertTrue(runtime.prisonRoleplay().insideCell(dimension,
                        inside.getX(), inside.getY(), inside.getZ()),
                "the confirmed selection must register a cell");

        var intruder = mockPlayer(helper);
        var breakInside = new BlockEvent.BreakEvent(level, inside,
                level.getBlockState(inside), intruder);
        NeoForge.EVENT_BUS.post(breakInside);
        helper.assertTrue(breakInside.isCanceled(),
                "blocks inside a cell must resist non-admin changes");

        var breakOutside = new BlockEvent.BreakEvent(level, outside,
                level.getBlockState(outside), intruder);
        NeoForge.EVENT_BUS.post(breakOutside);
        helper.assertFalse(breakOutside.isCanceled(),
                "blocks outside protected areas must stay mutable");
        helper.succeed();
    }

    /** Login/logout recovery is idempotent: re-running it must neither drop
     *  nor duplicate a restraint. */
    @GameTest(template = "empty")
    public static void recoveryIdempotent(GameTestHelper helper) {
        var runtime = runtime(helper);
        var commissioner = new VirtualPlayerGateway("dwurdy");
        commissioner.setOp(true);
        commissioner.giveVerified(ItemSpec.of("straja:rope", 1));
        commissioner.selectSlot(0);
        var victim = new VirtualPlayerGateway("victim");

        helper.assertTrue(runtime.custodyRoleplay().applyRope(commissioner, victim),
                "binding two online players must succeed");
        helper.assertTrue(runtime.custodyRoleplay().isBound(victim),
                "the victim must be bound after applyRope");

        // Re-applying hits the idempotent path instead of stacking a record.
        // The rope is consumed on success, so the issuer needs a fresh one.
        commissioner.clearLog();
        commissioner.giveVerified(ItemSpec.of("straja:rope", 1));
        commissioner.selectSlot(0);
        helper.assertTrue(runtime.custodyRoleplay().applyRope(commissioner, victim),
                "re-applying rope to a bound target stays a success");
        helper.assertTrue(commissioner.messageLog().stream().anyMatch(m -> m.contains("deja legat")),
                "re-applying rope must hit the already-bound path");

        // Recovery may legitimately apply or clear restraint effects — the
        // idempotency invariant is a steady state: cycles 2+ must reproduce
        // cycle 1's effect set exactly.
        runtime.custodyRoleplay().recoverOnLogout(victim);
        runtime.custodyRoleplay().recoverOnLogin(victim);
        helper.assertTrue(runtime.custodyRoleplay().isBound(victim),
                "the restraint must survive the first recovery cycle");
        var steadyState = victim.effects().keySet();
        for (int i = 2; i <= 3; i++) {
            runtime.custodyRoleplay().recoverOnLogout(victim);
            runtime.custodyRoleplay().recoverOnLogin(victim);
            final int pass = i;
            helper.assertTrue(runtime.custodyRoleplay().isBound(victim),
                    "the restraint must survive recovery pass " + pass);
            helper.assertTrue(victim.effects().keySet().equals(steadyState),
                    "recovery pass " + pass + " must not stack or drop effects");
        }
        helper.succeed();
    }

    /** Paper-item metadata is untrusted input: forged ids fail closed while
     *  well-formed ones reach the owning service. */
    @GameTest(template = "empty")
    public static void physicalItemRouting(GameTestHelper helper) {
        var runtime = runtime(helper);

        var forged = new VirtualPlayerGateway("forged");
        forged.giveVerified(new ItemSpec("straja:archive_document", 1,
                Map.of("ArchiveDocumentId", "../../etc"), null));
        forged.selectSlot(0);
        helper.assertTrue(StrajaEvents.usePhysicalItem(runtime, forged),
                "a registered paper item must be routed");
        helper.assertTrue(forged.messageLog().stream().anyMatch(m -> m.contains("referință validă")),
                "forged document metadata must fail closed");

        var scholar = new VirtualPlayerGateway("scholar");
        scholar.giveVerified(new ItemSpec("straja:archive_document", 1,
                Map.of("ArchiveDocumentId", "missing_doc"), null));
        scholar.selectSlot(0);
        helper.assertTrue(StrajaEvents.usePhysicalItem(runtime, scholar),
                "a well-formed document id must be routed");
        helper.assertTrue(scholar.messageLog().stream().anyMatch(m -> m.contains("Foaia nu există")),
                "the service must answer a well-formed unknown id");

        var civilian = new VirtualPlayerGateway("civilian");
        civilian.giveVerified(ItemSpec.of("minecraft:stone", 1));
        civilian.selectSlot(0);
        helper.assertFalse(StrajaEvents.usePhysicalItem(runtime, civilian),
                "non-paper items must not be claimed");
        helper.succeed();
    }
}
