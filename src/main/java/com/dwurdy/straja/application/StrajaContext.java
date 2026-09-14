package com.dwurdy.straja.application;

import com.dwurdy.straja.application.port.out.*;
import com.dwurdy.straja.domain.model.StrajaPolicies;

/**
 * The assembled hexagon: policies + all outbound ports, injected into every
 * application service. Built once at bootstrap; tests build their own with
 * fakes.
 */
public record StrajaContext(
        StrajaPolicies policies,
        Clock clock,
        IdGenerator ids,
        ServerGateway server,
        PlayerStateRepository players,
        SetupRepository setup,
        AuditRepository audit,
        InboxRepository inbox,
        MissionRepository missions,
        FineRepository fines,
        PrisonRepository prison,
        RoomRepository rooms,
        ComplaintRepository complaints,
        ReportRepository reports,
        AudienceRepository audiences,
        CustodyRepository custody,
        ArchiveRepository archive,
        NpcRepository npcs,
        TestRepository test,
        CurrencyProvider currency,
        DeliveryProvider delivery,
        WorldGateway world,
        FactionGateway factions) {}
