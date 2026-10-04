package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.Fine;
import com.dwurdy.straja.domain.model.FineStore;
import com.dwurdy.straja.domain.model.PrisonerRegisterStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DEBT-1 (#234) debt ledger. Every fine carries a {@code paidAmount} plus a
 * contribution trail; this service totals the unpaid balance, applies money
 * to it oldest-first (auditing each application), and hands debtors written
 * books — directly while online, queued under {@code pendingNotices} while
 * offline and drained at login next to pending locker belongings.
 */
public final class DebtService {
    /** Statuses whose {@link Fine#remaining()} still counts as collectible debt. */
    public static final List<String> PAYABLE = List.of(
            "ISSUED", "DELIVERY_FAILED", "PAYMENT_REVIEW",
            "ARREST_PENDING", "IN_SENTENCE", "GRACE_AFTER_SENTENCE");

    private final StrajaContext ctx;
    private final AuditService audit;
    private PlayerService players;
    /** DEBT-2: locker/chest extraction stays with the seizure engine. */
    private SeizureService seizure;

    public DebtService(StrajaContext ctx, AuditService audit) {
        this.ctx = ctx;
        this.audit = audit;
    }

    /** DEBT-3: the /straja debt view gate needs the officer check (late-bound). */
    public void usePlayers(PlayerService service) {
        this.players = service;
    }

    /** DEBT-2 (#235): custody-coin extraction (late-bound like prison.useDebt). */
    public void useSeizure(SeizureService service) {
        this.seizure = service;
    }

    private long now() {
        return ctx.clock().nowMillis();
    }

    /** Per-fine breakdown row returned by {@link #allocate}. */
    public record Allocation(String fineId, int applied, boolean covered) {}

    /** Total unpaid balance across the target's payable fines. */
    public int outstandingDebt(String targetUuid) {
        if (targetUuid == null || targetUuid.isBlank()) return 0;
        int total = 0;
        for (Fine fine : ctx.fines().read().fines) {
            if (fine != null && targetUuid.equals(fine.targetUuid)
                    && PAYABLE.contains(fine.status)) {
                total += fine.remaining();
            }
        }
        return total;
    }

    /**
     * Applies {@code amount} to the target's payable fines oldest-first by
     * {@code issuedAt}: each application appends a contribution, bumps
     * {@code paidAmount}, flips the fine PAID (+{@code paidAt}) when covered
     * and is audited. Returns the per-fine breakdown.
     */
    public List<Allocation> allocate(String targetUuid, int amount, String payer,
                                     String payerUuid, String source) {
        return allocate(targetUuid, amount, payer, payerUuid, source, null);
    }

    /**
     * DEBT-3: same oldest-first allocation, except fines carrying
     * {@code priorityLaw} are served first (bail lands on the bounty-capture
     * fine before the rest of the prisoner's debt).
     */
    public List<Allocation> allocate(String targetUuid, int amount, String payer,
                                     String payerUuid, String source, String priorityLaw) {
        FineStore data = ctx.fines().read();
        List<Fine> payable = new ArrayList<>();
        if (targetUuid != null) {
            for (Fine fine : data.fines) {
                if (fine != null && targetUuid.equals(fine.targetUuid)
                        && PAYABLE.contains(fine.status) && fine.remaining() > 0) {
                    payable.add(fine);
                }
            }
        }
        payable.sort(Comparator.comparingLong(fine -> fine.issuedAt));
        if (priorityLaw != null && !priorityLaw.isBlank()) {
            List<Fine> first = new ArrayList<>(), rest = new ArrayList<>();
            for (Fine fine : payable) {
                (priorityLaw.equals(fine.law) ? first : rest).add(fine);
            }
            payable.clear();
            payable.addAll(first);
            payable.addAll(rest);
        }
        int left = Math.max(0, amount);
        List<Allocation> out = new ArrayList<>();
        for (Fine fine : payable) {
            if (left <= 0) break;
            int applied = applyPayment(fine, left, payer, payerUuid, source);
            if (applied <= 0) continue;
            out.add(new Allocation(fine.id, applied, "PAID".equals(fine.status)));
            left -= applied;
        }
        if (!out.isEmpty()) ctx.fines().write(data);
        return List.copyOf(out);
    }

    /**
     * Applies up to {@code amount} to one fine: appends a contribution and
     * bumps {@code paidAmount}; a covered fine flips PAID (+{@code paidAt}).
     * The application is audited; the caller owns the store write.
     */
    public int applyPayment(Fine fine, int amount, String payer,
                            String payerUuid, String source) {
        if (fine == null) return 0;
        int applied = Math.max(0, Math.min(fine.remaining(), amount));
        if (applied > 0) {
            Fine.Contribution contribution = new Fine.Contribution();
            contribution.payer = payer == null ? "" : payer;
            contribution.payerUuid = payerUuid == null ? "" : payerUuid;
            contribution.amount = applied;
            contribution.at = now();
            contribution.source = source == null ? "" : source;
            if (fine.contributions == null) fine.contributions = new ArrayList<>();
            fine.contributions.add(contribution);
            fine.paidAmount += applied;
            audit.record("debt_apply", contribution.payer, contribution.payerUuid,
                    fine.target, fine.targetUuid, "SUCCESS",
                    "fineId=" + fine.id + " applied=" + applied + " source=" + contribution.source
                            + " remaining=" + fine.remaining());
        }
        if (fine.remaining() <= 0 && PAYABLE.contains(fine.status)) {
            fine.status = "PAID";
            fine.paidAt = now();
        }
        return applied;
    }

    /**
     * Payment toward a citizen's debt (third-party or levy): caps at the
     * outstanding balance — refusing cleanly at zero — withdraws the capped
     * amount, then allocates it. Contributions are final.
     */
    public List<Allocation> contribute(PlayerGateway payer, String targetUuid,
                                       int amount, String source) {
        return contribute(payer, targetUuid, amount, source, null);
    }

    /** As {@link #contribute}, but serves {@code priorityLaw} fines first. */
    public List<Allocation> contribute(PlayerGateway payer, String targetUuid,
                                       int amount, String source, String priorityLaw) {
        if (payer == null) return List.of();
        int capped = Math.min(Math.max(0, amount), outstandingDebt(targetUuid));
        if (capped <= 0) {
            payer.refuse("straja.debt.none", "straja.remedy.reception");
            return List.of();
        }
        var withdrawal = ctx.currency().withdraw(payer, capped);
        if (!withdrawal.ok()) {
            payer.refuse("straja.fine.pay_failed", "straja.remedy.retry");
            return List.of();
        }
        var applied = allocate(targetUuid, capped, payer.name(),
                payer.uuid() == null ? "" : payer.uuid().toString(), source, priorityLaw);
        if (!applied.isEmpty()) {
            payer.tell("Contribuția de " + capped + " monede a fost înregistrată.");
        }
        return applied;
    }

    // ------------------------------------------------ DEBT-3 player surface

    /**
     * Resolves a name-or-uuid input to the debtor's uuid, uuid-first: an
     * online player wins, then the fine store's uuid and name columns, the
     * prisoner register (uuid key, then name), a syntactically valid uuid,
     * and finally Mojang's deterministic offline-name uuid so unknown names
     * still key cleanly (and simply read as zero debt).
     */
    public String resolveDebtorUuid(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) return "";
        String input = nameOrUuid.trim();
        PlayerGateway online = ctx.server().findPlayer(input);
        if (online != null && online.uuid() != null) return online.uuid().toString();
        var data = ctx.fines().read();
        for (Fine fine : data.fines) {
            if (fine != null && input.equals(fine.targetUuid)) return input;
        }
        for (Fine fine : data.fines) {
            if (fine != null && input.equalsIgnoreCase(fine.target)
                    && fine.targetUuid != null && !fine.targetUuid.isBlank()) {
                return fine.targetUuid;
            }
        }
        var reg = ctx.prisonerRegister().read();
        if (reg.prisoner(input) != null) return input;
        var rec = reg.prisonerByName(input);
        if (rec != null && rec.detaineeUuid != null && !rec.detaineeUuid.isBlank()) {
            return rec.detaineeUuid;
        }
        try {
            return java.util.UUID.fromString(input).toString();
        } catch (IllegalArgumentException notUuid) {
            return PrisonerRegisterStore.legacyUuid(input);
        }
    }

    /** The target's payable fines with a positive balance, oldest-first. */
    public List<Fine> payableFines(String targetUuid) {
        List<Fine> out = new ArrayList<>();
        if (targetUuid != null) {
            for (Fine fine : ctx.fines().read().fines) {
                if (fine != null && targetUuid.equals(fine.targetUuid)
                        && PAYABLE.contains(fine.status) && fine.remaining() > 0) {
                    out.add(fine);
                }
            }
        }
        out.sort(Comparator.comparingLong(fine -> fine.issuedAt));
        return out;
    }

    /**
     * {@code /straja debt <player>}: the ledger rows (fine id, law, remaining)
     * plus the total owed. Officers read any citizen; anyone else may only
     * inspect their own debt.
     */
    public void showDebt(PlayerGateway viewer, String nameOrUuid) {
        if (viewer == null) return;
        String targetUuid = resolveDebtorUuid(nameOrUuid);
        boolean self = viewer.uuid() != null
                && viewer.uuid().toString().equals(targetUuid);
        if (!self && (players == null
                || (!players.isCommissioner(viewer)
                    && !players.hasCapability(viewer, Capability.ISSUE_FINES)))) {
            viewer.refuse("straja.debt.view_rank", "straja.remedy.fix_retry");
            return;
        }
        var rows = payableFines(targetUuid);
        if (rows.isEmpty()) {
            viewer.refuse("straja.debt.none", "straja.remedy.reception");
            return;
        }
        int total = 0;
        for (Fine fine : rows) {
            total += fine.remaining();
            viewer.tell(fine.id + " — " + fine.law + ": " + fine.remaining()
                    + " monede rămase");
        }
        viewer.tell("Total de plată restant: " + total + " monede.");
    }

    /**
     * {@code /straja debt pay} and {@code /straja bail} shared flow: resolves
     * the debtor, defaults an omitted amount to the full remainder, routes the
     * capped coins through {@link #contribute} and mails the debtor a
     * contribution-credit book naming the payer, the per-fine applications
     * and the new remaining balance.
     */
    public List<Allocation> payContribution(PlayerGateway payer, String nameOrUuid,
                                            Integer amount, String source,
                                            String priorityLaw) {
        if (payer == null) return List.of();
        if (amount != null && amount <= 0) {
            payer.refuse("straja.debt.invalid_amount", "straja.remedy.fix_retry");
            return List.of();
        }
        String targetUuid = resolveDebtorUuid(nameOrUuid);
        int requested = amount == null ? outstandingDebt(targetUuid) : amount;
        var applied = contribute(payer, targetUuid, requested, source, priorityLaw);
        if (applied.isEmpty()) return applied;
        int paid = applied.stream().mapToInt(Allocation::applied).sum();
        var pages = new ArrayList<String>();
        pages.add("Înștiințare de plată\n\n" + payer.name() + " a achitat " + paid
                + " monede către datoriile tale restante.");
        var detail = new StringBuilder();
        var data = ctx.fines().read();
        for (Allocation a : applied) {
            Fine fine = data.find(a.fineId());
            detail.append(a.fineId()).append(": +").append(a.applied())
                    .append(" monede").append(a.covered() ? " (achitată)" : "")
                    .append(fine == null ? "" : " — rămas " + fine.remaining())
                    .append('\n');
        }
        pages.add(detail.toString().trim());
        pages.add("Rest de plată: " + outstandingDebt(targetUuid) + " monede.");
        notifyPrisoner(targetUuid, null, "Înștiințare de plată", pages);
        return applied;
    }

    /**
     * DEBT-2 (#235) levy: a prisoner cannot hold coins while owing. Sweeps
     * the live inventory first (coins acquired during custody), then the
     * seized custody chests — personal locker, then pending-locker
     * reservations — capped at the outstanding debt. The extracted value is
     * allocated oldest-first under source {@code "LEVY"}, audited, and
     * receipted as a written book (queued while offline). Safe no-op at
     * zero debt or zero coins. Returns the base units applied to fines.
     */
    public int levy(String targetUuid) {
        if (targetUuid == null || targetUuid.isBlank()
                || !ctx.policies().debtEnabled) return 0;
        int owed = outstandingDebt(targetUuid);
        if (owed <= 0) return 0;
        int remaining = owed;
        int extracted = 0;
        PlayerGateway online = ctx.server().findPlayer(targetUuid);
        if (online != null && online.isOnline()) {
            int take = Math.min(remaining, Math.max(0, ctx.currency().balanceOf(online)));
            if (take > 0) {
                var withdrawal = ctx.currency().withdraw(online, take);
                extracted += Math.max(0, withdrawal.removed());
                remaining -= Math.max(0, withdrawal.removed());
            }
        }
        if (remaining > 0 && seizure != null) {
            int taken = seizure.extractCustodyCoins(targetUuid, remaining);
            extracted += taken;
            remaining -= taken;
        }
        if (extracted <= 0) return 0;
        String name = debtorName(targetUuid, online);
        var applied = allocate(targetUuid, extracted, name, targetUuid, "LEVY");
        int appliedTotal = applied.stream().mapToInt(Allocation::applied).sum();
        int debtLeft = outstandingDebt(targetUuid);
        audit.record("levy", "system", "", name, targetUuid, "SUCCESS",
                "extracted=" + extracted + " applied=" + appliedTotal
                        + " remaining=" + debtLeft);
        notifyPrisoner(targetUuid, online, "Proces-verbal de sechestru",
                levyReceiptPages(name, extracted, applied, debtLeft));
        return appliedTotal;
    }

    /**
     * DEBT-2 release-denial statement: remaining debt, the configured
     * threshold and the way out (contributions / labor-camp accrual).
     */
    public void notifyReleaseDenied(String targetUuid, PlayerGateway online,
                                    int owed, int threshold) {
        List<String> pages = new ArrayList<>();
        pages.add("REFUZ DE ELIBERARE\n\nDatorie restantă: " + owed + " unități"
                + "\nPrag de eliberare: " + threshold + " unități\n\n"
                + "Eliberarea rămâne blocată până când datoria nu mai depășește pragul.");
        pages.add("Cale de ieșire: contribuții plătite la Recepționistă — orice "
                + "cetățean poate achita pentru tine — sau credit de muncă în "
                + "lagăr spre prețul libertății.");
        notifyPrisoner(targetUuid, online, "Refuz de eliberare", pages);
    }

    /**
     * DEBT-2 transfer order issued when a debt-blocked release diverts to a
     * labor camp: amount owed, the camp and how the debt clears.
     */
    public void notifyTransferOrder(String targetUuid, PlayerGateway online,
                                    int owed, String campName) {
        List<String> pages = new ArrayList<>();
        pages.add("ORDIN DE TRANSFER\n\nDatorie restantă: " + owed + " unități"
                + "\nDestinație: lagărul de muncă " + campName + "\n\n"
                + "Eliberarea a fost blocată de datorie — detenția continuă în lagăr.");
        pages.add("Datoria se stinge prin contribuții plătite la Recepționistă "
                + "(orice cetățean poate achita); munca în lagăr adună credit în "
                + "contul de muncă spre prețul libertății.");
        notifyPrisoner(targetUuid, online, "Ordin de transfer", pages);
    }

    private String debtorName(String targetUuid, PlayerGateway online) {
        if (online != null && online.name() != null && !online.name().isBlank()) {
            return online.name();
        }
        var rec = ctx.prisonerRegister().read().prisoner(targetUuid);
        if (rec != null && rec.detaineeName != null && !rec.detaineeName.isBlank()) {
            return rec.detaineeName;
        }
        for (Fine fine : ctx.fines().read().fines) {
            if (fine != null && targetUuid.equals(fine.targetUuid)
                    && fine.target != null && !fine.target.isBlank()) return fine.target;
        }
        return targetUuid;
    }

    private List<String> levyReceiptPages(String name, int extracted,
                                          List<Allocation> applied, int debtLeft) {
        List<String> pages = new ArrayList<>();
        StringBuilder first = new StringBuilder();
        first.append("PROCES-VERBAL DE SECHESTRU\n\nDeținut: ").append(name)
                .append("\nMonede sechestrate: ").append(extracted).append(" unități");
        if (!applied.isEmpty()) {
            first.append("\n\nAplicat pe datorii:");
            var store = ctx.fines().read();
            for (var row : applied) {
                Fine fine = store.find(row.fineId());
                first.append("\n").append(row.fineId()).append(": +").append(row.applied());
                if (row.covered()) first.append(" (achitată)");
                else if (fine != null) first.append(" — rest ").append(fine.remaining());
            }
        }
        pages.add(first.toString());
        pages.add("Datorie restantă: " + debtLeft + " unități.\n\nMonedele sechestrate "
                + "se văd pe chitanță și nu se mai restituie; restul bunurilor din "
                + "dulap îți este restituit la eliberare.");
        return pages;
    }

    /**
     * Book notice for a debtor: online hands the written book over, offline
     * queues it on the prisoner register so {@link #deliverPendingNotices}
     * can pour it back at login.
     */
    public void notifyPrisoner(String targetUuid, PlayerGateway online,
                             String title, List<String> pages) {
        if (targetUuid == null || targetUuid.isBlank()) return;
        PlayerGateway target = online != null && online.isOnline()
                ? online : ctx.server().findPlayer(targetUuid);
        if (target != null && target.isOnline()) {
            try {
                target.giveWrittenBook(title == null ? "" : title, "Straja", pages);
                return;
            } catch (RuntimeException ignored) {
                // A book that cannot be handed over waits for the next login.
            }
        }
        var reg = ctx.prisonerRegister().read();
        var notice = new PrisonerRegisterStore.PendingNotice();
        notice.title = title == null ? "" : title;
        notice.author = "Straja";
        notice.pages = pages == null ? new ArrayList<>() : new ArrayList<>(pages);
        notice.createdAt = now();
        reg.enqueueNotice(targetUuid, notice);
        ctx.prisonerRegister().write(reg);
    }

    /** Delivers book notices queued while the player was offline. */
    public void deliverPendingNotices(PlayerGateway target) {
        if (target == null || target.uuid() == null) return;
        var reg = ctx.prisonerRegister().read();
        var notices = reg.drainPendingNotices(target.uuid().toString());
        if (notices.isEmpty()) return;
        int delivered = 0;
        for (var notice : notices) {
            if (notice == null) continue;
            try {
                target.giveWrittenBook(notice.title, notice.author, notice.pages);
                delivered++;
            } catch (RuntimeException ignored) {
                // A report that cannot be delivered must never undo the drain.
            }
        }
        ctx.prisonerRegister().write(reg);
        if (delivered > 0) {
            audit.record("notice_delivery", "system", "", target.name(),
                    target.uuid().toString(), "SUCCESS", "pending delivered=" + delivered);
        }
    }
}
