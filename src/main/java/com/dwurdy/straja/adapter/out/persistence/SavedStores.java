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

    public static class Npcs extends JsonBackedStore implements NpcRepository {
        public Npcs(StoreAccess access) { super(access, "npcs"); }
        @Override public NpcRegistry read() { return readJson(NpcRegistry.class, NpcRegistry::new); }
        @Override public void write(NpcRegistry registry) { writeJson(registry); }
    }

    public static class Test extends JsonBackedStore implements TestRepository {
        public Test(StoreAccess access) { super(access, "test"); }
        @Override public TestStore read() { return readJson(TestStore.class, TestStore::new); }
        @Override public void write(TestStore store) { writeJson(store); }
    }
}
