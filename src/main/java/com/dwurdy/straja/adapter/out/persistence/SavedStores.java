package com.dwurdy.straja.adapter.out.persistence;

import com.dwurdy.straja.application.port.out.*;
import com.dwurdy.straja.domain.model.*;
import java.util.ArrayList;
import java.util.List;

/** SavedData-backed repository implementations, one per aggregate store. */
public final class SavedStores {
    private SavedStores() {}

    public static class Setup extends JsonBackedStore implements SetupRepository {
        public Setup(StoreAccess access) { super(access, "setup"); }
        @Override public SetupData read() { return readJson(SetupData.class, SetupData::defaults); }
        @Override public void write(SetupData data) { writeJson(data); }
    }

    public static class Audit extends JsonBackedStore implements AuditRepository {
        private final int retentionLimit;

        public Audit(StoreAccess access, int retentionLimit) {
            super(access, "audit");
            this.retentionLimit = Math.max(1, retentionLimit);
        }

        @Override public void append(AuditEntry entry) {
            List<AuditEntry> entries = new ArrayList<>(entries());
            entries.add(entry);
            while (entries.size() > retentionLimit) entries.remove(0);
            writeJson(entries);
        }

        @Override @SuppressWarnings("unchecked")
        public List<AuditEntry> entries() {
            List<AuditEntry> list = readJson(List.class, ArrayList::new);
            // Gson deserializes raw list entries to LinkedTreeMap; re-parse for typing.
            return GSON.fromJson(GSON.toJsonTree(list),
                    new com.google.gson.reflect.TypeToken<List<AuditEntry>>() {}.getType());
        }

        @Override public List<AuditEntry> tail(int count) {
            List<AuditEntry> all = entries();
            return all.subList(Math.max(0, all.size() - Math.max(0, count)), all.size());
        }
    }

    public static class Inbox extends JsonBackedStore implements InboxRepository {
        public Inbox(StoreAccess access) { super(access, "inbox"); }
        @Override public List<InboxMessage> read() {
            List<InboxMessage> list = readJson(List.class, ArrayList::new);
            return GSON.fromJson(GSON.toJsonTree(list),
                    new com.google.gson.reflect.TypeToken<List<InboxMessage>>() {}.getType());
        }
        @Override public void write(List<InboxMessage> messages) { writeJson(messages); }
    }

    public static class Missions extends JsonBackedStore implements MissionRepository {
        public Missions(StoreAccess access) { super(access, "missions"); }
        @Override public MissionStore read() { return readJson(MissionStore.class, MissionStore::new); }
        @Override public void write(MissionStore store) { writeJson(store); }
    }

    public static class Fines extends JsonBackedStore implements FineRepository {
        public Fines(StoreAccess access) { super(access, "fines"); }
        @Override public FineStore read() { return readJson(FineStore.class, FineStore::new); }
        @Override public void write(FineStore store) { writeJson(store); }
    }

    public static class Prison extends JsonBackedStore implements PrisonRepository {
        public Prison(StoreAccess access) { super(access, "prisons"); }
        @Override public PrisonStore read() { return readJson(PrisonStore.class, PrisonStore::new); }
        @Override public void write(PrisonStore store) { writeJson(store); }
    }

    public static class Rooms extends JsonBackedStore implements RoomRepository {
        public Rooms(StoreAccess access) { super(access, "rooms"); }
        @Override public RoomStore read() { return readJson(RoomStore.class, RoomStore::new); }
        @Override public void write(RoomStore store) { writeJson(store); }
    }

    public static class Complaints extends JsonBackedStore implements ComplaintRepository {
        public Complaints(StoreAccess access) { super(access, "complaints"); }
        @Override public ComplaintStore read() { return readJson(ComplaintStore.class, ComplaintStore::new); }
        @Override public void write(ComplaintStore store) { writeJson(store); }
    }

    public static class MissionTemplates extends JsonBackedStore implements MissionTemplateRepository {
        public MissionTemplates(StoreAccess access) { super(access, "mission_templates"); }
        @Override public MissionTemplateStore read() { return readJson(MissionTemplateStore.class, MissionTemplateStore::new); }
        @Override public void write(MissionTemplateStore store) { writeJson(store); }
    }

    public static class Emergency extends JsonBackedStore implements EmergencyRepository {
        public Emergency(StoreAccess access) { super(access, "emergency"); }
        @Override public EmergencyState read() { return readJson(EmergencyState.class, EmergencyState::new); }
        @Override public void write(EmergencyState state) { writeJson(state); }
    }

    public static class Audiences extends JsonBackedStore implements AudienceRepository {
        public Audiences(StoreAccess access) { super(access, "audiences"); }
        @Override public AudienceStore read() { return readJson(AudienceStore.class, AudienceStore::new); }
        @Override public void write(AudienceStore store) { writeJson(store); }
    }

    public static class Reports extends JsonBackedStore implements ReportRepository {
        public Reports(StoreAccess access) { super(access, "reports"); }
        @Override public ActivityReportStore read() { return readJson(ActivityReportStore.class, ActivityReportStore::new); }
        @Override public void write(ActivityReportStore store) { writeJson(store); }
    }

    public static class Custody extends JsonBackedStore implements CustodyRepository {
        public Custody(StoreAccess access) { super(access, "custody"); }
        @Override public CustodyStore read() { return readJson(CustodyStore.class, CustodyStore::new); }
        @Override public void write(CustodyStore store) { writeJson(store); }
    }

    public static class Archive extends JsonBackedStore implements ArchiveRepository {
        public Archive(StoreAccess access) { super(access, "archive"); }
        @Override public ArchiveStore read() { return readJson(ArchiveStore.class, ArchiveStore::new); }
        @Override public void write(ArchiveStore store) { writeJson(store); }
    }

    public static class IdentityCards extends JsonBackedStore implements IdentityCardRepository {
        public IdentityCards(StoreAccess access) { super(access, "identity_cards"); }
        @Override public IdentityCardStore read() {
            return readJson(IdentityCardStore.class, IdentityCardStore::new);
        }
        @Override public void write(IdentityCardStore store) { writeJson(store); }
    }

    public static class Npcs extends JsonBackedStore implements NpcRepository {
        public Npcs(StoreAccess access) { super(access, "npcs"); }
        @Override public NpcRegistry read() { return readJson(NpcRegistry.class, NpcRegistry::new); }
        @Override public void write(NpcRegistry registry) { writeJson(registry); }
    }

    public static class AdminTools extends JsonBackedStore implements AdminToolRepository {
        public AdminTools(StoreAccess access) { super(access, "admin_tools"); }
        @Override public AdminToolStore read() { return readJson(AdminToolStore.class, AdminToolStore::new); }
        @Override public void write(AdminToolStore store) { writeJson(store); }
    }

    public static class Test extends JsonBackedStore implements TestRepository {
        public Test(StoreAccess access) { super(access, "test"); }
        @Override public TestStore read() { return readJson(TestStore.class, TestStore::new); }
        @Override public void write(TestStore store) { writeJson(store); }
    }

    public static class Incidents extends JsonBackedStore implements IncidentRepository {
        public Incidents(StoreAccess access) { super(access, "incidents"); }
        @Override public IncidentStore read() { return readJson(IncidentStore.class, IncidentStore::new); }
        @Override public void write(IncidentStore store) { writeJson(store); }
    }

    public static class Bolos extends JsonBackedStore implements BoloRepository {
        public Bolos(StoreAccess access) { super(access, "bolos"); }
        @Override public BoloStore read() { return readJson(BoloStore.class, BoloStore::new); }
        @Override public void write(BoloStore store) { writeJson(store); }
    }

    public static class Evidence extends JsonBackedStore implements EvidenceRepository {
        public Evidence(StoreAccess access) { super(access, "evidence"); }
        @Override public EvidenceStore read() { return readJson(EvidenceStore.class, EvidenceStore::new); }
        @Override public void write(EvidenceStore store) { writeJson(store); }
    }

    public static class ArrestRecords extends JsonBackedStore implements ArrestRecordRepository {
        public ArrestRecords(StoreAccess access) { super(access, "arrest_records"); }
        @Override public ArrestRecordStore read() { return readJson(ArrestRecordStore.class, ArrestRecordStore::new); }
        @Override public void write(ArrestRecordStore store) { writeJson(store); }
    }

    public static class Reputation extends JsonBackedStore implements ReputationRepository {
        public Reputation(StoreAccess access) { super(access, "reputation"); }
        @Override public ReputationStore read() { return readJson(ReputationStore.class, ReputationStore::new); }
        @Override public void write(ReputationStore store) { writeJson(store); }
    }

    public static class Personnel extends JsonBackedStore implements PersonnelRepository {
        public Personnel(StoreAccess access) { super(access, "personnel"); }
        @Override public PersonnelStore read() { return readJsonVersioned(PersonnelStore.class, PersonnelStore::new, PersonnelStore.CURRENT_SCHEMA_VERSION); }
        @Override public void write(PersonnelStore store) { writeJson(store); }
    }

    public static class Promotions extends JsonBackedStore implements PromotionRepository {
        public Promotions(StoreAccess access) { super(access, "promotions"); }
        @Override public PromotionStore read() { return readJsonVersioned(PromotionStore.class, PromotionStore::new, PromotionStore.CURRENT_SCHEMA_VERSION); }
        @Override public void write(PromotionStore store) { writeJson(store); }
    }

    public static class Stations extends JsonBackedStore implements StationRepository {
        public Stations(StoreAccess access) { super(access, "stations"); }
        @Override public StationStore read() { return readJsonVersioned(StationStore.class, StationStore::defaults, StationStore.CURRENT_SCHEMA_VERSION); }
        @Override public void write(StationStore store) { writeJson(store); }
    }

    public static class Documents extends JsonBackedStore implements DocumentRepository {
        public Documents(StoreAccess access) { super(access, "documents"); }
        @Override public DocumentStore read() {
            DocumentStore store = readJsonVersioned(DocumentStore.class, DocumentStore::new, DocumentStore.CURRENT_SCHEMA_VERSION);
            if (store.documents == null) store.documents = new java.util.LinkedHashMap<>();
            if (store.instruments == null) store.instruments = new java.util.LinkedHashMap<>();
            if (store.redemptions == null) store.redemptions = new java.util.LinkedHashMap<>();
            if (store.idempotencyIndex == null) store.idempotencyIndex = new java.util.LinkedHashMap<>();
            if (store.formRequests == null) store.formRequests = new java.util.LinkedHashMap<>();
            if (store.formRequestIndex == null) store.formRequestIndex = new java.util.LinkedHashMap<>();
            return store;
        }
        @Override public void write(DocumentStore store) { writeJson(store); }
    }

    public static class EquipmentLedger extends JsonBackedStore implements EquipmentRepository {
        public EquipmentLedger(StoreAccess access) { super(access, "equipment_ledger"); }
        @Override public EquipmentStore read() { return readJsonVersioned(EquipmentStore.class, EquipmentStore::new, EquipmentStore.CURRENT_SCHEMA_VERSION); }
        @Override public void write(EquipmentStore store) { writeJson(store); }
    }

    public static class Mobilizations extends JsonBackedStore implements MobilizationRepository {
        public Mobilizations(StoreAccess access) { super(access, "mobilizations"); }
        @Override public MobilizationStore read() { return readJsonVersioned(MobilizationStore.class, MobilizationStore::new, MobilizationStore.CURRENT_SCHEMA_VERSION); }
        @Override public void write(MobilizationStore store) { writeJson(store); }
    }

    public static class Campaigns extends JsonBackedStore implements CampaignRepository {
        public Campaigns(StoreAccess access) { super(access, "campaigns"); }
        @Override public CampaignStore read() { return readJsonVersioned(CampaignStore.class, CampaignStore::new, CampaignStore.CURRENT_SCHEMA_VERSION); }
        @Override public void write(CampaignStore store) { writeJson(store); }
    }

    public static class Settlements extends JsonBackedStore implements SettlementRepository {
        public Settlements(StoreAccess access) { super(access, "settlements"); }
        @Override public SettlementStore read() { return readJsonVersioned(SettlementStore.class, SettlementStore::new, SettlementStore.CURRENT_SCHEMA_VERSION); }
        @Override public void write(SettlementStore store) { writeJson(store); }
    }

    public static class Operations extends JsonBackedStore implements OperationRepository {
        public Operations(StoreAccess access) { super(access, "operations"); }
        @Override public OperationStore read() { return readJsonVersioned(OperationStore.class, OperationStore::new, OperationStore.CURRENT_SCHEMA_VERSION); }
        @Override public void write(OperationStore store) { writeJson(store); }
    }

    public static class Outbox extends JsonBackedStore implements OutboxRepository {
        public Outbox(StoreAccess access) { super(access, "outbox"); }
        @Override public List<OutboxEvent> read() {
            List<OutboxEvent> list = readJson(List.class, ArrayList::new);
            return GSON.fromJson(GSON.toJsonTree(list),
                    new com.google.gson.reflect.TypeToken<List<OutboxEvent>>() {}.getType());
        }
        @Override public void write(List<OutboxEvent> events) { writeJson(events); }
    }
}
