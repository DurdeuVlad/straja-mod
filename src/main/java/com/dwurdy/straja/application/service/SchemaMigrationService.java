package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.CampaignStore;
import com.dwurdy.straja.domain.model.DocumentStore;
import com.dwurdy.straja.domain.model.EquipmentStore;
import com.dwurdy.straja.domain.model.MobilizationStore;
import com.dwurdy.straja.domain.model.OperationStore;
import com.dwurdy.straja.domain.model.PersonnelStore;
import com.dwurdy.straja.domain.model.PromotionStore;
import com.dwurdy.straja.domain.model.SettlementStore;
import com.dwurdy.straja.domain.model.StationStore;

/** Small, deterministic schema gate for the new SavedData aggregates. */
public final class SchemaMigrationService {
    public <T> T requireSupported(int version, int current, T value) {
        if (version > current) throw new IllegalStateException("unsupported future schema version " + version);
        return value;
    }

    public PersonnelStore personnel(PersonnelStore value) {
        if (value == null) value = new PersonnelStore();
        requireSupported(value.schemaVersion, PersonnelStore.CURRENT_SCHEMA_VERSION, value);
        value.schemaVersion = PersonnelStore.CURRENT_SCHEMA_VERSION;
        if (value.records == null) value.records = new java.util.LinkedHashMap<>();
        return value;
    }
    public PromotionStore promotions(PromotionStore value) { return normalize(value, PromotionStore.CURRENT_SCHEMA_VERSION, PromotionStore::new); }
    public StationStore stations(StationStore value) { return normalize(value, StationStore.CURRENT_SCHEMA_VERSION, StationStore::defaults); }
    public DocumentStore documents(DocumentStore value) {
        value = normalize(value, DocumentStore.CURRENT_SCHEMA_VERSION, DocumentStore::new);
        if (value.documents == null) value.documents = new java.util.LinkedHashMap<>();
        if (value.instruments == null) value.instruments = new java.util.LinkedHashMap<>();
        if (value.redemptions == null) value.redemptions = new java.util.LinkedHashMap<>();
        if (value.idempotencyIndex == null) value.idempotencyIndex = new java.util.LinkedHashMap<>();
        if (value.formRequests == null) value.formRequests = new java.util.LinkedHashMap<>();
        if (value.formRequestIndex == null) value.formRequestIndex = new java.util.LinkedHashMap<>();
        return value;
    }
    public EquipmentStore equipment(EquipmentStore value) { return normalize(value, EquipmentStore.CURRENT_SCHEMA_VERSION, EquipmentStore::new); }
    public MobilizationStore mobilizations(MobilizationStore value) { return normalize(value, MobilizationStore.CURRENT_SCHEMA_VERSION, MobilizationStore::new); }
    public CampaignStore campaigns(CampaignStore value) { return normalize(value, CampaignStore.CURRENT_SCHEMA_VERSION, CampaignStore::new); }
    public SettlementStore settlements(SettlementStore value) { return normalize(value, SettlementStore.CURRENT_SCHEMA_VERSION, SettlementStore::new); }
    public OperationStore operations(OperationStore value) { return normalize(value, OperationStore.CURRENT_SCHEMA_VERSION, OperationStore::new); }

    private <T> T normalize(T value, int current, java.util.function.Supplier<T> factory) {
        if (value == null) value = factory.get();
        int version = ((Number) readField(value, "schemaVersion")).intValue();
        requireSupported(version, current, value);
        writeField(value, "schemaVersion", current);
        return value;
    }

    private static Object readField(Object value, String name) {
        try { return value.getClass().getField(name).get(value); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("missing schema metadata", error); }
    }
    private static void writeField(Object value, String name, int version) {
        try { value.getClass().getField(name).setInt(value, version); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("missing schema metadata", error); }
    }
}
