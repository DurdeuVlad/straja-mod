package com.dwurdy.straja.gametest;

import com.dwurdy.straja.adapter.in.event.StrajaEvents;
import com.dwurdy.straja.adapter.in.npc.NpcRoles;
import com.dwurdy.straja.adapter.in.npc.StrajaNpcEntity;
import com.dwurdy.straja.adapter.in.test.VirtualPlayerGateway;
import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.adapter.out.minecraft.WhipItem;
import com.dwurdy.straja.adapter.out.persistence.StrajaDataProvider;
import com.dwurdy.straja.application.service.NpcAdminService;
import com.dwurdy.straja.bootstrap.StrajaItems;
import com.dwurdy.straja.bootstrap.StrajaMenus;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.domain.model.Cell;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.NpcRegistry;
import com.dwurdy.straja.domain.model.PrisonStore;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.SetupData;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
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
                "head_sack", "baton", "whip", "keychain", "fine_book", "fine_notice",
                "training_manual", "identity_card", "seal_stamp",
                "sealed_military_crate")) {
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

    /** The whip uses the same guarded, non-lethal custody pipeline as the baton. */
    @GameTest(template = "empty")
    public static void whipDamageRouting(GameTestHelper helper) {
        var runtime = runtime(helper);
        var attacker = mockPlayer(helper);
        var target = mockPlayer(helper);
        attacker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(StrajaItems.WHIP.get()));

        var denied = new LivingIncomingDamageEvent(target,
                new DamageContainer(target.damageSources().playerAttack(attacker), 4.0f));
        NeoForge.EVENT_BUS.post(denied);
        helper.assertTrue(denied.isCanceled(), "a whip strike by a non-guard must be canceled");

        var attackerState = runtime.players().state(attacker.getUUID());
        attackerState.rank = Rank.GUARD.level();
        runtime.players().save(attacker.getUUID(), attackerState);
        target.setHealth(10.0f);
        var capped = new LivingIncomingDamageEvent(target,
                new DamageContainer(target.damageSources().playerAttack(attacker), 4.0f));
        NeoForge.EVENT_BUS.post(capped);
        helper.assertFalse(capped.isCanceled(), "a guard's non-lethal whip strike must pass through");
        helper.assertTrue(capped.getAmount() == WhipItem.MAX_DAMAGE,
                "a whip strike must keep its low damage profile");
        helper.assertTrue(WhipItem.KNOCKBACK_STRENGTH > WhipItem.MAX_DAMAGE,
                "a whip must have more knockback than damage");
        helper.assertTrue(WhipItem.ATTACK_SPEED_BONUS > 0.0D,
                "a whip must provide faster follow-up attacks");
        var velocity = target.getDeltaMovement();
        helper.assertTrue(velocity.x * velocity.x + velocity.z * velocity.z > 0.1D,
                "a successful whip strike must apply visible knockback");

        target.setHealth(2.0f);
        var lethal = new LivingIncomingDamageEvent(target,
                new DamageContainer(target.damageSources().playerAttack(attacker), 4.0f));
        NeoForge.EVENT_BUS.post(lethal);
        helper.assertTrue(lethal.isCanceled(), "a lethal whip strike must be canceled");
        helper.assertTrue(runtime.custodyRoleplay().isDowned(gateway(target)),
                "a lethal whip strike must down the target");
        helper.assertTrue(target.getHealth() == 1.0f,
                "a whip knockout must leave the target at 1 health");
        runtime.custodyRoleplay().recoverAfterDeath(gateway(target));
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
        runtime.custodyRoleplay().recoverAfterDeath(gateway(target));
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

        // The GameTest world persists across runs — the confirmed cell must
        // not leak into the shared cell pool or it eventually hits maxCells.
        var prison = runtime.context().prison().read();
        prison.cells.removeIf(c -> c != null && dimension.equals(c.dimension)
                && inside.getX() >= c.minX && inside.getX() <= c.maxX
                && inside.getY() >= c.minY && inside.getY() <= c.maxY
                && inside.getZ() >= c.minZ && inside.getZ() <= c.maxZ);
        runtime.context().prison().write(prison);
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

    /** Archive papers enter canonical bookshelves but never Secretary copy. */
    @GameTest(template = "empty")
    public static void archivePapersAreBookshelfCompatibleWithoutCopyLeak(GameTestHelper helper) {
        runtime(helper);
        var folder = new ItemStack(StrajaItems.ARCHIVE_FOLDER.get());
        var document = new ItemStack(StrajaItems.ARCHIVE_DOCUMENT.get());
        helper.assertTrue(folder.is(ItemTags.BOOKSHELF_BOOKS),
                "archive folders must be accepted by the canonical bookshelf tag");
        helper.assertTrue(document.is(ItemTags.BOOKSHELF_BOOKS),
                "archive documents must be accepted by the canonical bookshelf tag");

        var player = mockPlayer(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, folder);
        helper.assertTrue(gateway(player).copyMainHandBook()
                        == com.dwurdy.straja.application.port.out.PlayerGateway.BookCopyResult.NOT_A_BOOK,
                "Secretary must not duplicate archive folders through the generic bookshelf tag");
        helper.assertTrue(player.getInventory().countItem(StrajaItems.ARCHIVE_FOLDER.get()) == 1,
                "rejecting the copy must leave exactly the original folder");
        helper.succeed();
    }

    /** A real server-issued buletin is routed back to its UUID-bound record. */
    @GameTest(template = "empty")
    public static void identityCardIssueAndPhysicalRead(GameTestHelper helper) {
        var runtime = runtime(helper);
        var issuer = mockPlayer(helper);
        var holder = mockPlayer(helper);
        issuer.getServer().getPlayerList().op(issuer.getGameProfile());

        helper.assertTrue(runtime.identityCards().issue(gateway(issuer), gateway(holder)),
                "an operator must be able to issue a buletin to an online player");
        holder.getInventory().selected = 0;
        helper.assertTrue(holder.getInventory().countItem(StrajaItems.IDENTITY_CARD.get()) == 1,
                "issuing a buletin must deliver exactly one physical card");
        helper.assertTrue(StrajaEvents.usePhysicalItem(runtime, gateway(holder)),
                "the registered buletin item must be claimed by the physical-item router");
        helper.assertTrue(holder.getInventory().getItem(0).getOrDefault(
                net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.empty()).getString().contains("Buletin"),
                "the delivered item must carry a readable buletin name");
        helper.succeed();
    }

    /**
     * #201: bare /straja, /straja help and /straja ajutor are the player
     * recovery surface — they must parse and execute at permission 0 — while
     * subtree help and admin commands stay behind the parse-time gate.
     */
    @GameTest(template = "empty")
    public static void strajaHelpRunsAtPermissionZero(GameTestHelper helper) {
        runtime(helper);
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        var playerSource = mockPlayer(helper).createCommandSourceStack();
        helper.assertTrue(!playerSource.hasPermission(1),
                "the mock player must sit at permission 0 for this test");

        for (String command : List.of("straja", "straja help", "straja ajutor")) {
            try {
                helper.assertTrue(dispatcher.execute(command, playerSource) == 1,
                        "'" + command + "' must print the player orientation at permission 0");
            } catch (CommandSyntaxException e) {
                helper.fail("'" + command + "' must parse at permission 0: " + e.getMessage());
            }
        }

        for (String adminOnly : List.of("straja backup", "straja status help")) {
            boolean refused = false;
            try {
                dispatcher.execute(adminOnly, playerSource);
            } catch (CommandSyntaxException e) {
                refused = true;
            }
            helper.assertTrue(refused,
                    "'" + adminOnly + "' must stay unparsed at permission 0");
        }

        var adminSource = helper.getLevel().getServer().createCommandSourceStack();
        try {
            helper.assertTrue(dispatcher.execute("straja help", adminSource) == 1,
                    "/straja help must keep the admin index at operator level");
            helper.assertTrue(dispatcher.execute("straja", adminSource) == 1,
                    "bare /straja must resolve to the admin index at operator level");
        } catch (CommandSyntaxException e) {
            helper.fail("admin help must keep working: " + e.getMessage());
        }
        helper.succeed();
    }

    /**
     * #204 spot check on a real server: a civilian actor trying the member-only
     * duty action must receive a structured refusal — translatable
     * {@code straja.refusal.format} carrying a reason key and a
     * {@code straja.remedy.*} next step — and stay off duty.
     */
    @GameTest(template = "empty")
    public static void refusalCarriesReasonAndRemedy(GameTestHelper helper) {
        var runtime = runtime(helper);
        List<Component> captured = new ArrayList<>();
        CommandSource capture = new CommandSource() {
            @Override public void sendSystemMessage(Component component) {
                captured.add(component);
            }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        var source = new CommandSourceStack(capture, Vec3.ZERO, Vec2.ZERO,
                helper.getLevel(), 4, "gametest", Component.literal("gametest"),
                helper.getLevel().getServer(), null);
        var civilian = new com.dwurdy.straja.adapter.in.command.ConsolePlayerGateway(source);

        runtime.guardDuty().startDuty(civilian);

        Component refusal = captured.stream()
                .filter(c -> c.getContents() instanceof TranslatableContents t
                        && "straja.refusal.format".equals(t.getKey()))
                .findFirst().orElse(null);
        helper.assertTrue(refusal != null,
                "a denied member-only action must answer with the refusal format, got: "
                        + captured);
        var args = ((TranslatableContents) refusal.getContents()).getArgs();
        helper.assertTrue(args.length >= 2
                        && args[0] instanceof Component reason
                        && reason.getContents() instanceof TranslatableContents reasonT
                        && reasonT.getKey().startsWith("straja.")
                        && args[1] instanceof Component remedy
                        && remedy.getContents() instanceof TranslatableContents remedyT
                        && remedyT.getKey().startsWith("straja.remedy."),
                "the refusal must carry a reason key and a straja.remedy.* next step");
        helper.assertTrue(!runtime.playerQueries().isOnDutyGuard(civilian),
                "a refused duty start must leave the civilian off duty");
        helper.succeed();
    }

    /** Captures every chat line a command sends, so output text is assertable. */
    private static CommandSourceStack capturingSource(GameTestHelper helper, List<String> sink) {
        var server = helper.getLevel().getServer();
        CommandSource capture = new CommandSource() {
            @Override public void sendSystemMessage(Component component) {
                sink.add(component.getString());
            }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        return new CommandSourceStack(capture, Vec3.ZERO, Vec2.ZERO, helper.getLevel(),
                4, "gametest", Component.literal("gametest"), server, null);
    }

    private static void run(GameTestHelper helper,
                            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher,
                            CommandSourceStack source, String command) {
        try {
            helper.assertTrue(dispatcher.execute(command, source) == 1,
                    "'" + command + "' must execute at operator level");
        } catch (CommandSyntaxException e) {
            helper.fail("'" + command + "' must parse: " + e.getMessage());
        }
    }

    private static void assertLine(GameTestHelper helper, List<String> lines, String fragment) {
        helper.assertTrue(lines.stream().anyMatch(line -> line.contains(fragment)),
                "expected a line containing '" + fragment + "', got: " + lines);
    }

    /** Writes an aggregate through the same JSON envelope JsonBackedStore reads. */
    private static void putStore(MinecraftServer server, String name, Object value) {
        StrajaDataProvider.edit(server, name).putString("json",
                new GsonBuilder().serializeNulls().create().toJson(value));
        StrajaDataProvider.save(server, name);
    }

    /** Reads one aggregate through the same JSON envelope JsonBackedStore uses. */
    private static <T> T readStore(MinecraftServer server, String name, Class<T> type,
                                   java.util.function.Supplier<T> fallback) {
        String raw = StrajaDataProvider.data(server, name).getString("json");
        if (raw == null || raw.isEmpty()) return fallback.get();
        return new GsonBuilder().serializeNulls().create().fromJson(raw, type);
    }

    /**
     * #205: /straja setup lists every install category and /straja setup
     * verify reports an honest ready verdict. The GameTest world persists
     * between runs and other tests share it, so seeding here is strictly
     * additive — existing locations, cells and registrations are preserved.
     */
    @GameTest(template = "empty")
    public static void strajaSetupAndVerifyTrackInstallState(GameTestHelper helper) {
        var runtime = runtime(helper);
        var server = helper.getLevel().getServer();
        var dispatcher = server.getCommands().getDispatcher();
        List<String> lines = new ArrayList<>();
        var admin = capturingSource(helper, lines);

        // Whatever the leftover world state, every category must render.
        run(helper, dispatcher, admin, "straja setup");
        assertLine(helper, lines, "Comisar:");
        assertLine(helper, lines, "Locații administrative:");
        assertLine(helper, lines, "Checkpoint-uri patrulare:");
        assertLine(helper, lines, "NPC-uri:");
        assertLine(helper, lines, "Celule de detenție:");
        assertLine(helper, lines, "Economie");
        assertLine(helper, lines, "Stații:");
        helper.assertTrue(lines.stream().anyMatch(line -> line.contains("Următorul pas:")
                        || line.contains("Configurare completă")),
                "the checklist must end with a next step or the complete marker, got: " + lines);
        lines.clear();
        run(helper, dispatcher, admin, "straja setup verify");
        assertLine(helper, lines, "Verificare post-instalare");
        helper.assertTrue(lines.stream().anyMatch(line -> line.contains("gata")),
                "verify must print a verdict, got: " + lines);
        lines.clear();

        // Seed a complete install additively through the persisted stores,
        // then confirm the checklist and smoke step agree it is ready.
        SetupData setup = readStore(server, "setup", SetupData.class, SetupData::defaults);
        for (String key : SetupData.LOCATION_KEYS) {
            setup.locations.putIfAbsent(key, new SetupData.Location());
        }
        if (setup.checkpoints.isEmpty()) {
            setup.checkpoints.add(SetupData.newCheckpoint("checkpoint_1"));
        }
        for (var point : setup.checkpoints) {
            point.x = 1d; point.y = 64d; point.z = 1d;
        }
        putStore(server, "setup", setup);
        PrisonStore prison = readStore(server, "prisons", PrisonStore.class, PrisonStore::new);
        if (prison.cells.isEmpty()) {
            var cell = new Cell();
            cell.id = "cell_gametest";
            prison.cells.add(cell);
            putStore(server, "prisons", prison);
        }
        for (String role : NpcAdminService.ROLE_ORDER) {
            runtime.npcRegistry().adopt("gametest-" + role, role);
        }

        run(helper, dispatcher, admin, "straja setup");
        assertLine(helper, lines, "Configurare completă");
        lines.clear();
        run(helper, dispatcher, admin, "straja setup verify");
        assertLine(helper, lines, "Checklist configurare: complet");
        assertLine(helper, lines, "Validare stații: OK");
        // The verdict must correlate with the live doctor report either way.
        assertLine(helper, lines, runtime.v2Consistency().check("consistency").isEmpty()
                ? "gata pentru jucători"
                : "NU este gata");
        helper.succeed();
    }

    /**
     * #203: the receptionist surface reorders around the player's state —
     * civilians lead with «Depune cererea», sworn members lead with duty
     * bookkeeping and admission sinks under «Mai multe…». Seeded through the
     * real players store so {@code readState} observes the transition.
     */
    @GameTest(template = "empty")
    public static void npcSurfaceReordersOnStateTransition(GameTestHelper helper) {
        var runtime = runtime(helper);
        var player = mockPlayer(helper);

        // Fresh player = civilian: admission leads, member bookkeeping sinks.
        var civilIds = NpcRoles.orderedActionIds(
                NpcRoles.RECEPTIONIST, player, helper.getLevel());
        helper.assertTrue("application-submit".equals(civilIds.get(0)),
                "civilian receptionist must lead with «Depune cererea», got: " + civilIds);
        int civilFaq = civilIds.indexOf("faq:root_receptionist");
        helper.assertTrue(civilFaq >= 0 && civilFaq < 3,
                "FAQ must be within the first three actions for civilians, index " + civilFaq);
        helper.assertTrue(civilIds.size() <= 6,
                "primary page exceeds the 6-action cap");
        var civilOverflow = NpcRoles.overflowActionIds(
                NpcRoles.RECEPTIONIST, player, helper.getLevel());
        helper.assertTrue(civilOverflow.contains("guard-status"),
                "member bookkeeping must wait under «Mai multe…» for civilians");

        // Same player sworn in: the surface reorders without losing actions.
        var state = runtime.players().state(player.getUUID());
        state.rank = Rank.STAGIAR.level();
        runtime.players().save(player.getUUID(), state);
        var memberIds = NpcRoles.orderedActionIds(
                NpcRoles.RECEPTIONIST, player, helper.getLevel());
        helper.assertTrue("guard-status".equals(memberIds.get(0)),
                "member receptionist must lead with «Stare Străjer», got: " + memberIds);
        helper.assertTrue(!memberIds.contains("application-submit"),
                "members must not see «Depune cererea» on the primary page");
        helper.assertTrue(NpcRoles.overflowActionIds(
                        NpcRoles.RECEPTIONIST, player, helper.getLevel())
                        .contains("application-submit"),
                "admission stays reachable under «Mai multe…»");

        // The overflow page navigates through the same tokenized dispatch.
        helper.assertTrue(
                NpcRoles.performAction("more:receptionist", player, helper.getLevel()),
                "the «Mai multe…» token must render the overflow page");
        helper.assertTrue(
                NpcRoles.performAction("main:receptionist", player, helper.getLevel()),
                "the «Înapoi» token must re-render the primary page");
        helper.succeed();
    }

    /**
     * LAW-002: live deep-scan proof on a real ServerPlayer — a shulker box and
     * a bundle are enumerated structurally, nested rows keep their slot path.
     */
    @GameTest(template = "empty")
    public static void checkpointDeepScanFindsNestedContraband(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var player = helper.makeMockServerPlayerInLevel();

        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(net.minecraft.core.component.DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents
                        .fromItems(List.of(new ItemStack(Items.TNT, 2))));
        player.getInventory().add(0, box);

        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS,
                new net.minecraft.world.item.component.BundleContents(
                        List.of(new ItemStack(Items.DIAMOND, 4))));
        player.getInventory().add(1, bundle);

        player.getInventory().offhand.set(0, new ItemStack(Items.TNT, 1));

        var scanned = new com.dwurdy.straja.adapter.out.minecraft.MinecraftDeepScanGateway(server)
                .scanInventory(player);
        var ids = scanned.stream().map(s -> s.slot + "=" + s.itemId).toList();

        helper.assertTrue(ids.stream().anyMatch(s -> s.equals("main:0=minecraft:shulker_box")),
                "outer shulker must appear as main:0 — got " + ids);
        helper.assertTrue(ids.stream().anyMatch(s -> s.equals("main:0>0=minecraft:tnt")),
                "nested TNT must appear under the shulker path — got " + ids);
        helper.assertTrue(ids.stream().anyMatch(s -> s.equals("main:1>0=minecraft:diamond")),
                "bundle contents must appear under the bundle path — got " + ids);
        helper.assertTrue(ids.stream().anyMatch(s -> s.equals("offhand:0=minecraft:tnt")),
                "offhand stack must appear — got " + ids);
        var nested = scanned.stream().filter(s -> s.slot.equals("main:0>0")).findFirst().orElse(null);
        helper.assertTrue(nested != null && nested.count == 2 && nested.componentsTag != null,
                "nested row must carry count + serialized components for the ledger");
        helper.succeed();
    }

    /**
     * LAW-002: a powered-open iron door is forced shut through the real
     * WorldGateway — the synchronous lockdown leg of checkpoint denial.
     */
    @GameTest(template = "empty")
    public static void checkpointDoorCloses(GameTestHelper helper) {
        var runtime = StrajaRuntime.get();
        var level = helper.getLevel();
        var lower = helper.absolutePos(new BlockPos(2, 1, 2));
        var upper = lower.above();
        var lowerState = Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.OPEN, true)
                .setValue(DoorBlock.POWERED, true)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        var upperState = lowerState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        level.setBlock(lower, lowerState, 3);
        level.setBlock(upper, upperState, 3);

        runtime.context().world().closeDoor(
                level.dimension().location().toString(), lower.getX(), lower.getY(), lower.getZ());

        helper.assertTrue(!level.getBlockState(lower).getValue(DoorBlock.OPEN),
                "lower half must close on checkpoint denial");
        helper.assertTrue(!level.getBlockState(lower).getValue(DoorBlock.POWERED),
                "powered state must clear so the door stays shut");
        helper.assertTrue(!level.getBlockState(upper).getValue(DoorBlock.OPEN),
                "upper half must close too");
        helper.succeed();
    }

    /**
     * LAW-005 / AT5: a real end-to-end desk sale — coal leaves the seller's
     * inventory, lands in the placed chest through the real container gateway,
     * pays physical coins, and appends one immutable ledger row.
     */
    @GameTest(template = "empty")
    public static void merchantDeskTrade(GameTestHelper helper) {
        var runtime = runtime(helper);
        var level = helper.getLevel();
        var dim = level.dimension().location().toString();

        // Vanilla stand-in coins — Ady's Decorations is not on the test server.
        var policies = runtime.context().policies();
        var originalCoins = new java.util.LinkedHashMap<>(policies.coinItemIds);
        policies.coinItemIds.clear();
        policies.coinItemIds.put(1, "minecraft:iron_nugget");
        policies.coinItemIds.put(64, "minecraft:gold_nugget");
        try {
            var chestPos = helper.absolutePos(new BlockPos(2, 1, 2));
            level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
            var deskPos = helper.absolutePos(new BlockPos(1, 1, 1));

            var desks = runtime.context().merchantDesks().read();
            var desk = new com.dwurdy.straja.domain.model.MerchantDeskRecord();
            desk.id = "gt_qm";
            desk.dimension = dim;
            desk.deskPos = new com.dwurdy.straja.domain.model.StoragePoint(
                    dim, deskPos.getX(), deskPos.getY(), deskPos.getZ());
            desk.chests.add(new com.dwurdy.straja.domain.model.StoragePoint(
                    dim, chestPos.getX(), chestPos.getY(), chestPos.getZ()));
            desk.sellTable.put("minecraft:coal", 2);
            desks.put(desk);
            runtime.context().merchantDesks().write(desks);

            var seller = new VirtualPlayerGateway("desk_seller");
            seller.moveTo(deskPos.getX(), deskPos.getY(), deskPos.getZ());
            seller.giveVerified(ItemSpec.of("minecraft:coal", 10));

            helper.assertTrue(runtime.desks().sell(seller, "gt_qm", null, 0),
                    "the sale must complete: " + seller.messageLog());

            var chest = (net.minecraft.world.Container) level.getBlockEntity(chestPos);
            helper.assertTrue(chest != null, "the placed chest must resolve");
            int coal = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                var s = chest.getItem(i);
                if (s.is(Items.COAL)) coal += s.getCount();
            }
            helper.assertTrue(coal == 10, "all 10 coal must land in the chest — got " + coal);
            helper.assertTrue(seller.inventory().countOf("minecraft:coal") == 0,
                    "sold coal must leave the seller inventory");
            helper.assertTrue(seller.inventory().countOf("minecraft:iron_nugget") == 20,
                    "20 base units pay as 20 iron nuggets");

            var trades = runtime.context().merchantDesks().read().trades();
            helper.assertTrue(trades.stream().anyMatch(e -> "gt_qm".equals(e.deskId)
                    && e.baseUnits == 20 && e.itemsSold.getOrDefault("minecraft:coal", 0) == 10),
                    "the ledger must hold one immutable row for the sale");
        } finally {
            policies.coinItemIds.clear();
            policies.coinItemIds.putAll(originalCoins);
            var cleanup = runtime.context().merchantDesks().read();
            cleanup.remove("gt_qm");
            cleanup.trades().removeIf(e -> "gt_qm".equals(e.deskId));
            runtime.context().merchantDesks().write(cleanup);
        }
        helper.succeed();
    }

    /**
     * LAW-006 live custody: a cell arrest transfers into camp custody at the
     * intake spawn, death respawns at the dormitory, and reaching the freedom
     * price releases the prisoner at the camp's release point.
     */
    @GameTest(template = "empty")
    public static void laborCampCustody(GameTestHelper helper) {
        var runtime = runtime(helper);
        ServerLevel level = helper.getLevel();
        String dim = level.dimension().location().toString();

        var commissioner = new VirtualPlayerGateway("dwurdy");
        commissioner.setOp(true);
        commissioner.setDimension(dim);

        var min = helper.absolutePos(new BlockPos(0, -60, 0));
        var max = helper.absolutePos(new BlockPos(10, 10, 10));
        helper.assertTrue(runtime.laborCamps().register(commissioner, "gt_mine", "Test Camp",
                min.getX() + "," + min.getY() + "," + min.getZ(),
                max.getX() + "," + max.getY() + "," + max.getZ()),
                "camp registration must succeed: " + commissioner.messageLog());
        var campStore = runtime.context().laborCamps().read();
        var camp = campStore.camp("gt_mine");
        var intake = helper.absolutePos(new BlockPos(3, 1, 3));
        var dorm = helper.absolutePos(new BlockPos(5, 1, 5));
        var release = helper.absolutePos(new BlockPos(12, 1, 12));
        camp.intakeSpawn = new com.dwurdy.straja.domain.model.StoragePoint(
                dim, intake.getX(), intake.getY(), intake.getZ());
        camp.dormitorySpawn = new com.dwurdy.straja.domain.model.StoragePoint(
                dim, dorm.getX(), dorm.getY(), dorm.getZ());
        camp.releaseSpawn = new com.dwurdy.straja.domain.model.StoragePoint(
                dim, release.getX(), release.getY(), release.getZ());
        camp.freedomFlatPrice = 64;
        campStore.put(camp);
        runtime.context().laborCamps().write(campStore);

        try {
            var prisoner = mockPlayer(helper);
            var pg = gateway(prisoner);
            prisoner.teleportTo(min.getX() + 2, min.getY() + 61, min.getZ() + 2);

            runtime.prison().arrest(pg, null, 1, commissioner, "gt-arrest");
            helper.assertTrue(runtime.prison().transferToCamp(commissioner, pg, "gt_mine"),
                    "the camp transfer must succeed");

            var rec = runtime.context().prisonerRegister().read()
                    .prisoner(prisoner.getUUID().toString());
            helper.assertTrue(rec != null
                            && rec.status == com.dwurdy.straja.domain.model.PrisonerStatus.IN_CAMP
                            && "gt_mine".equals(rec.assignedCampId),
                    "transfer must register IN_CAMP custody");
            helper.assertTrue(Math.abs(prisoner.getX() - (intake.getX() + 0.5)) < 0.01,
                    "transfer must deliver at the intake spawn");

            // Death inside the camp respawns at the dormitory, custody intact.
            runtime.prisonRoleplay().onRespawn(pg);
            helper.assertTrue(Math.abs(prisoner.getX() - (dorm.getX() + 0.5)) < 0.01,
                    "respawn must land on the dormitory spawn");
            rec = runtime.context().prisonerRegister().read()
                    .prisoner(prisoner.getUUID().toString());
            helper.assertTrue(rec.status == com.dwurdy.straja.domain.model.PrisonerStatus.IN_CAMP,
                    "respawn must keep camp custody");

            // Reaching the freedom price releases at the camp release point.
            var reg = runtime.context().prisonerRegister().read();
            rec = reg.prisoner(prisoner.getUUID().toString());
            rec.laborAccount = 64;
            runtime.context().prisonerRegister().write(reg);
            helper.assertTrue(runtime.prison().checkLaborRelease(prisoner.getUUID().toString()),
                    "the labor buy-out must release the prisoner");
            rec = runtime.context().prisonerRegister().read()
                    .prisoner(prisoner.getUUID().toString());
            helper.assertTrue(rec.status
                            == com.dwurdy.straja.domain.model.PrisonerStatus.SERVED_LABOR,
                    "freedom price must mark the record SERVED_LABOR");
            helper.assertTrue(Math.abs(prisoner.getX() - (release.getX() + 0.5)) < 0.01,
                    "release must land at the camp release point");
        } finally {
            var cleanup = runtime.context().laborCamps().read();
            cleanup.remove("gt_mine");
            runtime.context().laborCamps().write(cleanup);
        }
        helper.succeed();
    }

    /**
     * LAW-007: the authoritative wanted surface — a BOLO makes a player
     * wanted, a register FUGITIVE counts even with no BOLO, and arresting the
     * suspect resolves every mark and clears wanted state.
     */
    @GameTest(template = "empty")
    public static void wantedMarksLifecycle(GameTestHelper helper) {
        var runtime = runtime(helper);
        var prisoner = mockPlayer(helper);
        var pg = gateway(prisoner);

        var bs = runtime.context().bolos().read();
        var record = new com.dwurdy.straja.domain.model.BoloRecord();
        record.id = "b-gt-1";
        record.subjectUuid = prisoner.getUUID().toString();
        record.subjectName = prisoner.getGameProfile().getName();
        record.status = com.dwurdy.straja.domain.model.BoloStatus.ACTIVE;
        bs.records.add(record);
        runtime.context().bolos().write(bs);
        helper.assertTrue(runtime.wanted().isWanted(prisoner.getUUID()),
                "an ACTIVE bolo must make the player wanted");

        var commissioner = new VirtualPlayerGateway("dwurdy");
        commissioner.setOp(true);
        commissioner.setDimension(prisoner.level().dimension().location().toString());
        runtime.prison().arrest(pg, null, 1, commissioner, "gt-wanted");

        bs = runtime.context().bolos().read();
        var own = bs.records.stream()
                .filter(r -> "b-gt-1".equals(r.id)).findFirst().orElse(null);
        helper.assertTrue(own != null && own.status
                        == com.dwurdy.straja.domain.model.BoloStatus.RESOLVED,
                "arrest must resolve the bolo as RESOLVED");
        helper.assertTrue(!runtime.wanted().isWanted(prisoner.getUUID()),
                "an arrested suspect must no longer be wanted");

        var reg = runtime.context().prisonerRegister().read();
        var rec = reg.prisoner(prisoner.getUUID().toString());
        rec.status = com.dwurdy.straja.domain.model.PrisonerStatus.FUGITIVE;
        runtime.context().prisonerRegister().write(reg);
        helper.assertTrue(runtime.wanted().isWanted(prisoner.getUUID()),
                "a register fugitive is wanted even with no active bolo");
        rec.status = com.dwurdy.straja.domain.model.PrisonerStatus.RELEASED;
        runtime.context().prisonerRegister().write(reg);
        helper.assertTrue(!runtime.wanted().isWanted(prisoner.getUUID()),
                "release must clear wanted state");
        helper.succeed();
    }

    /**
     * #246 — the artifact registry is the shared source of truth for the
     * forgery milestones: licenses gate registration, serials allocate in
     * order, and the pending window is what separates "registered" from
     * "legal".
     */
    @GameTest(template = "empty")
    public static void artifactRegistryLicensesAndPendingWindow(GameTestHelper helper) {
        var runtime = runtime(helper);
        var admin = new VirtualPlayerGateway("gt_admin");
        admin.setOp(true);
        var inspector = new VirtualPlayerGateway("gt_inspector");
        var holder = new VirtualPlayerGateway("gt_holder");
        var registry = runtime.artifactRegistry();
        boolean previousEnabled = runtime.context().policies().artifactRegistryEnabled;
        runtime.context().policies().artifactRegistryEnabled = true;
        try {
        // Idempotent on persistent test worlds: the license may already exist
        // from an earlier run of this suite.
        if (!registry.isLicensed(inspector,
                com.dwurdy.straja.domain.model.ArtifactLicenseType.INSPECTOR)) {
            helper.assertTrue(registry.grantLicense(admin, inspector, "INSPECTOR"),
                    "an admin must grant the inspector license");
        }
        helper.assertTrue(registry.register(holder, holder, "minecraft:iron_sword") == null,
                "unlicensed registration must be refused");

        String serial = registry.register(inspector, holder, "minecraft:iron_sword");
        helper.assertTrue(serial != null && serial.startsWith("RC-"),
                "a licensed inspector must receive an allocated serial, got: " + serial);
        helper.assertTrue(!registry.isLegal(serial),
                "a freshly registered artifact sits pending for the maturation window");

        int previous = runtime.context().policies().artifactPendingHours;
        runtime.context().policies().artifactPendingHours = 0;
        try {
            String instant = registry.register(inspector, holder, "minecraft:bow");
            helper.assertTrue(registry.isLegal(instant),
                    "a zero maturation window must register straight to legal");
        } finally {
            runtime.context().policies().artifactPendingHours = previous;
        }
        } finally {
            runtime.context().policies().artifactRegistryEnabled = previousEnabled;
        }
        helper.succeed();
    }

    /**
     * #247 — the forging engine on a live server: the anvil plan splits
     * licensed/unlicensed strikers, the exemplar ingredient only matches
     * data-carrying documents, and an unlicensed forge writes a FORGED
     * shadow record that can never pass as a legal registration.
     */
    @GameTest(template = "empty")
    public static void forgeryEngineAnvilPlanAndShadowRecords(GameTestHelper helper) {
        var runtime = runtime(helper);
        var admin = new VirtualPlayerGateway("gt_f_admin");
        admin.setOp(true);
        var inspector = new VirtualPlayerGateway("gt_f_inspector");
        var forger = new VirtualPlayerGateway("gt_forger");
        var registry = runtime.artifactRegistry();
        var forgery = runtime.forgery();
        var policies = runtime.context().policies();
        boolean prevRegistry = policies.artifactRegistryEnabled;
        boolean prevForgery = policies.forgeryEnabled;
        var prevArms = new java.util.ArrayList<>(policies.artifactRegulatedItemIds);
        policies.artifactRegistryEnabled = true;
        policies.forgeryEnabled = true;
        if (!policies.artifactRegulatedItemIds.contains("minecraft:iron_sword")) {
            policies.artifactRegulatedItemIds.add("minecraft:iron_sword");
        }
        try {
            if (!registry.isLicensed(inspector,
                    com.dwurdy.straja.domain.model.ArtifactLicenseType.INSPECTOR)) {
                helper.assertTrue(registry.grantLicense(admin, inspector, "INSPECTOR"),
                        "an admin must grant the inspector license");
            }

            // The anvil plan: unregulated items and unlicensed-vs-licensed split.
            helper.assertTrue(
                    forgery.planAnvilStrike(forger, "minecraft:stick", false) == null,
                    "unregulated items are not a forging operation");
            helper.assertTrue(
                    forgery.planAnvilStrike(forger, "minecraft:iron_sword", true) == null,
                    "a marked item cannot be re-struck");
            var forgedPlan = forgery.planAnvilStrike(forger, "minecraft:iron_sword", false);
            helper.assertTrue(forgedPlan != null && !forgedPlan.authentic(),
                    "an unlicensed striker must get the forged plan");
            var authenticPlan = forgery.planAnvilStrike(inspector, "minecraft:iron_sword", false);
            helper.assertTrue(authenticPlan != null && authenticPlan.authentic(),
                    "a licensed inspector must get the authentic strike");

            // The exemplar ingredient: blank items never teach a serial's shape.
            var ingredient = new com.dwurdy.straja.adapter.in.crafting.ExemplarIngredient("card");
            ItemStack blank = new ItemStack(StrajaItems.IDENTITY_CARD.get());
            helper.assertTrue(!ingredient.test(blank),
                    "a blank card must never pass as an exemplar");
            ItemStack genuine = blank.copy();
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.putString("IdentityCardId", "ID-1");
            genuine.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
            helper.assertTrue(ingredient.test(genuine),
                    "an issued card must pass as an exemplar");

            // Unlicensed forge: shadow record, authentic-space isolation.
            var outcome = forgery.forge(forger, "minecraft:iron_sword");
            helper.assertTrue(outcome != null && outcome.shadowSerial().startsWith("FRG-"),
                    "a forge attempt must write an FRG shadow record");
            var shadow = registry.find(outcome.shadowSerial());
            helper.assertTrue(shadow != null && !shadow.legalAt(Long.MAX_VALUE),
                    "a forged shadow record can never be legal");
            helper.assertTrue(outcome.marking().startsWith("#"),
                    "forged items carry a physical mark, got: " + outcome.marking());

            // The three smithing recipes loaded — proves the JSONs parsed.
            var recipes = helper.getLevel().getRecipeManager().getRecipes();
            long forgeRecipes = recipes.stream().filter(r -> r.id().getNamespace().equals("straja")
                    && r.id().getPath().startsWith("forge_")).count();
            helper.assertTrue(forgeRecipes == 3,
                    "expected 3 forge recipes loaded, found: " + forgeRecipes);

            // Pending-marker resolution: a forged smithing result carries the
            // marker until the take-hook or first inventory tick resolves it.
            var forgerEntity = helper.makeMockServerPlayerInLevel();
            ItemStack pending = new ItemStack(StrajaItems.IDENTITY_CARD.get());
            var pendingTag = new net.minecraft.nbt.CompoundTag();
            pendingTag.putString(
                    com.dwurdy.straja.adapter.in.crafting.SmithingForgeRecipe.PENDING_KEY, "1");
            ItemStack exemplarStack = new ItemStack(StrajaItems.IDENTITY_CARD.get());
            var exTag = new net.minecraft.nbt.CompoundTag();
            exTag.putString("IdentityCardId", "ID-77");
            exemplarStack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(exTag));
            pendingTag.put(com.dwurdy.straja.adapter.in.crafting.SmithingForgeRecipe.EXEMPLAR_KEY,
                    exemplarStack.save(forgerEntity.registryAccess(), new net.minecraft.nbt.CompoundTag()));
            pending.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(pendingTag));
            com.dwurdy.straja.adapter.in.crafting.ForgeCrafting.resolveIfPending(
                    pending, forgerEntity);
            var resolved = pending.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            helper.assertTrue(resolved != null
                            && !resolved.copyTag().contains(
                                    com.dwurdy.straja.adapter.in.crafting.SmithingForgeRecipe.PENDING_KEY),
                    "the pending marker must clear on resolution");
            helper.assertTrue(resolved != null
                            && !resolved.copyTag().getString(
                                    com.dwurdy.straja.application.service.ForgeryService.MARK_KEY).isBlank(),
                    "a resolved forge must carry a physical mark");
            helper.assertTrue(forgerEntity.getInventory().countItem(
                    StrajaItems.IDENTITY_CARD.get()) >= 1,
                    "the exemplar snapshot must be refunded to the forger");
        } finally {
            policies.artifactRegistryEnabled = prevRegistry;
            policies.forgeryEnabled = prevForgery;
            policies.artifactRegulatedItemIds = prevArms;
        }
        helper.succeed();
    }
}
