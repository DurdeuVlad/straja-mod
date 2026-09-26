package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.in.NpcProvisioningUseCase;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcProfileDescriptor;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcProvisioningAuditEntry;
import com.dwurdy.straja.domain.model.NpcHostLocation;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Coordinates the admin assignment flow without importing a provider API.
 * Content validation and durable lifecycle ownership remain application-owned;
 * the provider only receives the resulting binding and published surface.
 */
public final class NpcProvisioningService implements NpcProvisioningUseCase {
    private static final Pattern HOST = Pattern.compile("[^a-zA-Z0-9._:-]");
    private static final String STALE_ASSIGNMENT_CODE = "stale-assignment";
    private static final String OPERATION_PENDING_CODE = "operation-pending";

    private final NpcBindingLifecycleService lifecycle;
    private final NpcContentCatalog catalog;
    private final NpcSurfaceProviderRegistry providers;
    private final LongSupplier clock;

    public NpcProvisioningService(
            NpcBindingLifecycleService lifecycle,
            NpcContentCatalog catalog,
            NpcSurfaceProviderRegistry providers) {
        this(lifecycle, catalog, providers, System::currentTimeMillis);
    }

    public NpcProvisioningService(
            NpcBindingLifecycleService lifecycle,
            NpcContentCatalog catalog,
            NpcSurfaceProviderRegistry providers,
            LongSupplier clock) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<ProfileOption> profiles(NpcProviderId providerId) {
        Objects.requireNonNull(providerId, "providerId");
        boolean available = providers.available(providerId);
        var capabilities = providers.capabilities(providerId);
        return catalog.descriptors().stream()
                .sorted(Comparator.comparing(descriptor -> descriptor.profileId().value()))
                .map(descriptor -> {
                    List<String> missing = providers.supports(
                            providerId, catalog.require(descriptor.profileId()).requiredCapabilities())
                            ? List.of()
                            : catalog.validateCapabilities(descriptor.profileId(), capabilities);
                    String reason = !available
                            ? "NPC provider is unavailable"
                            : String.join("; ", missing);
                    return new ProfileOption(
                            descriptor.profileId().value(),
                            descriptor.title(),
                            descriptor.summary(),
                            descriptor.roleId(),
                            descriptor.stationId(),
                            descriptor.schemaVersion(),
                            available && missing.isEmpty(),
                            reason,
                            descriptor.dialogueContentIds(),
                            descriptor.questContentIds(),
                            descriptor.actionContentIds());
                })
                .toList();
    }

    @Override
    public Optional<AssignmentView> current(NpcProviderId providerId, String hostEntityUuid) {
        Objects.requireNonNull(providerId, "providerId");
        requireHost(hostEntityUuid);
        return inspectionsForHost(hostEntityUuid).stream()
                .map(NpcBindingLifecycleService.Inspection::binding)
                .findFirst()
                .map(NpcProvisioningService::view);
    }

    @Override
    public String assignmentRevision(String hostEntityUuid) {
        requireHost(hostEntityUuid);
        return lifecycle.assignmentRevision(hostEntityUuid);
    }

    @Override
    public ProvisioningResult assign(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            String profileId) {
        return assign(providerId, hostEntityUuid, actorId, profileId, null);
    }

    /** Legacy command seam may retain an explicit station while the GUI uses the catalog default. */
    public ProvisioningResult assign(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            String profileId,
            String stationOverride) {
        return assignInternal(providerId, hostEntityUuid, actorId, profileId,
                stationOverride, Optional.empty(), assignmentRevision(hostEntityUuid));
    }

    @Override
    public ProvisioningResult assignWithLocation(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            String profileId,
            Optional<NpcHostLocation> hostLocation) {
        return assignIfRevisionMatches(
                providerId, hostEntityUuid, actorId, profileId, hostLocation,
                assignmentRevision(hostEntityUuid));
    }

    @Override
    public synchronized ProvisioningResult assignIfRevisionMatches(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            String profileId,
            Optional<NpcHostLocation> hostLocation,
            String expectedRevision) {
        Objects.requireNonNull(hostLocation, "hostLocation");
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        return assignInternal(providerId, hostEntityUuid, actorId, profileId,
                null, hostLocation, expectedRevision);
    }

    private synchronized ProvisioningResult assignInternal(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            String profileId,
            String stationOverride,
            Optional<NpcHostLocation> requestedLocation,
            String expectedRevision) {
        Objects.requireNonNull(providerId, "providerId");
        requireHost(hostEntityUuid);
        requireActor(actorId);
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        if (!lifecycle.assignmentRevision(hostEntityUuid).equals(expectedRevision)) {
            return staleAssignment();
        }
        List<NpcBindingLifecycleService.Inspection> hostAssignments = inspectionsForHost(hostEntityUuid);
        if (hostAssignments.isEmpty() && lifecycle.hasPendingProvisioningIntentForHost(hostEntityUuid)) {
            return ProvisioningResult.unknown(
                    "NPC assignment has an unresolved provisioning intent without a recoverable candidate");
        }
        NpcBindingLifecycleService.Inspection currentInspection = hostAssignments.isEmpty()
                ? null : hostAssignments.get(0);
        NpcBinding current = currentInspection == null ? null : currentInspection.binding();
        String bindingId = current == null
                ? stableBindingId(providerId, hostEntityUuid)
                : current.bindingId();
        if (hostAssignments.size() > 1) {
            return audit(NpcProvisioningAuditEntry.Action.ASSIGN, providerId, hostEntityUuid,
                    actorId, current, profileId, bindingId,
                    ProvisioningResult.rejected(
                            "duplicate-host-assignment",
                            "multiple durable assignments target this host; unassign the unintended mapping before reassignment"));
        }
        if (currentInspection != null && currentInspection.state() == NpcBindingLifecycleService.State.UNKNOWN) {
            return audit(NpcProvisioningAuditEntry.Action.ASSIGN, providerId, hostEntityUuid,
                    actorId, current, profileId, bindingId,
                    ProvisioningResult.unknown(
                            "NPC assignment has an unresolved provider operation; wait for reconciliation before changing providers"));
        }
        if (!providers.available(providerId)) {
            return audit(NpcProvisioningAuditEntry.Action.ASSIGN, providerId, hostEntityUuid,
                    actorId, current, profileId, bindingId,
                    ProvisioningResult.unavailable("NPC provider is unavailable: " + providerId.value()));
        }

        NpcProfileDescriptor descriptor;
        try {
            descriptor = catalog.descriptor(profileId);
        } catch (IllegalArgumentException error) {
            return audit(NpcProvisioningAuditEntry.Action.ASSIGN, providerId, hostEntityUuid,
                    actorId, current, profileId, bindingId,
                    ProvisioningResult.rejected("unknown-profile", error.getMessage()));
        }

        List<String> missing = providers.supports(
                providerId, catalog.require(descriptor.profileId()).requiredCapabilities())
                ? List.of()
                : catalog.validateCapabilities(descriptor.profileId(), providers.capabilities(providerId));
        if (!missing.isEmpty()) {
            return audit(NpcProvisioningAuditEntry.Action.ASSIGN, providerId, hostEntityUuid,
                    actorId, current, descriptor.profileId().value(), bindingId,
                    ProvisioningResult.rejected("unsupported-capability", String.join("; ", missing)));
        }

        NpcBinding candidate;
        try {
            candidate = new NpcBinding(
                    bindingId,
                    providerId,
                    hostEntityUuid,
                    current != null && current.providerId().equals(providerId)
                            ? current.externalNpcId() : "",
                    descriptor.roleId(),
                    station(stationOverride, descriptor.stationId()),
                    descriptor.contentProfileId(),
                    descriptor.profileId(),
                    descriptor.schemaVersion(),
                    actorId,
                    nonnegativeNow(),
                    requestedLocation.orElseGet(() -> current == null ? null : current.hostLocation()));
        } catch (IllegalArgumentException error) {
            return audit(NpcProvisioningAuditEntry.Action.ASSIGN, providerId, hostEntityUuid,
                    actorId, current, profileId, bindingId,
                    ProvisioningResult.rejected("invalid-assignment", error.getMessage()));
        }

        return auditedMutation(
                NpcProvisioningAuditEntry.Action.ASSIGN,
                providerId, hostEntityUuid, actorId, current, descriptor.profileId().value(), bindingId,
                expectedRevision,
                candidate,
                () -> map(current == null
                        ? lifecycle.bindAndPublish(candidate)
                        : lifecycle.rebind(candidate)));
    }

    @Override
    public synchronized ProvisioningResult unassign(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId) {
        return unassignIfRevisionMatches(
                providerId, hostEntityUuid, actorId, assignmentRevision(hostEntityUuid));
    }

    @Override
    public synchronized ProvisioningResult unassignIfRevisionMatches(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            String expectedRevision) {
        Objects.requireNonNull(providerId, "providerId");
        requireHost(hostEntityUuid);
        requireActor(actorId);
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        if (!lifecycle.assignmentRevision(hostEntityUuid).equals(expectedRevision)) {
            return staleAssignment();
        }
        List<NpcBindingLifecycleService.Inspection> hostAssignments = inspectionsForHost(hostEntityUuid);
        if (hostAssignments.isEmpty()) {
            if (lifecycle.hasPendingProvisioningIntentForHost(hostEntityUuid)) {
                return ProvisioningResult.unknown(
                        "NPC assignment has an unresolved provisioning intent without a recoverable candidate");
            }
            return audit(NpcProvisioningAuditEntry.Action.UNASSIGN, providerId, hostEntityUuid,
                    actorId, null, "", stableBindingId(providerId, hostEntityUuid),
                    ProvisioningResult.rejected("not-bound", "NPC has no Straja profile assignment"));
        }
        if (hostAssignments.size() > 1) {
            return audit(NpcProvisioningAuditEntry.Action.UNASSIGN, providerId, hostEntityUuid,
                    actorId, null, "", stableBindingId(providerId, hostEntityUuid),
                    ProvisioningResult.rejected(
                            "duplicate-host-assignment",
                            "multiple durable assignments target this host; remove the unintended mapping first"));
        }
        NpcBinding current = hostAssignments.getFirst().binding();
        return auditedMutation(
                NpcProvisioningAuditEntry.Action.UNASSIGN,
                current.providerId(), hostEntityUuid, actorId, current, "", current.bindingId(), expectedRevision,
                current,
                () -> map(lifecycle.unbind(current.bindingId())));
    }

    @Override
    public synchronized ProvisioningResult unassignBindingIfRevisionMatches(
            NpcProviderId providerId,
            String hostEntityUuid,
            String bindingId,
            String actorId,
            String expectedRevision) {
        Objects.requireNonNull(providerId, "providerId");
        requireHost(hostEntityUuid);
        requireBindingId(bindingId);
        requireActor(actorId);
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        if (!lifecycle.assignmentRevision(hostEntityUuid).equals(expectedRevision)) {
            return staleAssignment();
        }
        NpcBindingLifecycleService.Inspection inspection = lifecycle.inspect(bindingId);
        NpcBinding current = inspection.binding();
        if (current == null || !hostEntityUuid.equals(current.hostEntityUuid())) {
            return audit(NpcProvisioningAuditEntry.Action.UNASSIGN, providerId, hostEntityUuid,
                    actorId, null, "", bindingId,
                    ProvisioningResult.rejected("not-bound", "the requested binding is not assigned to this NPC"));
        }
        if (inspection.state() == NpcBindingLifecycleService.State.UNKNOWN) {
            return audit(NpcProvisioningAuditEntry.Action.UNASSIGN, providerId, hostEntityUuid,
                    actorId, current, "", bindingId,
                    ProvisioningResult.unknown("the requested binding has an unresolved provider operation"));
        }
        return auditedMutation(
                NpcProvisioningAuditEntry.Action.UNASSIGN,
                current.providerId(), hostEntityUuid, actorId, current, "", current.bindingId(), expectedRevision,
                current,
                () -> map(lifecycle.unbind(current.bindingId())));
    }

    @Override
    public synchronized ProvisioningResult reproject(
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId) {
        Objects.requireNonNull(providerId, "providerId");
        requireHost(hostEntityUuid);
        requireActor(actorId);
        List<NpcBindingLifecycleService.Inspection> hostAssignments = inspectionsForHost(hostEntityUuid);
        if (hostAssignments.isEmpty()) {
            return audit(NpcProvisioningAuditEntry.Action.REPROJECT, providerId, hostEntityUuid,
                    actorId, null, "", stableBindingId(providerId, hostEntityUuid),
                    ProvisioningResult.rejected("not-bound", "NPC has no Straja profile assignment"));
        }
        if (hostAssignments.size() > 1) {
            return audit(NpcProvisioningAuditEntry.Action.REPROJECT, providerId, hostEntityUuid,
                    actorId, null, "", stableBindingId(providerId, hostEntityUuid),
                    ProvisioningResult.rejected("duplicate-host-assignment", "multiple host assignments need cleanup"));
        }
        NpcBinding current = hostAssignments.getFirst().binding();
        ProvisioningResult result;
        try {
            result = map(lifecycle.reproject(current.bindingId()));
        } catch (RuntimeException error) {
            // The lifecycle writes a retry intent before invoking the provider,
            // so either publication has not started or restart can recover it.
            // Keep storage/provider failures from escaping the admin interaction.
            result = ProvisioningResult.unknown(
                    "provider projection could not be confirmed; inspect NPC status before retrying");
        }
        return audit(NpcProvisioningAuditEntry.Action.REPROJECT, current.providerId(), hostEntityUuid,
                actorId, current, current.profileId().value(), current.bindingId(), result);
    }

    @Override
    public Optional<AssignmentStatus> status(NpcProviderId providerId, String hostEntityUuid) {
        Objects.requireNonNull(providerId, "providerId");
        requireHost(hostEntityUuid);
        NpcBindingLifecycleService.Inspection inspection = inspectionsForHost(hostEntityUuid).stream()
                .findFirst().orElse(null);
        if (inspection == null) return Optional.empty();
        NpcBinding binding = inspection.binding();
        String lastProjectionError = "";
        List<NpcProvisioningAuditEntry> events = auditTrail(binding.providerId(), hostEntityUuid);
        for (int index = events.size() - 1; index >= 0; index--) {
            NpcProvisioningAuditEntry event = events.get(index);
            if (event.action() == NpcProvisioningAuditEntry.Action.ASSIGN
                    || event.action() == NpcProvisioningAuditEntry.Action.REPROJECT
                    || event.action() == NpcProvisioningAuditEntry.Action.MIGRATE) {
                lastProjectionError = event.outcome() == NpcProvisioningAuditEntry.Outcome.ACCEPTED
                        ? "" : event.failureReason();
                break;
            }
        }
        if (!catalog.hasProfile(binding.profileId())) {
            lastProjectionError = "assigned NPC profile is unknown or retired";
        }
        return Optional.of(new AssignmentStatus(
                view(binding), inspection.state().name(),
                inspection.pendingOperation() == null ? "" : inspection.pendingOperation().name(),
                lastProjectionError));
    }

    @Override
    public List<AssignmentView> assignments(NpcProviderId providerId, String hostEntityUuid) {
        Objects.requireNonNull(providerId, "providerId");
        requireHost(hostEntityUuid);
        return inspectionsForHost(hostEntityUuid).stream()
                .map(inspection -> view(inspection.binding()))
                .toList();
    }

    @Override
    public List<NpcProvisioningAuditEntry> auditTrail(NpcProviderId providerId, String hostEntityUuid) {
        Objects.requireNonNull(providerId, "providerId");
        requireHost(hostEntityUuid);
        return lifecycle.provisioningAudit().stream()
                .filter(event -> providerId.value().equals(event.providerId()))
                .filter(event -> hostEntityUuid.equals(event.providerInstanceId()))
                .toList();
    }

    private long nonnegativeNow() {
        return Math.max(0L, clock.getAsLong());
    }

    private static AssignmentView view(NpcBinding binding) {
        return new AssignmentView(
                binding.bindingId(),
                binding.providerId().value(),
                binding.hostEntityUuid(),
                binding.profileId().value(),
                binding.roleId(),
                binding.stationId(),
                binding.assignedBy(),
                binding.assignedAtEpochMillis(),
                binding.schemaVersion(),
                binding.hostLocation());
    }

    private List<NpcBindingLifecycleService.Inspection> inspectionsForHost(String hostEntityUuid) {
        return lifecycle.inspections().values().stream()
                .filter(inspection -> inspection.binding() != null)
                .filter(inspection -> inspection.binding().hostEntityUuid().equals(hostEntityUuid))
                .sorted(Comparator.comparing(inspection -> inspection.binding().bindingId()))
                .toList();
    }

    private ProvisioningResult auditedMutation(
            NpcProvisioningAuditEntry.Action action,
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            NpcBinding previous,
            String newProfileId,
            String bindingId,
            String expectedRevision,
            NpcBinding requestedBinding,
            Supplier<ProvisioningResult> mutation) {
        NpcProvisioningAuditEntry intent = new NpcProvisioningAuditEntry(
                java.util.UUID.randomUUID().toString(), bindingId, action,
                providerId.value(), hostEntityUuid, actorId,
                previous == null ? "" : previous.profileId().value(), newProfileId,
                NpcProvisioningAuditEntry.Outcome.PENDING, OPERATION_PENDING_CODE,
                "Provisioning operation is in progress.", nonnegativeNow());
        try {
            if (!lifecycle.recordProvisioningAuditIfRevision(intent, expectedRevision, requestedBinding)) {
                return staleAssignment();
            }
        } catch (NpcBindingLifecycleService.ProvisioningAuditCapacityException error) {
            return ProvisioningResult.rejected("recovery-backlog-full", error.getMessage());
        } catch (RuntimeException error) {
            return ProvisioningResult.unknown(
                    "assignment revision could not be durably recorded; inspect NPC status before retrying");
        }

        ProvisioningResult result;
        try {
            result = mutation.get();
        } catch (RuntimeException error) {
            // Keep the durable intent pending. The lifecycle may have failed
            // while persisting its recovery phase after a provider call; a
            // terminal UNKNOWN would erase the exact candidate needed to
            // resume safely after restart.
            return ProvisioningResult.unknown(
                    "provider operation failed without a reliable result; inspect NPC status before retrying");
        }
        NpcProvisioningAuditEntry.Outcome outcome = NpcProvisioningAuditEntry.Outcome.valueOf(
                result.status().name());
        NpcProvisioningAuditEntry completed = new NpcProvisioningAuditEntry(
                intent.eventId(), intent.bindingId(), intent.action(), intent.providerId(),
                intent.providerInstanceId(), intent.actorId(), intent.oldProfileId(), intent.newProfileId(),
                outcome, result.code(), outcome == NpcProvisioningAuditEntry.Outcome.ACCEPTED
                        ? "" : result.message(), intent.occurredAtEpochMillis());
        try {
            lifecycle.completeProvisioningAudit(completed);
        } catch (RuntimeException error) {
            return ProvisioningResult.unknown(
                    "operation result could not be durably audited; inspect NPC status before retrying");
        }
        return result;
    }

    private static ProvisioningResult staleAssignment() {
        return ProvisioningResult.rejected(
                STALE_ASSIGNMENT_CODE,
                "NPC assignment changed after this confirmation opened; review the current assignment and confirm again");
    }

    private ProvisioningResult audit(
            NpcProvisioningAuditEntry.Action action,
            NpcProviderId providerId,
            String hostEntityUuid,
            String actorId,
            NpcBinding previous,
            String newProfileId,
            String bindingId,
            ProvisioningResult result) {
        NpcProvisioningAuditEntry.Outcome outcome = NpcProvisioningAuditEntry.Outcome.valueOf(
                result.status().name());
        try {
            lifecycle.recordProvisioningAudit(new NpcProvisioningAuditEntry(
                    java.util.UUID.randomUUID().toString(), bindingId, action,
                    providerId.value(), hostEntityUuid, actorId,
                    previous == null ? "" : previous.profileId().value(), newProfileId,
                    outcome, result.code(), outcome == NpcProvisioningAuditEntry.Outcome.ACCEPTED
                            ? "" : result.message(), nonnegativeNow()));
        } catch (RuntimeException error) {
            return ProvisioningResult.unknown(
                    "operation result could not be durably audited; inspect NPC status before retrying");
        }
        return result;
    }

    private static ProvisioningResult map(NpcProviderResult result) {
        return switch (result.status()) {
            case ACCEPTED, RECONCILED -> ProvisioningResult.accepted(result.message());
            case REJECTED -> ProvisioningResult.rejected(result.code(), result.message());
            case UNAVAILABLE -> ProvisioningResult.unavailable(result.message());
            case UNKNOWN -> ProvisioningResult.unknown(result.message());
        };
    }

    private static String stableBindingId(NpcProviderId providerId, String hostEntityUuid) {
        String hash = java.util.UUID.nameUUIDFromBytes(
                hostEntityUuid.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
        return "straja." + providerId.value() + "." + hash.substring(0, 32);
    }

    private static void requireHost(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || HOST.matcher(value).find()) {
            throw new IllegalArgumentException("hostEntityUuid must be a non-blank safe identifier");
        }
    }

    private static void requireActor(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("actorId must be a non-blank identifier");
        }
    }

    private static void requireBindingId(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("bindingId must be a non-blank safe identifier");
        }
    }

    private static String station(String override, String fallback) {
        if (override == null || override.isBlank()) return fallback;
        if (!override.matches("[a-z][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("stationId has invalid format: " + override);
        }
        return override;
    }
}
