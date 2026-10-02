package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.LaborCampRecord;
import com.dwurdy.straja.domain.model.LawCheckpointRecord;
import com.dwurdy.straja.domain.model.MerchantDeskRecord;
import com.dwurdy.straja.domain.model.PrisonerRegisterRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.StoragePoint;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LAW-005 / AT5 merchant desks: sequential chest fill, atomic sale refusal,
 * coin payout vs labor credit, ledger persistence, and the quartermaster
 * profile derived from a camp's exit-gate carry bans.
 */
class MerchantDeskServiceTest {
    private static final String DIM = "minecraft:overworld";
    private static final String COAL = "minecraft:coal";
    private static final String IRON = "minecraft:raw_iron";

    private TestServer server;
    private StrajaContext ctx;
    private MerchantDeskService desks;
    private TestContainers containers;
    private TestCurrency currency;
    private TestPlayer boss;
    private TestPlayer seller;

    @BeforeEach
    void setup() {
        server = new TestServer();
        ctx = Fakes.context(server, new FixedClock(1_000_000L));
        var players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        desks = new MerchantDeskService(ctx, players, audit);
        containers = (TestContainers) ctx.containers();
        currency = (TestCurrency) ctx.currency();
        boss = server.add("dwurdy"); // configured commissioner name
        seller = server.add("miner");
    }

    private MerchantDeskRecord deskAt(double x, double y, double z) {
        var store = ctx.merchantDesks().read();
        var desk = new MerchantDeskRecord();
        desk.id = "qm";
        desk.npcName = "Intendent";
        desk.dimension = DIM;
        desk.deskPos = new StoragePoint(DIM, (int) x, (int) y, (int) z);
        desk.sellTable.put(COAL, 2);
        desk.sellTable.put(IRON, 5);
        store.put(desk);
        ctx.merchantDesks().write(store);
        return desk;
    }

    private void atDesk(MerchantDeskRecord desk, TestPlayer p) {
        p.x = desk.deskPos.x();
        p.y = desk.deskPos.y();
        p.z = desk.deskPos.z();
    }

    private void chest(MerchantDeskRecord desk, int x, int z) {
        containers.placeContainer(DIM, x, 64, z);
        desk.chests.add(new StoragePoint(DIM, x, 64, z));
        var store = ctx.merchantDesks().read();
        store.put(desk);
        ctx.merchantDesks().write(store);
    }

    private int chestUnits(int x, int z, String itemId) {
        return containers.countUnits(DIM, x, 64, z, Map.of(itemId, 1));
    }

    // ------------------------------------------------------------ AT5 trades

    @Test
    void sellMovesGoodsIntoFirstChestThenPaysCoins() {
        var desk = deskAt(0, 64, 0);
        chest(desk, 10, 10);
        atDesk(desk, seller);
        seller.give(ItemSpec.of(COAL, 10));
        seller.give(ItemSpec.of(IRON, 3));

        int before = currency.balance;
        assertTrue(desks.sell(seller, "qm", null, 0));

        // All 10 coal + 3 iron landed in chest 1.
        assertEquals(10, chestUnits(10, 10, COAL));
        assertEquals(3, chestUnits(10, 10, IRON));
        assertEquals(0, seller.inventory.countOf(COAL));
        assertEquals(0, seller.inventory.countOf(IRON));
        // 10*2 + 3*5 = 35 base units paid once.
        assertEquals(before + 35, currency.balance);
        assertEquals(1, currency.depositCalls);
        var trades = ctx.merchantDesks().read().trades();
        assertEquals(1, trades.size());
        var entry = trades.get(0);
        assertEquals("qm", entry.deskId);
        assertEquals(seller.uuid().toString(), entry.sellerUuid);
        assertEquals(35, entry.baseUnits);
        assertFalse(entry.creditedToLabor);
        assertEquals(10, entry.itemsSold.get(COAL));
        assertEquals(3, entry.itemsSold.get(IRON));
    }

    @Test
    void sellOverflowsSequentiallyIntoTheNextChest() {
        var desk = deskAt(0, 64, 0);
        // Chest 1: one slot only — 64 coal max. Chest 2 catches the rest.
        containers.slotCount = 1;
        containers.placeContainer(DIM, 10, 64, 10);
        containers.slotCount = 27;
        containers.placeContainer(DIM, 20, 64, 20);
        desk.chests.add(new StoragePoint(DIM, 10, 64, 10));
        desk.chests.add(new StoragePoint(DIM, 20, 64, 20));
        var store = ctx.merchantDesks().read();
        store.put(desk);
        ctx.merchantDesks().write(store);
        atDesk(desk, seller);
        seller.give(ItemSpec.of(COAL, 70));

        assertTrue(desks.sell(seller, "qm", COAL, 0));
        assertEquals(64, chestUnits(10, 10, COAL));
        assertEquals(6, chestUnits(20, 20, COAL));
        assertEquals(0, seller.inventory.countOf(COAL));
    }

    @Test
    void fullChestsRefuseWithoutTouchingItemsOrMoney() {
        var desk = deskAt(0, 64, 0);
        chest(desk, 10, 10);
        containers.slotCount = 1;
        containers.slots.clear();
        containers.placeContainer(DIM, 10, 64, 10); // 1 empty slot → 64 max
        atDesk(desk, seller);
        seller.give(ItemSpec.of(COAL, 70)); // 70 > 64 capacity

        int before = currency.balance;
        assertFalse(desks.sell(seller, "qm", null, 0));
        assertEquals(70, seller.inventory.countOf(COAL)); // nothing taken
        assertEquals(before, currency.balance);           // nothing paid
        assertEquals(0, currency.depositCalls);
        assertTrue(ctx.merchantDesks().read().trades().isEmpty());
        assertTrue(seller.told("pline")); // "Cuferele biroului sunt pline"
    }

    @Test
    void sellsNothingWhenItemIsNotOnTheTable() {
        var desk = deskAt(0, 64, 0);
        chest(desk, 10, 10);
        atDesk(desk, seller);
        seller.give(ItemSpec.of("minecraft:diamond", 4));

        assertFalse(desks.sell(seller, "qm", null, 0));
        assertEquals(4, seller.inventory.countOf("minecraft:diamond"));
        assertEquals(0, currency.depositCalls);
        assertTrue(ctx.merchantDesks().read().trades().isEmpty());
    }

    @Test
    void sellerTooFarFromDeskIsRefused() {
        var desk = deskAt(0, 64, 0);
        chest(desk, 10, 10);
        seller.x = 500; seller.y = 64; seller.z = 500;
        seller.give(ItemSpec.of(COAL, 4));

        assertFalse(desks.sell(seller, "qm", null, 0));
        assertEquals(4, seller.inventory.countOf(COAL));
        assertEquals(0, currency.depositCalls);
    }

    @Test
    void depositFailureRestoresAllGoods() {
        var desk = deskAt(0, 64, 0);
        chest(desk, 10, 10);
        atDesk(desk, seller);
        seller.give(ItemSpec.of(COAL, 8));
        currency.returnPartialDeposit = true;

        assertFalse(desks.sell(seller, "qm", null, 0));
        assertEquals(8, seller.inventory.countOf(COAL)); // refunded
        assertEquals(0, chestUnits(10, 10, COAL));       // nothing delivered
        assertTrue(ctx.merchantDesks().read().trades().isEmpty());
    }

    @Test
    void campPrisonerSaleCreditsLaborAccountInsteadOfCoins() {
        var desk = deskAt(0, 64, 0);
        desk.creditsLaborAccount = true;
        var store = ctx.merchantDesks().read();
        store.put(desk);
        ctx.merchantDesks().write(store);
        chest(desk, 10, 10);
        atDesk(desk, seller);
        seller.give(ItemSpec.of(COAL, 5)); // 5 * 2 = 10 base units

        var reg = ctx.prisonerRegister().read();
        var rec = new PrisonerRegisterRecord();
        rec.detaineeUuid = seller.uuid().toString();
        rec.detaineeName = seller.name();
        rec.status = PrisonerStatus.IN_CAMP;
        rec.assignedCampId = "mine1";
        reg.put(rec);
        ctx.prisonerRegister().write(reg);

        int before = currency.balance;
        assertTrue(desks.sell(seller, "qm", null, 0));

        assertEquals(before, currency.balance); // no physical coins
        assertEquals(0, currency.depositCalls);
        var after = ctx.prisonerRegister().read().prisoner(seller.uuid().toString());
        assertEquals(10, after.laborAccount);
        var entry = ctx.merchantDesks().read().trades().get(0);
        assertTrue(entry.creditedToLabor);
        assertEquals(10, entry.baseUnits);
    }

    @Test
    void civilianAtLaborDeskStillGetsCoins() {
        var desk = deskAt(0, 64, 0);
        desk.creditsLaborAccount = true;
        var store = ctx.merchantDesks().read();
        store.put(desk);
        ctx.merchantDesks().write(store);
        chest(desk, 10, 10);
        atDesk(desk, seller);
        seller.give(ItemSpec.of(COAL, 5));

        assertTrue(desks.sell(seller, "qm", null, 0));
        assertEquals(1, currency.depositCalls); // civilian → physical payout
        assertFalse(ctx.merchantDesks().read().trades().get(0).creditedToLabor);
    }

    @Test
    void sellFilterLimitsToOneCommodity() {
        var desk = deskAt(0, 64, 0);
        chest(desk, 10, 10);
        atDesk(desk, seller);
        seller.give(ItemSpec.of(COAL, 4));
        seller.give(ItemSpec.of(IRON, 4));

        assertTrue(desks.sell(seller, "qm", IRON, 0));
        assertEquals(0, seller.inventory.countOf(IRON));
        assertEquals(4, seller.inventory.countOf(COAL)); // coal untouched
        var entry = ctx.merchantDesks().read().trades().get(0);
        assertEquals(20, entry.baseUnits); // 4 iron * 5
        assertFalse(entry.itemsSold.containsKey(COAL));
    }

    // ------------------------------------------------------------ admin

    @Test
    void createDeskRegistersAndDuplicateIsRefused() {
        assertTrue(desks.createDesk(boss, "shop1", "Intendent"));
        assertNotNull(ctx.merchantDesks().read().desk("shop1"));
        assertFalse(desks.createDesk(boss, "shop1", "Other"));
    }

    @Test
    void nonStaffCannotCreateDesks() {
        assertFalse(desks.createDesk(seller, "shop1", "Npc"));
        assertNull(ctx.merchantDesks().read().desk("shop1"));
    }

    @Test
    void setPriceAndZeroRemoves() {
        assertTrue(desks.createDesk(boss, "shop1", "Npc"));
        assertTrue(desks.setPrice(boss, "shop1", COAL, 3));
        assertEquals(3, ctx.merchantDesks().read().desk("shop1").priceOf(COAL));
        assertTrue(desks.setPrice(boss, "shop1", COAL, 0));
        assertNull(ctx.merchantDesks().read().desk("shop1").sellTable.get(COAL));
    }

    @Test
    void pickAddsChestInOrderAndRejectsNonContainers() {
        assertTrue(desks.createDesk(boss, "shop1", "Npc"));
        assertTrue(desks.setPickMode(boss, "shop1"));
        assertTrue(desks.onPickClick(boss, DIM, 5, 64, 5)); // not a container
        assertTrue(boss.told("container"));
        containers.placeContainer(DIM, 5, 64, 5);
        assertTrue(desks.onPickClick(boss, DIM, 5, 64, 5));
        assertEquals(1, ctx.merchantDesks().read().desk("shop1").chests.size());
        // A second click appends to the fill order.
        containers.placeContainer(DIM, 6, 64, 5);
        assertTrue(desks.onPickClick(boss, DIM, 6, 64, 5));
        var desk = ctx.merchantDesks().read().desk("shop1");
        assertEquals(2, desk.chests.size());
        assertEquals(6, desk.chests.get(1).x());
        // Duplicate click is rejected.
        assertTrue(desks.onPickClick(boss, DIM, 6, 64, 5));
        assertEquals(2, ctx.merchantDesks().read().desk("shop1").chests.size());
    }

    @Test
    void quartermasterProfileMirrorsExitGateBans() {
        // Camp mine1 exits through checkpoint "exitgate" which carry-bans
        // coal for miners and locally bans salt.
        var camps = ctx.laborCamps().read();
        var camp = new LaborCampRecord();
        camp.id = "mine1";
        camp.exitCheckpointId = "exitgate";
        camps.put(camp);
        ctx.laborCamps().write(camps);
        var sites = ctx.lawCheckpoints().read();
        var site = new LawCheckpointRecord();
        site.id = "exitgate";
        site.normalize();
        site.localIllegalItems.add("straja:salt");
        site.roleCarryBans.put("miner", List.of(COAL, IRON));
        sites.put(site);
        ctx.lawCheckpoints().write(sites);
        assertTrue(desks.createDesk(boss, "qm", "Intendent"));

        assertTrue(desks.setupQuartermaster(boss, "mine1", "qm", 4));

        var desk = ctx.merchantDesks().read().desk("qm");
        assertTrue(desk.creditsLaborAccount);
        assertEquals(4, desk.priceOf(COAL));
        assertEquals(4, desk.priceOf(IRON));
        assertEquals(4, desk.priceOf("straja:salt"));
        assertEquals("qm", ctx.laborCamps().read().camp("mine1").quartermasterDeskId);
    }

    @Test
    void ledgerQueryFiltersBySeller() {
        var desk = deskAt(0, 64, 0);
        chest(desk, 10, 10);
        atDesk(desk, seller);
        seller.give(ItemSpec.of(COAL, 2));
        var other = server.add("smith");
        atDesk(desk, other);
        other.give(ItemSpec.of(IRON, 1));
        desks.sell(seller, "qm", null, 0);
        desks.sell(other, "qm", null, 0);

        seller.messages.clear();
        desks.showLedger(seller, "qm", null);
        assertEquals(2, seller.messages.size());

        seller.messages.clear();
        desks.showLedger(seller, "qm", "smith");
        assertEquals(1, seller.messages.size());
    }

    @Test
    void npcNameAndUuidFormsAreAccepted() {
        assertTrue(desks.createDesk(boss, "named", "Intendent"));
        assertEquals("Intendent", ctx.merchantDesks().read().desk("named").npcName);
        String uuid = java.util.UUID.randomUUID().toString();
        assertTrue(desks.createDesk(boss, "uuidDesk", uuid));
        assertEquals(uuid, ctx.merchantDesks().read().desk("uuidDesk").npcUuid);
    }
}
