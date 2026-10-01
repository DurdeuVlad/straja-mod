"""Master map for the refusal->remedy sweep.

Each entry: RO message template (as emitted by refusal-plan, with %s for
interpolated args) -> (lang key, remedy key, EN translation).

Templates absent from MAP are left as literals — they must not match the
source-scan denial regex (verified by the apply script and the JUnit test).
"""

R = "straja.remedy."

MAP = {
    # ── adapter/in/command/StrajaCommands.java ──────────────────────────
    "Straja nu este pornită.":
        ("straja.cmd.runtime_down", R + "wait",
         "Straja is not running."),
    "Registrul de Amenzi este disponibil doar Străjerilor activi.":
        ("straja.cmd.fine_register_rank", R + "reception",
         "The Fine Register is only available to active guards."),
    "Acțiunea NPC a expirat sau nu este validă.":
        ("straja.cmd.action_expired", R + "retry",
         "The NPC action expired or is not valid."),
    "Doar Comisaru' poate citi inbox-ul.":
        ("straja.cmd.inbox_comisar", R + "ask_comisar",
         "Only the Commissioner can read the inbox."),

    # ── adapter/in/event/StrajaEvents.java ──────────────────────────────
    "Documentul arhivei nu are o referință validă.":
        ("straja.item.archive_ref", R + "archivist",
         "The archive document has no valid reference."),
    "Buletinul nu are o referință validă.":
        ("straja.item.id_ref", R + "archivist",
         "The ID card has no valid reference."),
    "Documentul oficial nu are o referință validă.":
        ("straja.item.doc_ref", R + "archivist",
         "The official document has no valid reference."),
    "Instrumentul nu are o referință validă sau nu îți aparține.":
        ("straja.item.tool_ref", R + "retry",
         "The tool has no valid reference or is not yours."),
    "Celula este protejată. Doar adminii o pot modifica sau deschide.":
        ("straja.cell.protected", R + "ask_comisar",
         "The cell is protected. Only admins can modify or open it."),

    # ── adapter/in/form/FormSubmissionRouter.java ───────────────────────
    "Dosarul nu există sau nu are probe vizibile.":
        ("straja.form.dossier_evidence", R + "archivist",
         "The case does not exist or has no visible evidence."),
    "Nu există istoric sau subiectul nu este online.":
        ("straja.form.subject_history", R + "retry",
         "There is no history or the subject is not online."),
    "Slotul și cantitatea probei trebuie să fie numere.":
        ("straja.form.slot_not_numbers", R + "fix_retry",
         "The slot and evidence count must be numbers."),
    "Modificarea reputației trebuie să fie un număr.":
        ("straja.form.reputation_not_number", R + "fix_retry",
         "The reputation change must be a number."),

    # ── adapter/in/npc/NpcRoles.java ────────────────────────────────────
    "Armurierul nu are articole pentru tine acum.":
        ("straja.armorer.no_items", R + "wait",
         "The Armorer has no items for you right now."),
    "Fișa V2 nu este încă proiectată. Reautentifică-te sau vorbește cu Recepția.":
        ("straja.npc.v2_unprojected", R + "reception",
         "Your V2 record is not projected yet."),
    "Nu există o cerere de promovare V2 deschisă pentru tine.":
        ("straja.npc.no_promotion", R + "ask_comisar",
         "There is no open V2 promotion request for you."),
    "Nu ai încă o profesie V2 în fișa de personal. Cere o numire profesională la Comisar.":
        ("straja.npc.no_profession", R + "ask_comisar",
         "You have no V2 profession on your personnel record yet."),
    "Profesia %s nu are încă un generator activ.":
        ("straja.npc.profession_inactive", R + "ask_comisar",
         "The %s profession has no active generator yet."),
    "Nu există momentan o ofertă profesională: %s":
        ("straja.npc.no_offer", R + "wait",
         "There is currently no profession offer: %s"),
    "Cererea de avansare a fost refuzată: %s":
        ("straja.npc.promotion_refused", R + "instructor",
         "The promotion request was refused: %s"),
    "Înscrierea a fost refuzată: %s":
        ("straja.npc.enroll_refused", R + "reception",
         "The enrollment was refused: %s"),
    "[Straja] Acțiunea NPC nu mai este disponibilă: dosarul sau cererea s-a schimbat.":
        ("straja.npc.action_stale", R + "retry",
         "[Straja] The NPC action is no longer available: the case or request has changed."),
    "[Straja] Întrebarea FAQ nu mai este disponibilă.":
        ("straja.npc.faq_gone", R + "retry",
         "[Straja] That FAQ question is no longer available."),
    "[FAQ] Ramura nu mai este disponibilă pentru statutul tău.":
        ("straja.npc.faq_branch_gone", R + "faq",
         "[FAQ] That branch is no longer available for your status."),
    "[Straja] Acest meniu nu mai este disponibil.":
        ("straja.npc.menu_gone", R + "retry",
         "[Straja] This menu is no longer available."),
    "[Straja] NPC-ul nu mai este înregistrat.":
        ("straja.npc.unregistered", R + "faq",
         "[Straja] That NPC is no longer registered."),
    "Nu există incidente active pentru rosterul tău.":
        ("straja.npc.no_incidents", R + "wait",
         "There are no active incidents for your roster."),
    "Rosterul de serviciu este gol sau nu ai acces.":
        ("straja.npc.roster_empty", R + "duty",
         "The duty roster is empty or you have no access."),
    "Nu există BOLO-uri active sau nu ai acces.":
        ("straja.npc.no_bolos", R + "wait",
         "There are no active BOLOs or you have no access."),
    "Nu există un dosar de arest pentru tine.":
        ("straja.npc.no_arrest_case", R + "faq",
         "There is no arrest case for you."),
    "Nu există dosare de arest vizibile pentru tine.":
        ("straja.npc.no_arrest_cases", R + "faq",
         "There are no arrest cases visible to you."),
    "Nu există probe vizibile pentru tine.":
        ("straja.npc.no_evidence", R + "archivist",
         "There is no evidence visible to you."),
    "[Straja] Acțiunea nu mai este disponibilă; starea misiunii s-a schimbat.":
        ("straja.npc.action_stale_mission", R + "retry",
         "[Straja] The action is no longer available; the mission state changed."),
    "[Straja] Acțiunea nu mai este disponibilă; starea dosarului s-a schimbat.":
        ("straja.npc.action_stale_dossier", R + "retry",
         "[Straja] The action is no longer available; the case state changed."),
    "[Straja] Acțiunea nu mai este disponibilă; starea amenzii s-a schimbat.":
        ("straja.npc.action_stale_fine", R + "retry",
         "[Straja] The action is no longer available; the fine state changed."),
    "[Straja] Acțiunea nu mai este disponibilă; starea arhivei s-a schimbat.":
        ("straja.npc.action_stale_archive", R + "retry",
         "[Straja] The action is no longer available; the archive state changed."),
    "[Straja] Doar membrii Străjii depun rapoarte de activitate.":
        ("straja.npc.reports_members_only", R + "reception",
         "[Straja] Only Straja members file activity reports."),
    "[Straja] Doar membrii Străjii pot cere audiență la Comisar.":
        ("straja.npc.audience_members_only", R + "reception",
         "[Straja] Only Straja members can request an audience with the Commissioner."),
    "[Straja] Doar Comisarul poate folosi interfața administrativă.":
        ("straja.npc.admin_comisar_only", R + "ask_comisar",
         "[Straja] Only the Commissioner can use the administrative interface."),

    # ── application/service/AdminService.java ───────────────────────────
    "[Straja] Nu există fișe de personal.":
        ("straja.admin.no_sheets", R + "reception",
         "[Straja] There are no personnel records."),
    "Jucătorul nu este online sau nu există.":
        ("straja.common.player_offline", R + "retry",
         "The player is not online or does not exist."),
    "Autorizarea V2 nu este disponibilă.":
        ("straja.admin.auth_unavailable", R + "wait",
         "V2 authorization is not available."),
    "Autorizarea V2 a fost refuzată: %s":
        ("straja.admin.auth_refused", R + "retry",
         "V2 authorization was refused: %s"),
    "[Straja] Acțiunea nu mai este disponibilă pentru acest membru.":
        ("straja.admin.action_stale", R + "retry",
         "[Straja] The action is no longer available for this member."),
    "[Straja] Membrul nu este online — acțiunile de personal cer prezența.":
        ("straja.admin.member_offline", R + "wait",
         "[Straja] The member is not online — personnel actions require presence."),
    "Personnel V2 nu există.":
        ("straja.admin.personnel_missing", R + "retry",
         "V2 Personnel does not exist."),

    # ── application/service/ArchiveService.java ─────────────────────────
    "Doar Arhivista autorizată sau Comisaru' poate crea dosare.":
        ("straja.archive.create_dossier_rank", R + "archivist",
         "Only the authorized Archivist or the Commissioner can create dossiers."),
    "Titlul dosarului trebuie completat (max %s caractere).":
        ("straja.archive.title_required", R + "fix_retry",
         "The dossier title is required (max %s characters)."),
    "Dosarul nu există sau nu ai acces.":
        ("straja.archive.dossier_missing", R + "retry",
         "The dossier does not exist or you have no access."),
    "Nu poți modifica acest dosar.":
        ("straja.archive.dossier_readonly", R + "archivist",
         "You cannot modify this dossier."),
    "Nu există dosare accesibile.":
        ("straja.archive.no_dossiers", R + "archivist",
         "There are no accessible dossiers."),
    "Ținta trebuie să fie un alt jucător online.":
        ("straja.archive.target_offline", R + "fix_retry",
         "The target must be another online player."),
    "Destinatarul nu are loc pentru dosar.":
        ("straja.archive.target_full_dossier", R + "fix_retry",
         "The recipient has no room for the dossier."),
    "Dosarul nu a putut fi livrat; niciun acces nu a fost acordat.":
        ("straja.archive.dossier_undelivered", R + "retry",
         "The dossier could not be delivered; no access was granted."),
    "Doar Arhivista autorizată sau Comisaru' poate crea foi.":
        ("straja.archive.create_sheet_rank", R + "archivist",
         "Only the authorized Archivist or the Commissioner can create sheets."),
    "Doar Arhivista autorizată poate edita foaia.":
        ("straja.archive.edit_rank", R + "archivist",
         "Only the authorized Archivist can edit the sheet."),
    "Foaia nu există, dosarul este închis sau nu ai drept de scriere.":
        ("straja.archive.sheet_missing", R + "retry",
         "The sheet does not exist, the dossier is closed, or you lack write access."),
    "Foaia nu mai este editabilă după trimiterea la semnătură.":
        ("straja.archive.sheet_locked", R + "archivist",
         "The sheet is no longer editable once sent for signature."),
    "Conținutul depășește limita de %s caractere.":
        ("straja.archive.content_too_long", R + "fix_retry",
         "The content exceeds the %s character limit."),
    "Doar Arhivista autorizată poate modifica destinatarii.":
        ("straja.archive.recipients_rank", R + "archivist",
         "Only the authorized Archivist can change the recipients."),
    "Destinatarii pot fi schimbați doar pe un draft dintr-un dosar deschis.":
        ("straja.archive.recipients_draft", R + "fix_retry",
         "Recipients can only be changed on a draft in an open dossier."),
    "Doar Arhivista autorizată poate trimite foi la semnătură.":
        ("straja.archive.submit_rank", R + "archivist",
         "Only the authorized Archivist can submit sheets for signature."),
    "Doar un draft dintr-un dosar deschis poate fi trimis.":
        ("straja.archive.submit_draft", R + "fix_retry",
         "Only a draft in an open dossier can be submitted."),
    "Doar un Inspector sau Comisaru' poate semna acte.":
        ("straja.archive.sign_rank", R + "ask_comisar",
         "Only an Inspector or the Commissioner can sign acts."),
    "Foaia nu este pregătită pentru semnătură sau nu ai acces.":
        ("straja.archive.sign_not_ready", R + "archivist",
         "The sheet is not ready for signature or you have no access."),
    "Doar Inspectorul, Comisaru' sau Arhivista pot revoca un act.":
        ("straja.archive.revoke_rank", R + "ask_comisar",
         "Only the Inspector, the Commissioner, or the Archivist can revoke an act."),
    "Foaia nu există.":
        ("straja.archive.sheet_gone", R + "retry",
         "The sheet does not exist."),
    "Doar Arhivista autorizată, Inspectorul sau Comisaru' pot genera copii.":
        ("straja.archive.copy_rank", R + "archivist",
         "Only the authorized Archivist, the Inspector, or the Commissioner can generate copies."),
    "Numărul de copii este invalid.":
        ("straja.archive.copy_count", R + "fix_retry",
         "The number of copies is invalid."),
    "Nu ai suficiente Foi Indigo: ai nevoie de %s.":
        ("straja.archive.indigo_short", R + "archivist",
         "You lack enough Indigo Sheets: you need %s."),
    "Operația oprită: inventar plin pentru %s. Nicio Indigo nu a fost consumată.":
        ("straja.archive.copy_inv_full", R + "retry",
         "Operation stopped: inventory full for %s. No Indigo was consumed."),
    "Operația nu a putut consuma Foi Indigo; nimic nu a fost livrat.":
        ("straja.archive.indigo_consume_fail", R + "retry",
         "The Indigo Sheets could not be consumed; nothing was delivered."),
    "Doar Arhivista autorizată, Inspectorul sau Comisaru' pot împacheta acte.":
        ("straja.archive.pack_rank", R + "archivist",
         "Only the authorized Archivist, the Inspector, or the Commissioner can package acts."),
    "Doar un act semnat poate fi împachetat.":
        ("straja.archive.pack_unsigned", R + "fix_retry",
         "Only a signed act can be packaged."),
    "Destinatarul nu are loc pentru plic.":
        ("straja.archive.target_full_env", R + "fix_retry",
         "The recipient has no room for the envelope."),
    "Plicul nu a putut fi consumat; operația a fost anulată.":
        ("straja.archive.env_consume_fail", R + "retry",
         "The envelope could not be consumed; the operation was cancelled."),
    "Actul nu există, nu este semnat sau nu ai acces.":
        ("straja.archive.act_missing", R + "archivist",
         "The act does not exist, is not signed, or you have no access."),
    "Destinatarul nu are loc pentru document.":
        ("straja.archive.target_full_doc", R + "fix_retry",
         "The recipient has no room for the document."),
    "Documentul nu a putut fi livrat; niciun acces nu a fost acordat.":
        ("straja.archive.doc_undelivered", R + "retry",
         "The document could not be delivered; no access was granted."),
    "Doar Arhivista autorizată poate cataloga dosare.":
        ("straja.archive.catalog_rank", R + "archivist",
         "Only the authorized Archivist can catalogue dossiers."),
    "Dosarul nu are înregistrări de catalog.":
        ("straja.archive.no_catalog", R + "archivist",
         "The dossier has no catalogue entries."),
    "Dosarul nu există, nu este deschis sau nu ai drept de scriere.":
        ("straja.archive.dossier_closed", R + "retry",
         "The dossier does not exist, is not open, or you lack write access."),
    "Titlul foii trebuie completat (max %s caractere).":
        ("straja.archive.sheet_title_required", R + "fix_retry",
         "The sheet title is required (max %s characters)."),
    "Foaia nu există sau nu ai acces.":
        ("straja.archive.sheet_no_access", R + "retry",
         "The sheet does not exist or you have no access."),
    "Motivul semnăturii depășește 240 de caractere.":
        ("straja.archive.reason_too_long", R + "fix_retry",
         "The signature reason exceeds 240 characters."),

    # ── application/service/ArmoryService.java ──────────────────────────
    "Armurierul deservește doar străjeri activi.":
        ("straja.armory.active_only", R + "duty",
         "The Armorer only serves active guards."),
    "Armurierul nu are acest articol pentru rangul tău.":
        ("straja.armory.item_rank", R + "faq",
         "The Armorer does not have this item for your rank."),
    "Moneda externă nu este configurată; armurierul nu poate încasa.":
        ("straja.armory.no_currency", R + "ask_comisar",
         "The external currency is not configured; the Armorer cannot collect payment."),
    "Armurierul nu are această rezervă pentru rangul tău.":
        ("straja.armory.stock_rank", R + "faq",
         "The Armorer does not have this stock for your rank."),
    "Inventarul este plin; punctele nu au fost cheltuite.":
        ("straja.armory.inv_full", R + "retry",
         "Your inventory is full; no points were spent."),
    "Articolul nu a putut fi predat; punctele au fost restituite.":
        ("straja.armory.delivery_fail", R + "retry",
         "The item could not be delivered; the points were refunded."),

    # ── application/service/ArrestRecordService.java ────────────────────
    "Nu există o sentință activă pentru această predare.":
        ("straja.arrest.no_sentence", R + "jailer",
         "There is no active sentence for this handover."),

    # ── application/service/AudienceService.java ────────────────────────
    "Doar membrii Străjii pot cere audiență la Comisar.":
        ("straja.audience.members_only", R + "reception",
         "Only Straja members can request an audience with the Commissioner."),
    "Doar membrii Străjii au cereri de audiență.":
        ("straja.audience.list_members", R + "reception",
         "Only Straja members have audience requests."),
    "Nu ai nicio cerere de audiență.":
        ("straja.audience.none_mine", R + "faq",
         "You have no audience request."),
    "Doar Comisaru' vede cererile de audiență.":
        ("straja.audience.list_comisar", R + "ask_comisar",
         "Only the Commissioner sees the audience requests."),
    "Nu există cereri de audiență în așteptare.":
        ("straja.audience.none_pending", R + "wait",
         "There are no pending audience requests."),
    "Doar Comisaru' decide cererile de audiență.":
        ("straja.audience.decide_comisar", R + "ask_comisar",
         "Only the Commissioner decides audience requests."),
    "Cererea %s nu așteaptă o decizie.":
        ("straja.audience.not_pending", R + "retry",
         "Request %s is not awaiting a decision."),

    # ── application/service/ComplaintService.java ───────────────────────
    "Acuzatul nu poate fi identificat printr-un cont conectat sau prin istoricul UUID persistent.":
        ("straja.complaint.accused_unknown", R + "fix_retry",
         "The accused cannot be identified by a connected account or persistent UUID history."),
    "Nu poți depune o plângere împotriva ta.":
        ("straja.complaint.self", R + "fix_retry",
         "You cannot file a complaint against yourself."),
    "Doar Sergentul sau un rang superior poate vedea dosarele.":
        ("straja.complaint.view_rank", R + "instructor",
         "Only the Sergeant or a higher rank can view cases."),
    "Doar Sergentul sau un rang superior poate prelua plângeri.":
        ("straja.complaint.claim_rank", R + "instructor",
         "Only the Sergeant or a higher rank can take complaints."),
    "Doar Sergentul sau un rang superior poate mobiliza participanți.":
        ("straja.complaint.mobilize_rank", R + "instructor",
         "Only the Sergeant or a higher rank can mobilize participants."),
    "Dosarul nu îți este atribuit.":
        ("straja.complaint.not_assigned", R + "retry",
         "The case is not assigned to you."),
    "Participantul trebuie să fie o gardă activă.":
        ("straja.complaint.participant_inactive", R + "duty",
         "The participant must be an active guard."),
    "Poți mobiliza doar Stagiari și Străjeri.":
        ("straja.complaint.mobilize_ranks", R + "fix_retry",
         "You can only mobilize Trainees and Guards."),
    "Doar o gardă activă poate participa la o investigație.":
        ("straja.complaint.join_inactive", R + "duty",
         "Only an active guard can join an investigation."),
    "Nu ai o mobilizare activă pentru acest dosar.":
        ("straja.complaint.no_mobilization", R + "retry",
         "You have no active mobilization for this case."),
    "Nu ești participant activ pe acest dosar.":
        ("straja.complaint.not_participant", R + "retry",
         "You are not an active participant on this case."),
    "Acest dosar nu îți aparține.":
        ("straja.complaint.not_yours", R + "retry",
         "This case does not belong to you."),
    "Dosarul nu are încă un raport final.":
        ("straja.complaint.no_report", R + "instructor",
         "The case has no final report yet."),
    "Doar Inspectorul sau Comisaru' poate verifica și plăti dosare.":
        ("straja.complaint.verify_rank", R + "ask_comisar",
         "Only the Inspector or the Commissioner can verify and pay cases."),
    "Dosarul nu este pregătit pentru verificare.":
        ("straja.complaint.not_ready", R + "retry",
         "The case is not ready for verification."),
    "Nu îți poți verifica propriul dosar.":
        ("straja.complaint.self_verify", R + "fix_retry",
         "You cannot verify your own case."),
    "Plângerea nu este disponibilă pentru preluare.":
        ("straja.complaint.claim_gone", R + "retry",
         "The complaint is not available to be taken."),
    "Doar Sergentul sau un rang superior poate depune raportul de investigație.":
        ("straja.complaint.report_rank", R + "instructor",
         "Only the Sergeant or a higher rank can file the investigation report."),
    "Raportul trebuie completat și să aibă maximum %s caractere.":
        ("straja.complaint.report_required", R + "fix_retry",
         "The report is required and must be at most %s characters."),
    "Dosarul nu îți este atribuit sau nu mai acceptă raport.":
        ("straja.complaint.report_closed", R + "retry",
         "The case is not assigned to you or no longer accepts a report."),
    "Reward-ul trebuie să fie un întreg între 0 și %s.":
        ("straja.complaint.reward_invalid", R + "fix_retry",
         "The reward must be a whole number between 0 and %s."),

    # ── application/service/CustodyService.java ─────────────────────────
    "Cererea nu există, a expirat sau nu îți aparține.":
        ("straja.custody.request_stale", R + "retry",
         "The request does not exist, has expired, or is not yours."),
    "Cererea de încătușare nu mai este validă: emitentul nu mai are autoritate sau Cătușe.":
        ("straja.custody.cuff_invalid", R + "retry",
         "The cuffing request is no longer valid: the issuer no longer has authority or Cuffs."),
    "Cererea de încătușare a fost anulată: nu mai ai autoritate sau Cătușe.":
        ("straja.custody.cuff_cancelled", R + "retry",
         "The cuffing request was cancelled: you no longer have authority or Cuffs."),
    "Încătușarea a fost refuzată: starea de custodie nu este validă.":
        ("straja.custody.cuff_state", R + "retry",
         "Cuffing was refused: the custody state is not valid."),
    "Cererea de predare nu mai este validă: gardianul nu este disponibil sau nu mai are Cătușe.":
        ("straja.custody.surrender_invalid", R + "retry",
         "The surrender request is no longer valid: the guard is unavailable or no longer has Cuffs."),
    "Cererea de predare a expirat: trebuie să fii activ și să ai Cătușele în inventar.":
        ("straja.custody.surrender_expired", R + "duty",
         "The surrender request expired: you must be on duty with Cuffs in your inventory."),
    "Obiectul din mână nu încape în inventar; îl primești înapoi automat când eliberezi un slot.":
        ("straja.custody.hand_full", R + "wait",
         "The item in hand does not fit the inventory; you get it back automatically once you free a slot."),
    "Nu poți cere încătușarea fără Cătușe în inventar.":
        ("straja.custody.no_cuffs_request", R + "jailer",
         "You cannot request cuffing without Cuffs in your inventory."),
    "Nu poți cere predarea fără Cătușe în inventar.":
        ("straja.custody.no_cuffs_surrender", R + "jailer",
         "You cannot request a surrender without Cuffs in your inventory."),
    "Nu poți aplica încătușarea fără Cătușe în inventar.":
        ("straja.custody.no_cuffs_apply", R + "jailer",
         "You cannot apply the cuffing without Cuffs in your inventory."),
    "Nu ai autoritatea necesară pentru a schimba modul cătușelor.":
        ("straja.custody.mode_auth", R + "duty",
         "You lack the authority to change the cuffs mode."),
    "Ținta trebuie să fie în apropiere.":
        ("straja.custody.target_far", R + "retry",
         "The target must be nearby."),
    "%s nu este încătușat.":
        ("straja.custody.not_cuffed", R + "fix_retry",
         "%s is not cuffed."),
    "Starea canonică a cătușelor nu este validă.":
        ("straja.custody.cuff_state_invalid", R + "retry",
         "The canonical cuffs state is not valid."),
    "Doar Comisaru' poate folosi eliberarea de urgență.":
        ("straja.custody.emergency_comisar", R + "ask_comisar",
         "Only the Commissioner can use the emergency release."),
    "Eliberarea a fost refuzată: starea de custodie nu este validă.":
        ("straja.custody.release_state", R + "retry",
         "The release was refused: the custody state is not valid."),
    "Cătușele nu au putut fi predate. Eliberează un slot și încearcă din nou.":
        ("straja.custody.cuffs_undelivered", R + "retry",
         "The Cuffs could not be delivered. Free a slot and try again."),
    "Cătușele pot fi rupte doar de alt jucător.":
        ("straja.custody.break_self", R + "fix_retry",
         "Cuffs can only be broken by another player."),
    "Foarfeca poate tăia frânghia, nu poate desface cătușele.":
        ("straja.custody.scissors_wrong", R + "fix_retry",
         "The Scissors can cut the rope but cannot undo the cuffs."),
    "Cătușele și frânghia nu pot fi eliberate simultan.":
        ("straja.custody.release_both", R + "fix_retry",
         "The cuffs and the rope cannot be released at the same time."),
    "Cheia poate desface cătușele, dar nu poate tăia frânghia.":
        ("straja.custody.key_wrong", R + "fix_retry",
         "The Key can undo the cuffs but cannot cut the rope."),
    "Cheia nu a putut fi consumată; eliberarea a fost anulată.":
        ("straja.custody.key_consume", R + "retry",
         "The Key could not be consumed; the release was cancelled."),
    "Nu te poți lega singur.":
        ("straja.custody.tie_self", R + "fix_retry",
         "You cannot tie yourself up."),
    "Trebuie să fii lângă țintă pentru a folosi frânghia.":
        ("straja.custody.rope_far", R + "retry",
         "You must be next to the target to use the rope."),
    "Frânghia poate fi folosită doar pe o țintă liberă, vie sau leșinată.":
        ("straja.custody.rope_state", R + "fix_retry",
         "The rope can only be used on a free, living or downed target."),
    "Legarea nu a putut întrerupe resuscitarea.":
        ("straja.custody.tie_revive_fail", R + "retry",
         "The tying could not interrupt the resuscitation."),
    "Legarea a fost refuzată: %s":
        ("straja.custody.tie_refused", R + "retry",
         "The tying was refused: %s"),
    "Frânghia nu a putut fi consumată; legarea a fost anulată.":
        ("straja.custody.rope_consume", R + "retry",
         "The rope could not be consumed; the tying was cancelled."),
    "Nu îți poți pune singur Sacul de Captiv.":
        ("straja.custody.sack_self", R + "fix_retry",
         "You cannot put the Captive Sack on yourself."),
    "Trebuie să fii lângă țintă pentru a pune sacul.":
        ("straja.custody.sack_far", R + "retry",
         "You must be next to the target to put the sack on."),
    "Sacul se poate pune doar unui suspect încătușat sau legat.":
        ("straja.custody.sack_state", R + "fix_retry",
         "The sack can only be put on a cuffed or tied suspect."),
    "Sacul a fost refuzat: %s":
        ("straja.custody.sack_refused", R + "retry",
         "The sack was refused: %s"),
    "Nu ai Sacul de Captiv pe cap.":
        ("straja.custody.no_sack", R + "fix_retry",
         "You are not wearing a Captive Sack."),
    "Ești inconștient și nu poți da jos sacul încă.":
        ("straja.custody.sack_downed", R + "wait",
         "You are unconscious and cannot remove the sack yet."),
    "Nu te poți transporta singur.":
        ("straja.custody.carry_self", R + "fix_retry",
         "You cannot carry yourself."),
    "Transportul nu poate fi suprapus peste alt transport.":
        ("straja.custody.carry_overlap", R + "wait",
         "A carry cannot overlap another carry."),
    "Poți transporta doar un jucător inconștient sau imobilizat.":
        ("straja.custody.carry_state", R + "fix_retry",
         "You can only carry an unconscious or immobilized player."),
    "Transportul nu poate începe: %s":
        ("straja.custody.carry_refused", R + "retry",
         "The carry cannot start: %s"),
    "Transportul nu a putut fi sincronizat cu serverul.":
        ("straja.custody.carry_sync", R + "retry",
         "The carry could not be synced with the server."),
    "Nu ești persoana care transportă această țintă.":
        ("straja.custody.not_carrier", R + "fix_retry",
         "You are not the one carrying this target."),
    "Nu te poți resuscita singur.":
        ("straja.custody.revive_self", R + "fix_retry",
         "You cannot resuscitate yourself."),
    "Trebuie să fii lângă persoana leșinată pentru a o resuscita.":
        ("straja.custody.revive_far", R + "retry",
         "You must be next to the downed person to resuscitate them."),
    "Această persoană nu poate fi resuscitată acum.":
        ("straja.custody.revive_state", R + "wait",
         "This person cannot be resuscitated right now."),
    "Persoana leșinată nu mai este la locul în care a căzut.":
        ("straja.custody.revive_moved", R + "retry",
         "The downed person is no longer where they fell."),
    "Resuscitarea nu poate începe: %s":
        ("straja.custody.revive_refused", R + "retry",
         "Resuscitation cannot start: %s"),
    "%s poate fi folosit doar de un străjer activ.":
        ("straja.custody.item_active_only", R + "duty",
         "%s can only be used by an active guard."),
    "%s este deja încătușat; %s nu mai poate porni un al doilea flow.":
        ("straja.custody.already_cuffed", R + "fix_retry",
         "%s is already cuffed; %s cannot start a second flow."),
    "%s este deja legat; %s nu mai poate porni un al doilea flow.":
        ("straja.custody.already_tied", R + "fix_retry",
         "%s is already tied; %s cannot start a second flow."),
    "%s este deja inconștient. Nu mai poate primi lovituri.":
        ("straja.custody.already_downed", R + "wait",
         "%s is already unconscious. They cannot take more hits."),
    "Lovitura a fost un knockout, dar nu ai Cătușe în inventar; ținta rămâne inconștientă.":
        ("straja.custody.ko_no_cuffs", R + "jailer",
         "The hit was a knockout, but you have no Cuffs in your inventory; the target stays unconscious."),
    "Ești inconștient. Nu poți ataca, interacționa, deschide inventarul sau folosi obiecte până te trezești.":
        ("straja.custody.downed_blocked", R + "wait",
         "You are unconscious. You cannot attack, interact, open your inventory or use items until you wake up."),
    "Nu ești încătușat.":
        ("straja.custody.self_not_cuffed", R + "fix_retry",
         "You are not cuffed."),
    "Nu ești inconștient.":
        ("straja.custody.self_not_downed", R + "fix_retry",
         "You are not unconscious."),
    "Cătușele au fost eliberate: emitentul nu mai este eligibil.":
        ("straja.custody.released_issuer", R + "retry",
         "The cuffs were released: the issuer is no longer eligible."),

    # ── application/service/EmergencyService.java ───────────────────────
    "Nu există o urgență activă.":
        ("straja.emergency.none", R + "ask_comisar",
         "There is no active emergency."),
    "Starea de urgență nu este activă.":
        ("straja.emergency.inactive", R + "ask_comisar",
         "The emergency state is not active."),
    "[Straja] Starea de urgență nu este activă.":
        ("straja.emergency.inactive_tagged", R + "ask_comisar",
         "[Straja] The emergency state is not active."),
    "Doar Comisarul sau un operator poate gestiona starea de urgență.":
        ("straja.emergency.comisar_only", R + "ask_comisar",
         "Only the Commissioner or an operator can manage the emergency state."),

    # ── application/service/EquipmentService.java ───────────────────────
    "Kitul nu încape în inventar. Eliberează sloturi și încearcă din nou.":
        ("straja.equipment.kit_full", R + "retry",
         "The kit does not fit your inventory. Free some slots and try again."),
    "Kitul nu a putut fi predat complet.":
        ("straja.equipment.kit_partial", R + "retry",
         "The kit could not be delivered completely."),

    # ── application/service/EvidenceService.java ────────────────────────
    "Percheziția nu mai este validă. Deschide-o din nou.":
        ("straja.evidence.search_stale", R + "retry",
         "The search is no longer valid. Open it again."),
    "Ținta s-a îndepărtat sau nu mai poate fi percheziționată.":
        ("straja.evidence.search_moved", R + "retry",
         "The target moved away or can no longer be searched."),
    "Obiectul sau cantitatea s-au schimbat; confiscarea a fost refuzată.":
        ("straja.evidence.stack_changed", R + "retry",
         "The item or its count changed; the seizure was refused."),
    "Obiectul s-a schimbat; confiscarea a fost refuzată.":
        ("straja.evidence.item_changed", R + "retry",
         "The item changed; the seizure was refused."),
    "Nu ai loc pentru punga de probe; confiscarea a fost refuzată.":
        ("straja.evidence.bag_full", R + "retry",
         "You have no room for the evidence bag; the seizure was refused."),
    "Ținta nu are loc pentru dovada de confiscare; confiscarea a fost refuzată.":
        ("straja.evidence.target_full", R + "retry",
         "The target has no room for the seizure receipt; the seizure was refused."),
    "Stiva nu a putut fi preluată integral; nimic nu a fost înregistrat ca probă.":
        ("straja.evidence.take_fail", R + "retry",
         "The stack could not be taken in full; nothing was recorded as evidence."),
    "Un document fizic nu a putut fi livrat; va fi reîncercat la reconectare.":
        ("straja.evidence.doc_deferred", R + "wait",
         "A physical document could not be delivered; it will be retried on reconnect."),
    "Proba nu mai este în custodia unui străjer.":
        ("straja.evidence.not_in_custody", R + "archivist",
         "The evidence is no longer in a guard's custody."),
    "Doar personalul autorizat poate depune probe.":
        ("straja.evidence.deposit_rank", R + "archivist",
         "Only authorized staff can deposit evidence."),
    "Proba nu poate fi restituită.":
        ("straja.evidence.no_return", R + "archivist",
         "The evidence cannot be returned."),
    "Doar personalul autorizat poate restitui probe.":
        ("straja.evidence.return_rank", R + "archivist",
         "Only authorized staff can return evidence."),
    "Proprietarul probei nu este online.":
        ("straja.evidence.owner_offline", R + "wait",
         "The evidence owner is not online."),
    "Proba nu mai poate fi transferată.":
        ("straja.evidence.no_transfer", R + "archivist",
         "The evidence can no longer be transferred."),
    "Proba nu mai poate fi distrusă.":
        ("straja.evidence.no_destroy", R + "ask_comisar",
         "The evidence can no longer be destroyed."),
    "Doar Comisaru' poate aproba distrugerea unei probe.":
        ("straja.evidence.destroy_rank", R + "ask_comisar",
         "Only the Commissioner can approve destroying evidence."),

    # ── application/service/FineService.java ────────────────────────────
    "Cetățeanul trebuie să fie online pentru întocmirea amenzii.":
        ("straja.fine.citizen_offline", R + "wait",
         "The citizen must be online for the fine to be drawn up."),
    "Nu poți amenda Comisaru' sau un gardian de același rang/superior.":
        ("straja.fine.protected_target", R + "fix_retry",
         "You cannot fine the Commissioner or a guard of the same/higher rank."),
    "Suma trebuie să fie una standard: %s monede.":
        ("straja.fine.amount_standard", R + "fix_retry",
         "The amount must be a standard one: %s coins."),
    "Legea trebuie completată și să aibă maximum %s caractere.":
        ("straja.fine.law_required", R + "fix_retry",
         "The law is required and must be at most %s characters."),
    "Descrierea trebuie completată și să aibă maximum %s caractere.":
        ("straja.fine.desc_required", R + "fix_retry",
         "The description is required and must be at most %s characters."),
    "Nu ai un formular de amendă scris. Completează Registrul de Amenzi înainte să-l prezinți țintei.":
        ("straja.fine.no_form", R + "fix_retry",
         "You have no written fine form. Fill in the Fine Register before presenting it to the target."),
    "Aceasta nu este ținta înscrisă în formular.":
        ("straja.fine.wrong_target", R + "fix_retry",
         "This is not the target written on the form."),
    "Amenda este refuzată de matricea de autoritate.":
        ("straja.fine.auth_matrix", R + "ask_comisar",
         "The fine is refused by the authority matrix."),
    "Înștiințarea existentă încă nu poate fi predată.":
        ("straja.fine.notice_pending", R + "wait",
         "The existing notice cannot be delivered yet."),
    "Amenda %s există deja; nu a fost creat un duplicat.":
        ("straja.fine.duplicate", R + "fix_retry",
         "Fine %s already exists; no duplicate was created."),
    "Înștiințarea nu a putut fi predată; amenda a fost blocată pentru verificare.":
        ("straja.fine.notice_fail", R + "ask_comisar",
         "The notice could not be delivered; the fine was blocked for review."),
    "Amenda nu există sau este deja închisă.":
        ("straja.fine.missing", R + "reception",
         "The fine does not exist or is already closed."),
    "Această amendă nu îți aparține.":
        ("straja.fine.not_yours", R + "reception",
         "This fine does not belong to you."),
    "Nu ai combinația exactă de monede pentru această amendă.":
        ("straja.fine.exact_coins", R + "reception",
         "You do not have the exact coin combination for this fine."),
    "Doar Comisaru' poate recupera o plată aflată în verificare.":
        ("straja.fine.recover_comisar", R + "ask_comisar",
         "Only the Commissioner can recover a payment under review."),
    "Nu există o plată de amendă în verificare pentru acest ID.":
        ("straja.fine.no_pending_payment", R + "retry",
         "There is no fine payment under review for this ID."),
    "Amenda nu mai poate fi contestată: trebuie să fie neachitată și neescaladată.":
        ("straja.fine.appeal_state", R + "reception",
         "The fine can no longer be appealed: it must be unpaid and un-escalated."),
    "Doar cetățeanul amendat poate depune contestația.":
        ("straja.fine.appeal_owner", R + "reception",
         "Only the fined citizen can file the appeal."),
    "Doar Inspectorul sau Comisaru' poate decide contestații.":
        ("straja.fine.appeal_judge", R + "ask_comisar",
         "Only the Inspector or the Commissioner can decide appeals."),
    "Contestația nu există sau nu mai este în așteptare.":
        ("straja.fine.appeal_gone", R + "retry",
         "The appeal does not exist or is no longer pending."),
    "Nu îți poți judeca propria amendă.":
        ("straja.fine.appeal_self", R + "fix_retry",
         "You cannot judge your own fine."),
    "Motivul deciziei trebuie să aibă maximum %s caractere.":
        ("straja.fine.reason_length", R + "fix_retry",
         "The decision reason must be at most %s characters."),
    "Reducerea trebuie să fie un tarif standard mai mic decât amenda inițială.":
        ("straja.fine.reduction_invalid", R + "fix_retry",
         "The reduction must be a standard tariff lower than the original fine."),
    "Doar Straja poate vedea misiunile de amenzi.":
        ("straja.fine.tasks_rank", R + "reception",
         "Only Straja can view the fine missions."),
    "Nu există misiuni de amenzi deschise.":
        ("straja.fine.no_tasks", R + "wait",
         "There are no open fine missions."),
    "Doar un Străjer activ poate prelua misiunea.":
        ("straja.fine.task_claim_rank", R + "duty",
         "Only an active Guard can take the mission."),
    "Misiunea nu există sau este deja închisă.":
        ("straja.fine.task_missing", R + "retry",
         "The mission does not exist or is already closed."),
    "Doar un Străjer activ poate închide misiunea.":
        ("straja.fine.task_close_rank", R + "duty",
         "Only an active Guard can close the mission."),
    "Misiunea nu există sau nu îți este atribuită.":
        ("straja.fine.task_not_assigned", R + "retry",
         "The mission does not exist or is not assigned to you."),
    "Ținta trebuie să fie online și adusă la recepționistă.":
        ("straja.fine.target_not_present", R + "retry",
         "The target must be online and brought to the receptionist."),
    "Ținta trebuie adusă la recepționistă.":
        ("straja.fine.target_away", R + "retry",
         "The target must be brought to the receptionist."),
    "Amenda asociată lipsește.":
        ("straja.fine.linked_missing", R + "ask_comisar",
         "The associated fine is missing."),
    "Amenda nu mai este eligibilă pentru recuperare.":
        ("straja.fine.not_recoverable", R + "retry",
         "The fine is no longer eligible for recovery."),
    "Doar Străjerul sau un rang superior poate executa mandatul de audiere.":
        ("straja.fine.warrant_rank", R + "duty",
         "Only the Guard or a higher rank can execute the hearing warrant."),
    "Doar Străjerul sau un rang superior poate executa această arestare.":
        ("straja.fine.arrest_rank", R + "duty",
         "Only the Guard or a higher rank can execute this arrest."),
    "Misiunea nu îți este atribuită.":
        ("straja.fine.mission_not_yours", R + "retry",
         "The mission is not assigned to you."),
    "Ținta trebuie să fie online pentru arestare.":
        ("straja.fine.arrest_offline", R + "wait",
         "The target must be online for the arrest."),
    "Trebuie să fii lângă țintă pentru arestare.":
        ("straja.fine.arrest_far", R + "retry",
         "You must be next to the target for the arrest."),
    "Arestarea nu a putut fi înregistrată.":
        ("straja.fine.arrest_fail", R + "ask_comisar",
         "The arrest could not be recorded."),
    "Nu există o solicitare de plată activă pentru acest ID.":
        ("straja.fine.no_payment_request", R + "reception",
         "There is no active payment request for this ID."),
    "Doar cetățeanul vizat poate refuza plata.":
        ("straja.fine.refuse_owner", R + "reception",
         "Only the targeted citizen can refuse payment."),
    "Amenda nu mai este în așteptarea unei decizii.":
        ("straja.fine.no_decision_pending", R + "retry",
         "The fine is no longer awaiting a decision."),
    "Refuzul plății se declară la recepționistă.":
        ("straja.fine.refuse_location", R + "reception",
         "The payment refusal is declared at the receptionist."),
    "Doar Străjerul sau un rang superior poate executa arestarea.":
        ("straja.fine.execute_rank", R + "duty",
         "Only the Guard or a higher rank can execute the arrest."),
    "Arestarea este permisă doar după refuzul explicit al cetățeanului la recepționistă (dosar %s).":
        ("straja.fine.arrest_needs_refusal", R + "reception",
         "The arrest is only allowed after the citizen's explicit refusal at the receptionist (case %s)."),
    "Arestarea pentru refuz se execută doar cât timp ținta este online.":
        ("straja.fine.arrest_online_only", R + "wait",
         "The refusal arrest can only be executed while the target is online."),
    "Amenda nu mai este eligibilă pentru arest.":
        ("straja.fine.arrest_ineligible", R + "retry",
         "The fine is no longer eligible for arrest."),
    "Ținta are deja o sentință activă. Nu se pot suprapune sentințele.":
        ("straja.fine.sentence_overlap", R + "ask_comisar",
         "The target already has an active sentence. Sentences cannot overlap."),
    "Trebuie să fii lângă țintă pentru a executa arestarea.":
        ("straja.fine.execute_far", R + "retry",
         "You must be next to the target to execute the arrest."),
    "Durata Comisarului trebuie să fie un număr întreg pozitiv.":
        ("straja.fine.duration_invalid", R + "fix_retry",
         "The Commissioner duration must be a positive integer."),
    "Recompensa nu a putut fi livrată acum; dosarul rămâne revendicabil.":
        ("straja.fine.reward_fail", R + "retry",
         "The reward could not be delivered now; the case stays claimable."),
    "Nu există o recompensă de arestare pentru acest dosar.":
        ("straja.fine.no_reward", R + "faq",
         "There is no arrest reward for this case."),
    "Doar garda însărcinată cu dosarul poate ridica recompensa.":
        ("straja.fine.reward_owner", R + "fix_retry",
         "Only the guard assigned to the case can claim the reward."),
    "Doar Inspectorul activ sau Comisaru' poate emite mandat de audiere.":
        ("straja.fine.warrant_issue_rank", R + "ask_comisar",
         "Only the active Inspector or the Commissioner can issue a hearing warrant."),
    "Ținta mandatului trebuie să fie online.":
        ("straja.fine.warrant_offline", R + "wait",
         "The warrant target must be online."),
    "Comisaru' nu poate fi ținta propriului mandat.":
        ("straja.fine.warrant_self", R + "fix_retry",
         "The Commissioner cannot be the target of their own warrant."),
    "Doar Comisaru' poate anula amenzi.":
        ("straja.fine.cancel_comisar", R + "ask_comisar",
         "Only the Commissioner can cancel fines."),
    "Amenda nu există.":
        ("straja.fine.gone", R + "retry",
         "The fine does not exist."),
    "Doar Inspectorul sau Comisaru' poate vedea contestațiile.":
        ("straja.fine.appeals_view_rank", R + "ask_comisar",
         "Only the Inspector or the Commissioner can view appeals."),
    "Testul de timeout pentru contestații este disponibil doar Comisarului în debug local.":
        ("straja.fine.timeout_debug", R + "ask_comisar",
         "The appeal-timeout test is only available to the Commissioner in local debug."),
    "Amenda nu are o contestație în așteptare.":
        ("straja.fine.no_appeal_pending", R + "retry",
         "The fine has no pending appeal."),
    "Contestația %s nu a primit decizie în termen. Amenda %s a fost iertată automat.":
        ("straja.fine.appeal_timeout", R + "faq",
         "Appeal %s received no decision in time. Fine %s was forgiven automatically."),

    # ── application/service/GuardService.java ───────────────────────────
    "Demisia ta este pe rol sau în cooldown — cererea nu se depune la Recepție. Vorbește cu Comisaru'.":
        ("straja.duty.resignation_cooldown", R + "ask_comisar",
         "Your resignation is pending or in cooldown — the request is not filed at Reception."),
    "Doar Comisaru' poate edita facțiunea nativă a altui jucător.":
        ("straja.duty.faction_comisar", R + "ask_comisar",
         "Only the Commissioner can edit another player's native faction."),
    "Nu ai încă module de instruire disponibile pentru rangul tău. Consultă Manualul de la Instructor.":
        ("straja.duty.no_modules", R + "instructor",
         "You have no training modules available for your rank yet."),
    "Nu există o avansare disponibilă pentru starea ta curentă.":
        ("straja.duty.no_promotion", R + "instructor",
         "There is no promotion available for your current state."),
    "Avansare la %s: necesare %s puncte (îți lipsesc %s).":
        ("straja.duty.promotion_points", R + "duty",
         "Promotion to %s: %s points required (you are missing %s)."),
    "Nu ești într-o stare care permite avansarea.":
        ("straja.duty.promotion_state", R + "ask_comisar",
         "You are not in a state that allows promotion."),
    "Mai sunt necesare %s puncte de serviciu.":
        ("straja.duty.points_short", R + "duty",
         "%s more service points are needed."),
    "Cererea de avansare nu a putut fi înregistrată: %s":
        ("straja.duty.promotion_fail", R + "ask_comisar",
         "The promotion request could not be recorded: %s"),
    "Manualul de instruire nu este disponibil în starea ta curentă.":
        ("straja.duty.manual_state", R + "instructor",
         "The training manual is not available in your current state."),
    "Inventarul este plin; Manualul nu a putut fi predat.":
        ("straja.duty.manual_full", R + "retry",
         "Your inventory is full; the Manual could not be delivered."),
    "Ținta nu are o avansare disponibilă.":
        ("straja.duty.target_no_promotion", R + "instructor",
         "The target has no promotion available."),
    "Promovarea V2 a fost refuzată: %s":
        ("straja.duty.v2_promotion_refused", R + "ask_comisar",
         "The V2 promotion was refused: %s"),
    "Ținta nu poate fi promovată prin această comandă.":
        ("straja.duty.target_no_command", R + "ask_comisar",
         "The target cannot be promoted through this command."),
    "Mai sunt necesare %s blocuri de serviciu.":
        ("straja.duty.blocks_short", R + "duty",
         "%s more service blocks are needed."),
    "Rang invalid. Folosește un rang între Stagiar și Inspector.":
        ("straja.duty.rank_invalid", R + "fix_retry",
         "Invalid rank. Use a rank between Trainee and Inspector."),
    "Autorizarea V2 a fost refuzată; starea V1 nu a fost modificată.":
        ("straja.duty.v2_auth_refused", R + "ask_comisar",
         "V2 authorization was refused; the V1 state was not changed."),
    "Ținta nu este într-o stare care poate fi reintegrată.":
        ("straja.duty.rejoin_state", R + "ask_comisar",
         "The target is not in a state that can be reintegrated."),
    "Autorizația centrală nu permite patrulă în stația curentă.":
        ("straja.duty.patrol_blocked", R + "ask_comisar",
         "Central authorization does not allow patrols at the current station."),
    "Doar un străjer activ poate începe serviciul.":
        ("straja.duty.start_rank", R + "reception",
         "Only an active guard can start a shift."),
    "Checkpoint-urile nu sunt configurate. Comisaru' trebuie să marcheze cel puțin %s puncte de patrulare.":
        ("straja.duty.no_checkpoints", R + "ask_comisar",
         "Checkpoints are not configured. The Commissioner must mark at least %s patrol points."),
    "Doar un străjer activ poate activa checkpoint-uri.":
        ("straja.duty.checkpoint_rank", R + "duty",
         "Only an active guard can activate checkpoints."),
    "Nu ești la checkpoint-ul %s.":
        ("straja.duty.checkpoint_far", R + "retry",
         "You are not at checkpoint %s."),
    "Doar un străjer activ poate încheia serviciul.":
        ("straja.duty.stop_rank", R + "duty",
         "Only an active guard can end a shift."),
    "Nu ești în serviciu.":
        ("straja.duty.not_on_duty", R + "duty",
         "You are not on duty."),
    "Serviciul activ a fost închis la restart; timpul offline nu se plătește.":
        ("straja.duty.restart_closed", R + "duty",
         "The active shift was closed at restart; offline time is not paid."),
    "Ținta nu este un străjer operațional.":
        ("straja.duty.target_not_guard", R + "fix_retry",
         "The target is not an operational guard."),
    "Nu ai autoritate pentru reluarea Special Duty.":
        ("straja.duty.sd_resume_auth", R + "ask_comisar",
         "You lack authority to resume Special Duty."),
    "Nu ai autoritate pentru închiderea Special Duty.":
        ("straja.duty.sd_close_auth", R + "ask_comisar",
         "You lack authority to close Special Duty."),
    "Nu există o demisie în așteptare.":
        ("straja.duty.no_resignation", R + "secretary",
         "There is no pending resignation."),
    "Nu ai o demisie activă.":
        ("straja.duty.no_resignation_active", R + "secretary",
         "You have no active resignation."),
    "Nu ai salariu disponibil.":
        ("straja.duty.no_salary", R + "duty",
         "You have no salary available."),
    "Moneda externă nu este configurată. Comisaru' trebuie să confirme ID-urile înainte de plata salariilor. Soldul a fost păstrat.":
        ("straja.duty.no_currency", R + "ask_comisar",
         "The external currency is not configured. The Commissioner must confirm the IDs before salary payout. Your balance was kept."),
    "Plata anterioară nu a putut fi confirmată; blocată pentru verificarea Comisarului.":
        ("straja.duty.payment_unconfirmed", R + "ask_comisar",
         "The previous payment could not be confirmed; blocked pending the Commissioner's review."),
    "Hrana de serviciu este disponibilă doar pentru un străjer activ.":
        ("straja.duty.food_rank", R + "duty",
         "Duty food is only available to an active guard."),
    "Hrana nu încape în inventar. Eliberează un slot și încearcă din nou.":
        ("straja.duty.food_full", R + "retry",
         "The food does not fit your inventory. Free a slot and try again."),
    "Doar Comisaru' poate modifica soldul de rechiziție.":
        ("straja.duty.requisition_comisar", R + "ask_comisar",
         "Only the Commissioner can change the requisition balance."),
    "Cantitatea trebuie să fie un număr pozitiv.":
        ("straja.duty.amount_invalid", R + "fix_retry",
         "The amount must be a positive number."),
    "Doar Comisaru' poate configura checkpoint-urile.":
        ("straja.duty.checkpoints_comisar", R + "ask_comisar",
         "Only the Commissioner can configure checkpoints."),
    "Nu există sloturi de checkpoint. Adaugă-le cu /straja checkpoint add.":
        ("straja.duty.no_checkpoint_slots", R + "ask_comisar",
         "There are no checkpoint slots."),
    "Doar Inspectorul sau Comisaru' pot stabili timpul misiunii.":
        ("straja.duty.mission_time_rank", R + "ask_comisar",
         "Only the Inspector or the Commissioner can set mission time."),
    "Doar Comisaru' poate configura locațiile administrative.":
        ("straja.duty.locations_comisar", R + "ask_comisar",
         "Only the Commissioner can configure administrative locations."),
    "Dosarul tău nu permite momentan admiterea în Strajă. Rezolvă sancțiunile și reabilitează-te înainte de a reaplica.":
        ("straja.duty.record_blocked", R + "reception",
         "Your record does not currently allow admission into the Straja. Resolve your sanctions and rehabilitate before reapplying."),

    # ── application/service/IdentityCardService.java ────────────────────
    "Buletinul contrafăcut nu a putut fi livrat; nu s-a creat niciun registru.":
        ("straja.idcard.forged_fail", R + "retry",
         "The forged ID could not be delivered; no registry was created."),
    "Buletinul nu există sau nu ai dreptul să-l verifici.":
        ("straja.idcard.no_access", R + "jailer",
         "The ID does not exist or you have no right to check it."),
    "Doar Comisaru' sau un operator poate revoca buletine.":
        ("straja.idcard.revoke_comisar", R + "ask_comisar",
         "Only the Commissioner or an operator can revoke IDs."),
    "ID-ul sau motivul revocării nu este valid.":
        ("straja.idcard.revoke_invalid", R + "fix_retry",
         "The ID or the revocation reason is not valid."),
    "Buletinul nu există.":
        ("straja.idcard.missing", R + "retry",
         "The ID does not exist."),
    "Titularul nu are o identitate utilizabilă.":
        ("straja.idcard.holder_invalid", R + "fix_retry",
         "The holder has no usable identity."),
    "Doar Comisaru' sau un operator poate emite buletine direct.":
        ("straja.idcard.issue_comisar", R + "ask_comisar",
         "Only the Commissioner or an operator can issue IDs directly."),
    "Titularul trebuie să fie conectat.":
        ("straja.idcard.holder_offline", R + "wait",
         "The holder must be connected."),
    "Doar Comisaru' sau un operator poate crea buletine contrafăcute.":
        ("straja.idcard.forge_comisar", R + "ask_comisar",
         "Only the Commissioner or an operator can create forged IDs."),

    # ── application/service/IncidentService.java ────────────────────────
    "Fluierul poate fi folosit doar în timpul serviciului.":
        ("straja.incident.whistle_duty", R + "duty",
         "The whistle can only be used while on duty."),
    "Nu ai autoritatea de a crea incidente.":
        ("straja.incident.create_auth", R + "duty",
         "You lack the authority to create incidents."),
    "Incidentul trebuie să aibă titlu și descriere.":
        ("straja.incident.fields_required", R + "fix_retry",
         "The incident needs a title and a description."),
    "Incidentul nu mai este activ.":
        ("straja.incident.inactive", R + "retry",
         "The incident is no longer active."),
    "Doar Străjerul principal sau un superior poate încheia incidentul.":
        ("straja.incident.close_rank", R + "duty",
         "Only the lead Guard or a superior can close the incident."),
    "Trebuie să fii Străjer activ pentru a lucra la incidente.":
        ("straja.incident.work_rank", R + "duty",
         "You must be an active Guard to work on incidents."),

    # ── application/service/MissionService.java ─────────────────────────
    "Misiunea trebuie predată unui subordonat online, nu emitentului.":
        ("straja.mission.self_handoff", R + "fix_retry",
         "The mission must be handed to an online subordinate, not the issuer."),
    "Destinatarul trebuie să fie un străjer activ și nesuspendat.":
        ("straja.mission.recipient_inactive", R + "fix_retry",
         "The recipient must be an active, non-suspended guard."),
    "Misiunea poate fi predată doar unui rang inferior emitentului.":
        ("straja.mission.recipient_rank", R + "fix_retry",
         "The mission can only be handed to a rank below the issuer's."),
    "Rangul minim trebuie să fie junior, străjer, senior sau locotenent.":
        ("straja.mission.min_rank_invalid", R + "fix_retry",
         "The minimum rank must be junior, guard, senior or lieutenant."),
    "Numărul de participanți trebuie să fie între 1 și %s.":
        ("straja.mission.slots_invalid", R + "fix_retry",
         "The participant count must be between 1 and %s."),
    "Rangul minim trebuie să fie inferior rangului emitentului.":
        ("straja.mission.min_rank_too_high", R + "fix_retry",
         "The minimum rank must be below the issuer's rank."),
    "Carnetul de Misiuni este disponibil doar Inspectorului și Comisarului.":
        ("straja.mission.book_rank", R + "ask_comisar",
         "The Mission Ledger is only available to the Inspector and the Commissioner."),
    "Nu ai un ordin în lucru.":
        ("straja.mission.no_order", R + "retry",
         "You have no order in progress."),
    "Timpul estimativ trebuie să fie un număr întreg între %s și %s minute.":
        ("straja.mission.eta_invalid", R + "fix_retry",
         "The estimated time must be a whole number between %s and %s minutes."),
    "Recompensa trebuie să fie un număr întreg între 0 și %s monede.":
        ("straja.mission.reward_invalid", R + "fix_retry",
         "The reward must be a whole number between 0 and %s coins."),
    "Nu există un ordin în lucru.":
        ("straja.mission.no_draft", R + "retry",
         "There is no order in progress."),
    "Doar Inspectorul sau Comisaru' pot folosi Carnetul de Misiuni.":
        ("straja.mission.book_use_rank", R + "ask_comisar",
         "Only the Inspector or the Commissioner can use the Mission Ledger."),
    "Doar Inspectorul sau Comisaru' pot consulta șabloanele de misiune.":
        ("straja.mission.templates_rank", R + "ask_comisar",
         "Only the Inspector or the Commissioner can view mission templates."),
    "Nu există șabloane de misiune active.":
        ("straja.mission.no_templates", R + "ask_comisar",
         "There are no active mission templates."),
    "Șablonul nu există sau este dezactivat.":
        ("straja.mission.template_gone", R + "ask_comisar",
         "The template does not exist or is disabled."),
    "Ajustarea bugetului se aplică doar ordinelor create dintr-un șablon.":
        ("straja.mission.budget_template_only", R + "fix_retry",
         "Budget adjustment only applies to orders created from a template."),
    "Ordinul are deja copii emise — bugetul nu se mai poate schimba.":
        ("straja.mission.budget_locked", R + "fix_retry",
         "The order already has issued copies — the budget can no longer change."),
    "Orele estimate trebuie să fie între 0 și 24, iar riscul între 0 și 10.":
        ("straja.mission.hours_risk_invalid", R + "fix_retry",
         "Estimated hours must be 0-24 and risk 0-10."),
    "Recompensa depășește limita calculată (%s B). Precizează un motiv pentru depășire.":
        ("straja.mission.reward_over_limit", R + "fix_retry",
         "The reward exceeds the calculated limit (%s B). Give a reason for the excess."),
    "Doar Comisaru' administrează șabloanele de misiune.":
        ("straja.mission.templates_comisar", R + "ask_comisar",
         "Only the Commissioner administers mission templates."),
    "Nu există șabloane de misiune.":
        ("straja.mission.templates_empty", R + "ask_comisar",
         "There are no mission templates."),
    "Șablonul %s nu există.":
        ("straja.mission.template_missing", R + "fix_retry",
         "Template %s does not exist."),
    "Numele trebuie să aibă 1-60 caractere.":
        ("straja.mission.name_invalid", R + "fix_retry",
         "The name must be 1-60 characters."),
    "Rangul minim trebuie să fie 1-4.":
        ("straja.mission.min_rank_range", R + "fix_retry",
         "The minimum rank must be 1-4."),
    "Orele estimate trebuie să fie între 0 și 24.":
        ("straja.mission.hours_invalid", R + "fix_retry",
         "Estimated hours must be between 0 and 24."),
    "Riscul trebuie să fie între 0 și 10.":
        ("straja.mission.risk_invalid", R + "fix_retry",
         "Risk must be between 0 and 10."),
    "Număr de participanți invalid.":
        ("straja.mission.slots_bad", R + "fix_retry",
         "Invalid participant count."),
    "Participanții plătiți trebuie să fie între 1 și %s.":
        ("straja.mission.paid_slots_invalid", R + "fix_retry",
         "Paid participants must be between 1 and %s."),
    "Termen invalid.":
        ("straja.mission.term_bad", R + "fix_retry",
         "Invalid term."),
    "Termenul trebuie să fie între %s și %s minute.":
        ("straja.mission.term_range", R + "fix_retry",
         "The term must be between %s and %s minutes."),
    "Obiectivul trebuie să aibă 1-%s caractere.":
        ("straja.mission.objective_length", R + "fix_retry",
         "The objective must be 1-%s characters."),
    "Doar Inspectorul sau Comisaru' pot declara misiuni plătite.":
        ("straja.mission.declare_rank", R + "ask_comisar",
         "Only the Inspector or the Commissioner can declare paid missions."),
    "Jucătorul țintă trebuie să fie online.":
        ("straja.common.target_offline", R + "wait",
         "The target player must be online."),
    "Destinatarul nu atinge rangul minim al misiunii (%s).":
        ("straja.mission.recipient_below_min", R + "fix_retry",
         "The recipient does not meet the mission's minimum rank (%s)."),
    "Misiunea #%s nu a putut fi livrată. Anunță Comisaru'.":
        ("straja.mission.delivery_fail", R + "ask_comisar",
         "Mission #%s could not be delivered."),
    "Nu ai un ordin complet. Scrie misiunea, semneaz-o și împacheteaz-o mai întâi.":
        ("straja.mission.no_complete_order", R + "retry",
         "You have no complete order. Write the mission, sign it and package it first."),
    "Ordinul a expirat înainte de predare. Scrie un draft nou.":
        ("straja.mission.order_expired", R + "retry",
         "The order expired before handover. Write a new draft."),
    "Predarea trebuie făcută unui subordonat online, nu emitentului.":
        ("straja.mission.handoff_self", R + "fix_retry",
         "The handover must go to an online subordinate, not the issuer."),
    "Ordinul #%s a fost predat fizic, dar pachetul Envelope nu a putut fi trimis. Anunță Comisaru' pentru retrimiterea pachetului.":
        ("straja.mission.envelope_fail", R + "ask_comisar",
         "Order #%s was physically handed over, but the Envelope packet could not be sent."),
    "Pachetul nu a putut fi predat; ordinul a rămas în carnet și misiunea este păstrată ca FAILED.":
        ("straja.mission.packet_fail", R + "ask_comisar",
         "The packet could not be delivered; the order stayed in the ledger and the mission is kept as FAILED."),
    "Misiunea nu are un pachet Envelope în așteptare.":
        ("straja.mission.no_pending_packet", R + "retry",
         "The mission has no pending Envelope packet."),
    "Doar emitentul sau Comisaru' poate retrimite pachetul.":
        ("straja.mission.resend_auth", R + "ask_comisar",
         "Only the issuer or the Commissioner can resend the packet."),
    "Pachetul Envelope încă nu poate fi trimis pentru misiunea #%s.":
        ("straja.mission.packet_blocked", R + "wait",
         "The Envelope packet for mission #%s cannot be sent yet."),
    "Nu există misiuni vizibile.":
        ("straja.mission.none_visible", R + "wait",
         "There are no visible missions."),
    "Doar proprietarul misiunii poate invita participanți.":
        ("straja.mission.invite_owner", R + "fix_retry",
         "Only the mission owner can invite participants."),
    "Misiunea nu mai acceptă participanți: %s.":
        ("straja.mission.join_closed", R + "retry",
         "The mission no longer accepts participants: %s."),
    "Participantul trebuie să fie online și diferit de emitent.":
        ("straja.mission.participant_invalid", R + "fix_retry",
         "The participant must be online and different from the issuer."),
    "Participantul trebuie să aibă cel puțin rangul %s.":
        ("straja.mission.participant_rank", R + "instructor",
         "The participant must be at least rank %s."),
    "Doar Comisaru' poate recupera o plată.":
        ("straja.mission.recover_comisar", R + "ask_comisar",
         "Only the Commissioner can recover a payment."),
    "Misiunea nu există sau nu este închisă.":
        ("straja.mission.not_closed", R + "retry",
         "The mission does not exist or is not closed."),
    "Nu există încă un participant online cu o plată recuperabilă; soldul rămâne pending.":
        ("straja.mission.no_recoverable", R + "wait",
         "There is no online participant with a recoverable payment yet; the balance stays pending."),
    "Doar un străjer activ poate %s.":
        ("straja.mission.action_rank", R + "duty",
         "Only an active guard can %s."),
    "Misiunea nu există sau nu ai fost invitat.":
        ("straja.mission.not_invited", R + "retry",
         "The mission does not exist or you were not invited."),
    "Ai refuzat deja acest ordin și nu mai poți reintra.":
        ("straja.mission.already_declined", R + "retry",
         "You already declined this order and cannot rejoin."),
    "Nu îndeplinești rangul sau statutul necesar pentru această misiune.":
        ("straja.mission.rank_unmet", R + "instructor",
         "You do not meet the required rank or status for this mission."),
    "Misiunea nu există sau nu îți aparține.":
        ("straja.mission.not_yours", R + "retry",
         "The mission does not exist or does not belong to you."),
    "Misiunea nu mai poate fi acceptată: %s.":
        ("straja.mission.accept_closed", R + "retry",
         "The mission can no longer be accepted: %s."),
    "Misiunea a expirat.":
        ("straja.mission.expired", R + "retry",
         "The mission has expired."),
    "Misiunea nu mai poate fi refuzată: %s.":
        ("straja.mission.decline_closed", R + "retry",
         "The mission can no longer be declined: %s."),
    "Misiunea a expirat și nu mai poate fi refuzată.":
        ("straja.mission.decline_expired", R + "retry",
         "The mission expired and can no longer be declined."),
    "Raportul nu mai poate fi trimis pentru această misiune: %s.":
        ("straja.mission.report_closed", R + "retry",
         "The report can no longer be sent for this mission: %s."),
    "Misiunea a depășit timpul și a fost marcată ca eșuată.":
        ("straja.mission.timed_out", R + "wait",
         "The mission exceeded its time and was marked failed."),
    "Misiunea nu mai poate fi eșuată: %s.":
        ("straja.mission.fail_closed", R + "retry",
         "The mission can no longer be failed: %s."),
    "Nu ai autoritate pentru această misiune.":
        ("straja.mission.no_auth", R + "ask_comisar",
         "You lack authority for this mission."),
    "Misiunea trebuie să aibă raportul primit înainte de închidere.":
        ("straja.mission.needs_report", R + "fix_retry",
         "The mission must have its report received before closing."),
    "Recompensa nu există sau nu îți aparține.":
        ("straja.mission.reward_gone", R + "retry",
         "The reward does not exist or is not yours."),
    "Recompensa misiunii este invalidă; anunță Comisaru'.":
        ("straja.mission.reward_invalid_state", R + "ask_comisar",
         "The mission reward is invalid."),
    "Misiunea nu are recompensă monetară.":
        ("straja.mission.no_reward", R + "fix_retry",
         "The mission has no monetary reward."),
    "Destinatarul recompensei trebuie să fie online pentru ridicare.":
        ("straja.mission.reward_offline", R + "wait",
         "The reward recipient must be online for collection."),
    "Nu există o cotă de recompensă pentru acest participant.":
        ("straja.mission.no_share", R + "retry",
         "There is no reward share for this participant."),
    "Plata cotei tale este în verificare; Comisaru' trebuie să verifice tranzacția pentru misiunea #%s.":
        ("straja.mission.share_pending", R + "ask_comisar",
         "Your share's payment is under review; the Commissioner must verify the transaction for mission #%s."),

    # ── application/service/PrisonService.java ──────────────────────────
    "Doar Comisaru' poate configura celule.":
        ("straja.prison.cells_comisar", R + "ask_comisar",
         "Only the Commissioner can configure cells."),
    "ID de celulă invalid.":
        ("straja.prison.cell_invalid", R + "fix_retry",
         "Invalid cell ID."),
    "Celula este ocupată și nu poate fi recreată până la eliberare.":
        ("straja.prison.cell_occupied", R + "wait",
         "The cell is occupied and cannot be recreated until release."),
    "Nu există celule configurate.":
        ("straja.prison.no_cells", R + "ask_comisar",
         "There are no configured cells."),
    "Arestarea a fost înregistrată, dar predarea la închisoare trebuie repetată.":
        ("straja.prison.handover_retry", R + "jailer",
         "The arrest was recorded, but the prison handover must be retried."),
    "Doar un Străjer sau Comisaru' poate elibera un deținut.":
        ("straja.prison.release_rank", R + "jailer",
         "Only a Guard or the Commissioner can release a prisoner."),
    "%s nu are o sentință activă.":
        ("straja.prison.no_sentence", R + "jailer",
         "%s has no active sentence."),
    "Nu ai o sentință activă.":
        ("straja.prison.self_no_sentence", R + "jailer",
         "You have no active sentence."),

    # ── application/service/ReportService.java ──────────────────────────
    "Doar membrii Străjii depun rapoarte de activitate.":
        ("straja.report.members_only", R + "reception",
         "Only Straja members file activity reports."),
    "Raportul trebuie să descrie activitatea din perioada raportată.":
        ("straja.report.content_required", R + "fix_retry",
         "The report must describe the activity of the reported period."),
    "Raportul %s te așteaptă la Comisar; nu poți depune altul acum.":
        ("straja.report.pending", R + "ask_comisar",
         "Report %s is waiting for you at the Commissioner; you cannot file another now."),
    "Doar membrii Străjii au rapoarte de activitate.":
        ("straja.report.list_members", R + "reception",
         "Only Straja members have activity reports."),
    "Nu ai depus încă niciun raport de activitate.":
        ("straja.report.none_mine", R + "secretary",
         "You have not filed any activity report yet."),
    "Doar Comisaru' verifică rapoartele de activitate.":
        ("straja.report.verify_comisar", R + "ask_comisar",
         "Only the Commissioner reviews activity reports."),
    "Nu există rapoarte în așteptare.":
        ("straja.report.none_pending", R + "wait",
         "There are no pending reports."),
    "Raportul %s nu așteaptă o decizie.":
        ("straja.report.not_pending", R + "retry",
         "Report %s is not awaiting a decision."),

    # ── application/service/ReputationService.java ──────────────────────
    "Comanda trebuie să fie Comisar și să aibă motiv.":
        ("straja.reputation.comisar_reason", R + "ask_comisar",
         "The command must come from the Commissioner and carry a reason."),
    "Doar Comisaru' poate inversa un eveniment de reputație.":
        ("straja.reputation.reverse_comisar", R + "ask_comisar",
         "Only the Commissioner can reverse a reputation event."),

    # ── application/service/RoomService.java ────────────────────────────
    "Doar Comisaru' poate crea camere.":
        ("straja.room.create_comisar", R + "ask_comisar",
         "Only the Commissioner can create rooms."),
    "ID invalid. Folosește doar litere mici, cifre, _ sau - (maximum 32).":
        ("straja.room.id_invalid", R + "fix_retry",
         "Invalid ID. Use only lowercase letters, digits, _ or - (max 32)."),
    "Scrisoarea „%s” nu a putut fi pusă în inventar. Eliberează un slot și anunță Comisaru'.":
        ("straja.room.letter_fail", R + "retry",
         "The letter „%s” could not be put in your inventory. Free a slot and notify the Commissioner."),
    "Nu ai o cameră atribuită.":
        ("straja.room.none_assigned", R + "ask_comisar",
         "You have no assigned room."),
    "Sigiliul de inspecție poate fi folosit doar de Comisaru'.":
        ("straja.room.seal_comisar", R + "ask_comisar",
         "The inspection seal can only be used by the Commissioner."),
    "Nu există camere configurate.":
        ("straja.room.none_configured", R + "ask_comisar",
         "There are no configured rooms."),

    # ── application/service/BoloService.java ────────────────────────────
    "BOLO-ul nu mai este activ.":
        ("straja.bolo.inactive", R + "retry",
         "The BOLO is no longer active."),
    "Nu ai autoritatea de a anula acest BOLO.":
        ("straja.bolo.cancel_auth", R + "ask_comisar",
         "You lack the authority to cancel this BOLO."),

    # ── application/service/RpExpansionService.java ─────────────────────
    "Subiectul trebuie să fie online pentru această emitere.":
        ("straja.expansion.subject_offline", R + "wait",
         "The subject must be online for this issuance."),

    # ── application/service/SecretaryService.java ───────────────────────
    "Nu ai loc în inventar pentru copia cărții.":
        ("straja.secretary.copy_full", R + "retry",
         "You have no room in your inventory for the book copy."),

    # ── GuardService complex ternary (handled manually) ─────────────────
    "Avansarea la %s este decizia Comisarului.":
        ("straja.duty.promotion_comisar", R + "ask_comisar",
         "Promotion to %s is the Commissioner's decision."),

    # ── GuardService coreError / helpers (handled manually) ─────────────
    "Ai nevoie de rangul Stagiar.":
        ("straja.duty.err.rank_required", R + "instructor",
         "You need the Trainee rank."),
    "Ești deja în serviciu.":
        ("straja.duty.err.already_on_duty", R + "status",
         "You are already on duty."),
    "Ești suspendat și nu poți începe serviciul.":
        ("straja.duty.err.suspended", R + "ask_comisar",
         "You are suspended and cannot start a shift."),
    "Demisia este deja în așteptare; nu poți începe un serviciu nou.":
        ("straja.duty.err.resignation_pending", R + "wait",
         "Your resignation is already pending; you cannot start a new shift."),
    "Ai demisionat și ești în cooldown pentru revenire.":
        ("straja.duty.err.resigned", R + "wait",
         "You resigned and are in rejoin cooldown."),
    "Ai fost îndepărtat din Strajă și nu poți începe serviciul.":
        ("straja.duty.err.fired", R + "ask_comisar",
         "You were removed from the Straja and cannot start a shift."),
    "Traseul trebuie să conțină cel puțin %s checkpoint-uri distincte.":
        ("straja.duty.err.route_invalid", R + "ask_comisar",
         "The route must contain at least %s distinct checkpoints."),
    "Nu ești într-o patrulă normală activă.":
        ("straja.duty.err.not_normal_duty", R + "retry",
         "You are not in an active normal patrol."),
    "Checkpoint-ul nu este disponibil încă. Respectă pauza de %s minute.":
        ("straja.duty.err.checkpoint_not_active", R + "wait",
         "The checkpoint is not available yet. Respect the %s-minute pause."),
    "Acesta nu este checkpoint-ul activ.":
        ("straja.duty.err.wrong_checkpoint", R + "retry",
         "This is not the active checkpoint."),
    "Ținta trebuie să fie într-o patrulă normală.":
        ("straja.duty.err.normal_duty_required", R + "fix_retry",
         "The target must be in a normal patrol."),
    "Ținta nu este în Special Duty.":
        ("straja.duty.err.special_duty_required", R + "fix_retry",
         "The target is not in Special Duty."),
    "Ai deja demisia semnată.":
        ("straja.duty.err.already_resigned", R + "status",
         "Your resignation is already signed."),
    "Nu există o demisie în așteptare.":
        ("straja.duty.err.resignation_not_pending", R + "secretary",
         "There is no pending resignation."),
    "Perioada de așteptare nu a expirat.":
        ("straja.duty.err.resignation_wait", R + "wait",
         "The waiting period has not expired."),
    "Încheie serviciul activ înainte de această operațiune.":
        ("straja.duty.err.duty_active", R + "secretary",
         "End the active shift before this operation."),
    "Operațiune refuzată: %s":
        ("straja.duty.err.unknown", R + "faq",
         "Operation refused: %s"),
    "Depune mai întâi cererea la Recepție, apoi prezintă-te la Instructor pentru examen.":
        ("straja.duty.apply_first", R + "reception",
         "File the request at Reception first, then present yourself to the Trainer for the exam."),
    "Formularul nu mai este valid. Deschide din nou quiz-ul la Instructor.":
        ("straja.form.stale", R + "instructor",
         "The form is no longer valid. Reopen the quiz at the Trainer."),
    "Mai încearcă peste %s.":
        ("straja.duty.cooldown", R + "wait",
         "Try again in %s."),
    "Quiz-ul de rang se deschide după recrutarea ca Stagiar.":
        ("straja.duty.quiz_rank", R + "instructor",
         "The rank quiz opens after recruitment as Trainee."),
    "Nu poți semna încă. Mai sunt %s.":
        ("straja.duty.resignation_wait", R + "wait",
         "You cannot sign yet. %s left."),
    "Alege un alt jucător online.":
        ("straja.custody.policy_self", R + "fix_retry",
         "Choose another online player."),
    "Un străjer nu poate încătușa alt membru al Străjii fără override de Inspector sau Comisaru'.":
        ("straja.custody.policy_guard", R + "ask_comisar",
         "A guard cannot cuff another Straja member without an Inspector or Commissioner override."),

    # ── remaining denials in swept files ────────────────────────────────
    "Doar Comisarul poate folosi interfața administrativă.":
        ("straja.admin.interface_comisar", R + "ask_comisar",
         "Only the Commissioner can use the administrative interface."),
    "Doar Comisaru' poate autoriza Arhivista.":
        ("straja.archive.authorize_comisar", R + "ask_comisar",
         "Only the Commissioner can authorize the Archivist."),
    "Ținta trebuie să fie online.":
        ("straja.common.target_offline", R + "wait",
         "The target must be online."),
    "Nu ai rangul sau serviciul necesar pentru a emite un BOLO.":
        ("straja.bolo.issue_rank", R + "duty",
         "You lack the rank or duty needed to issue a BOLO."),
    "Doar Comisaru' poate invita recruți.":
        ("straja.duty.invite_comisar", R + "ask_comisar",
         "Only the Commissioner can invite recruits."),
    "Invitația se poate acorda doar unui Civil eligibil, fără statut activ sau suspendat.":
        ("straja.duty.invite_eligible", R + "fix_retry",
         "The invite can only go to an eligible Civilian with no active or suspended status."),
    "Doar Comisaru' poate acorda sau retrage funcții.":
        ("straja.duty.function_comisar", R + "ask_comisar",
         "Only the Commissioner can grant or revoke functions."),
    "Funcția trebuie să aibă între 1 și %s caractere.":
        ("straja.duty.function_length", R + "fix_retry",
         "The function must be between 1 and %s characters."),
    "Buletinul nu a putut fi livrat; nu s-a creat niciun registru.":
        ("straja.idcard.undelivered", R + "retry",
         "The ID could not be delivered; no registry was created."),
    "Jucătorul a refuzat deja acest ordin și nu poate fi reinvitat.":
        ("straja.mission.reinvite_declined", R + "fix_retry",
         "The player already declined this order and cannot be re-invited."),
    "Vorbește cu Comisaru' și transmite-i mesajul complet.":
        ("straja.inbox.message_empty", R + "fix_retry",
         "Talk to the Commissioner and send the full message."),
    "Depune cererea direct la Comisaru', cu toate detaliile necesare.":
        ("straja.inbox.request_empty", R + "fix_retry",
         "File the request directly to the Commissioner, with all details."),

    # ── second pass: adversarial-review misses ──────────────────────────

    # CustodyService
    "Cătușele se acordă de la rangul Străjer în sus.":
        ("straja.custody.cuffs_rank", R + "instructor",
         "Cuffs are granted from the Guard rank up."),
    "Cererea de predare și cătușele se folosesc de la rangul Străjer în sus.":
        ("straja.custody.surrender_rank", R + "instructor",
         "Surrender requests and cuffs are used from the Guard rank up."),
    "Ține o Cheie, Foarfeca sau Brelocul Temnicerului în mâna principală.":
        ("straja.custody.hold_tool", R + "fix_retry",
         "Hold a Key, Shears, or the Jailer Keyring in your main hand."),
    "Ești încătușat de %s. Cheia trebuie folosită de acel gardian.":
        ("straja.custody.cuffed_by_other", R + "jailer",
         "You are cuffed by %s. The key must be used by that guard."),

    # GuardService
    "Mai întâi finalizează instruirea: %s module rămase. Întreabă Instructorul pentru următoarea întrebare.":
        ("straja.duty.finish_training", R + "instructor",
         "Finish your training first: %s modules left. Ask the Instructor for the next question."),
    "Raportul tău de activitate este restant. Depune-l la Secretariat înainte de a începe serviciul.":
        ("straja.duty.report_overdue", R + "secretary",
         "Your activity report is overdue. File it at the Secretariat before starting duty."),
    "Tura de patrulare începe la secretară. Mergi la Secretara Străjii pentru a intra în serviciu.":
        ("straja.duty.patrol_at_secretary", R + "secretary",
         "Patrol duty starts at the secretary. Go to the Straja Secretary to start your shift."),
    "Tura normală se încheie la secretară. Mergi la Secretara Străjii pentru a ieși din serviciu.":
        ("straja.duty.end_at_secretary", R + "secretary",
         "A normal shift ends at the secretary. Go to the Straja Secretary to leave duty."),
    "Facțiunea nativă poate avea maximum %s caractere.":
        ("straja.duty.faction_len", R + "fix_retry",
         "The native faction may have at most %s characters."),
    "Plata salariului este blocată pentru verificarea Comisarului.":
        ("straja.duty.salary_locked", R + "wait",
         "Salary payout is locked pending the Commissioner's check."),
    "Hrana de serviciu se ridică numai în timpul unei ture active.":
        ("straja.duty.food_duty", R + "duty",
         "Duty meals are only issued during an active shift."),
    "Manualul este pentru recruții invitați și străjeri. Vorbește cu Comisaru'.":
        ("straja.duty.manual_ranks", R + "ask_comisar",
         "The manual is for invited recruits and guards. Talk to the Commissioner."),
    "Special Duty poate fi autorizat de Comisaru' sau de un Inspector pentru alt străjer.":
        ("straja.duty.special_auth", R + "ask_comisar",
         "Special Duty may be authorized by the Commissioner or an Inspector for another guard."),
    "Locație necunoscută: reports, mailbox, office, receptionist, secretary, trainer, armorer, prison-release, infirmary sau hq.":
        ("straja.admin.location_unknown", R + "fix_retry",
         "Unknown location: reports, mailbox, office, receptionist, secretary, trainer, armorer, prison-release, infirmary or hq."),
    "Prezintă raportul complet direct Comisaru'ului.":
        ("straja.duty.report_blank", R + "fix_retry",
         "Present the full report directly to the Commissioner."),

    # ComplaintService
    "Registrul de plângeri este dezactivat.":
        ("straja.complaint.disabled", R + "ask_comisar",
         "The complaints registry is disabled."),
    "Depune la recepționistă un formular complet: acuzat și categorie (maximum %s caractere) și descriere (maximum %s caractere).":
        ("straja.complaint.form_incomplete", R + "fix_retry",
         "File a complete form at the receptionist: accused and category (max %s chars) and description (max %s chars)."),
    "Ai prea multe plângeri deschise. Așteaptă soluționarea lor.":
        ("straja.complaint.too_many", R + "wait",
         "You have too many open complaints. Wait for them to be resolved."),
    "Plângerea este deja preluată de %s.":
        ("straja.complaint.already_claimed", R + "fix_retry",
         "The complaint is already claimed by %s."),
    "Dosarele se preiau la secretară.":
        ("straja.complaint.claim_secretary", R + "secretary",
         "Case files are claimed at the secretary."),
    "Jucătorul este deja pe dosar.":
        ("straja.complaint.already_on_case", R + "fix_retry",
         "The player is already on the case file."),
    "Dosarul are deja numărul maxim de participanți.":
        ("straja.complaint.case_full", R + "wait",
         "The case file already has the maximum number of participants."),
    "Raportarea pentru dosar se face la secretară.":
        ("straja.complaint.checkin_secretary", R + "secretary",
         "Case reporting is done at the secretary."),
    "Părăsirea dosarului se face la secretară.":
        ("straja.complaint.leave_secretary", R + "secretary",
         "Leaving a case file is done at the secretary."),
    "Raportul de investigație se depune la secretară.":
        ("straja.complaint.report_secretary", R + "secretary",
         "The investigation report is filed at the secretary."),
    "Dosarul %s are un raport. Mergi la recepționistă și confirmă rezultatul sau retrage plângerea.":
        ("straja.complaint.confirm_reception", R + "reception",
         "Case file %s has a report. Go to the receptionist and confirm the result or withdraw the complaint."),
    "Confirmarea plângerii se face la recepționistă.":
        ("straja.complaint.confirmation_reception", R + "reception",
         "Complaint confirmation is done at the receptionist."),
    "Retragerea plângerii cere un motiv de maximum %s caractere.":
        ("straja.complaint.withdraw_reason", R + "fix_retry",
         "Withdrawing a complaint needs a reason of at most %s characters."),
    "La recepționistă, alege confirmarea soluționării sau retragerea plângerii.":
        ("straja.complaint.decision_reception", R + "reception",
         "At the receptionist, choose confirming the resolution or withdrawing the complaint."),
    "Bugetul zilnic al Inspectorului este insuficient (%s/%s).":
        ("straja.complaint.budget", R + "wait",
         "The Inspector's daily budget is insufficient (%s/%s)."),
    "Verificarea dosarelor se face la secretară.":
        ("straja.complaint.verify_secretary", R + "secretary",
         "Case verification is done at the secretary."),
    "În registrul de plângeri, alege aprobarea, returnarea pentru completări sau clasarea dosarului.":
        ("straja.complaint.pick_action", R + "fix_retry",
         "In the complaints registry, choose approving, returning for amendments, or closing the file."),
    "Așteaptă confirmarea petentului sau folosește override-ul Comisarului.":
        ("straja.complaint.wait_complainant", R + "wait",
         "Wait for the complainant's confirmation or use the Commissioner's override."),
    "Plângerea se depune la recepționistă.":
        ("straja.complaint.at_reception", R + "reception",
         "Complaints are filed at the receptionist."),

    # EvidenceService
    "Inventarul proprietarului este plin; proba rămâne în Arhivă.":
        ("straja.evidence.owner_full", R + "wait",
         "The owner's inventory is full; the evidence stays in the Archive."),
    "Transferul cere un arhivist/Comisar și un custode autorizat.":
        ("straja.evidence.transfer_auth", R + "ask_comisar",
         "Transfers require an archivist/Commissioner and an authorized custodian."),

    # AudienceService
    "Cererea de audiență are nevoie de un motiv.":
        ("straja.audience.need_reason", R + "fix_retry",
         "The audience request needs a reason."),
    "Decizie necunoscută — folosește resolve sau dismiss.":
        ("straja.audience.bad_decision", R + "fix_retry",
         "Unknown decision — use resolve or dismiss."),

    # ReportService
    "Decizie necunoscută — folosește accept, return sau call.":
        ("straja.report.bad_decision", R + "fix_retry",
         "Unknown decision — use accept, return or call."),
    "Un raport returnat are nevoie de o notă pentru autor.":
        ("straja.report.return_note", R + "fix_retry",
         "A returned report needs a note for its author."),

    # ArrestRecordService
    "Predarea la Temnicer cere un străjer activ.":
        ("straja.arrest.jailer_only", R + "duty",
         "Handing over to the Jailer requires an on-duty guard."),

    # MissionService
    "Sfera ordinului: minim %s, maximum %s participanți. Semnează și sigilează din nou.":
        ("straja.mission.scope_range", R + "fix_retry",
         "Order scope: min %s, max %s participants. Sign and seal it again."),
    "Crearea rapidă de misiuni este dezactivată de configurația serverului.":
        ("straja.mission.quick_off", R + "ask_comisar",
         "Quick mission creation is disabled by the server configuration."),
    "Recompensa se poate ridica numai după completarea misiunii.":
        ("straja.mission.reward_early", R + "wait",
         "The reward can only be claimed after the mission is completed."),
    "Misiunile oficiale se declară la secretară.":
        ("straja.mission.declare_secretary", R + "secretary",
         "Official missions are declared at the secretary."),
    "Predarea oficială se face la secretară.":
        ("straja.mission.submit_secretary", R + "secretary",
         "Official submission is done at the secretary."),

    # PrisonService
    "Numărul maxim de celule a fost atins.":
        ("straja.prison.max_cells", R + "ask_comisar",
         "The maximum number of cells has been reached."),
    "Sistemul de detenție este dezactivat.":
        ("straja.prison.disabled", R + "ask_comisar",
         "The detention system is disabled."),

    # IdentityCardService / IncidentService / BoloService / FineService
    "Sistemul de buletine este dezactivat.":
        ("straja.idcard.disabled", R + "ask_comisar",
         "The identity-card system is disabled."),
    "Sistemul de incidente este dezactivat.":
        ("straja.incident.disabled", R + "ask_comisar",
         "The incident system is disabled."),
    "Incidentul are deja numărul maxim de sprijinitori.":
        ("straja.incident.max_supporters", R + "wait",
         "The incident already has the maximum number of supporters."),
    "Sistemul BOLO este dezactivat.":
        ("straja.bolo.disabled", R + "ask_comisar",
         "The BOLO system is disabled."),
    "Sistemul de amenzi este dezactivat.":
        ("straja.fine.disabled", R + "ask_comisar",
         "The fines system is disabled."),
    "Retry este blocat: inventarul poate fi modificat parțial. Verifică tranzacția și folosește recover paid dacă plata a fost pierdută.":
        ("straja.fine.retry_blocked", R + "fix_retry",
         "Retry is blocked: the inventory may be partially modified. Check the transaction and use recover paid if the payment was lost."),
    "Contestațiile sunt dezactivate.":
        ("straja.fine.appeals_off", R + "ask_comisar",
         "Appeals are disabled."),
    "Contestațiile tale sunt blocate temporar pentru depuneri repetate.":
        ("straja.fine.appeals_blocked_self", R + "wait",
         "Your appeals are temporarily blocked for repeated filings."),
    "Contestațiile au fost blocate temporar pentru depuneri repetate.":
        ("straja.fine.appeals_blocked", R + "wait",
         "Appeals were temporarily blocked for repeated filings."),
    "Scrie motivul contestației, maximum %s caractere.":
        ("straja.fine.appeal_reason", R + "fix_retry",
         "Write the appeal reason, at most %s characters."),
    "Misiunea are deja numărul maxim de gărzi.":
        ("straja.fine.mission_full", R + "wait",
         "The mission already has the maximum number of guards."),
    "Plata se face la recepționistă. Mergi la locația configurată de Comisaru'.":
        ("straja.fine.pay_reception", R + "reception",
         "Payment is made at the receptionist. Go to the location configured by the Commissioner."),
    "Contestația se depune la recepționistă.":
        ("straja.fine.appeal_reception", R + "reception",
         "Appeals are filed at the receptionist."),
    "Decizia contestației se dă la recepționistă.":
        ("straja.fine.appeal_decision_reception", R + "reception",
         "The appeal decision is given at the receptionist."),
    "Audierea cere ținta, executantul și Comisaru' prezenți la biroul configurat.":
        ("straja.fine.hearing_office", R + "fix_retry",
         "A hearing needs the target, the executor, and the Commissioner present at the configured office."),

    # ArchiveService
    "Act semnat și blocat: %s. Corecțiile se fac printr-o foaie nouă.":
        ("straja.archive.signed_locked", R + "fix_retry",
         "Signed and locked document: %s. Corrections are made via a new sheet."),
    "Numai originalul unui act semnat poate produce copii.":
        ("straja.archive.copy_original", R + "fix_retry",
         "Only the original of a signed document can produce copies."),

    # ArmoryService
    "Fonduri insuficiente. Preț: %s monede.":
        ("straja.armory.no_funds", R + "secretary",
         "Insufficient funds. Price: %s coins."),
    "Puncte de rechiziție insuficiente. Ai %s, cost: %s.":
        ("straja.armory.no_points", R + "secretary",
         "Insufficient requisition points. You have %s, cost: %s."),

    # AdminToolService
    "Instrumentele administrative pot fi folosite doar de Comisar sau operatori.":
        ("straja.admintool.comisar_only", R + "ask_comisar",
         "Admin tools may only be used by the Commissioner or operators."),
    "Bagheta funcționează doar pe un NPC Straja înregistrat.":
        ("straja.admintool.wand_npc", R + "fix_retry",
         "The wand only works on a registered Straja NPC."),
    "Numele trebuie să aibă între 1 și 80 de caractere.":
        ("straja.admintool.name_len", R + "fix_retry",
         "The name must be between 1 and 80 characters."),
    "Skin-ul trebuie să aibă între 1 și 80 de caractere.":
        ("straja.admintool.skin_len", R + "fix_retry",
         "The skin must be between 1 and 80 characters."),
    "NPC-ul nu mai este înregistrat.":
        ("straja.admintool.npc_gone", R + "retry",
         "The NPC is no longer registered."),
    "Doar Comisaru' poate configura locațiile administrative.":
        ("straja.admintool.locations_comisar", R + "ask_comisar",
         "Only the Commissioner can configure admin locations."),

    # StrajaEvents / StrajaCommands
    "Registrul de Amenzi este rezervat Străjii active.":
        ("straja.item.fine_book_reserved", R + "duty",
         "The Fines register is reserved for active Straja members."),
    "Arestarea cere rangul de Străjer sau Comisaru'.":
        ("straja.cmd.arrest_rank", R + "duty",
         "Arresting requires the rank of Guard or Commissioner."),
    "Jucător offline.":
        ("straja.cmd.offline", R + "retry",
         "Player offline."),
    "Jucător offline sau necunoscut.":
        ("straja.cmd.unknown_target", R + "fix_retry",
         "Player offline or unknown."),
    "Această comandă necesită un jucător.":
        ("straja.cmd.player_only", R + "fix_retry",
         "This command requires a player."),
    "Această acțiune necesită un jucător.":
        ("straja.cmd.action_player_only", R + "fix_retry",
         "This action requires a player."),
    "Straja runtime is not running.":
        ("straja.cmd.runtime_down", R + "wait",
         "Straja runtime is not running."),

    "Niciun șablon capturat — copia nu a fost înregistrată.":
        ("straja.admintool.clone_fail", R + "retry",
         "No template captured — the copy was not registered."),
    "Clonatorul capturează doar un NPC Straja înregistrat.":
        ("straja.admintool.cloner_npc", R + "fix_retry",
         "The cloner only captures a registered Straja NPC."),
    "Celula nu poate traversa dimensiuni — selecția a fost resetată.":
        ("straja.admintool.cell_dimension", R + "fix_retry",
         "The cell cannot cross dimensions — the selection was reset."),

    # ── third pass: convention-test sweep of remaining surfaces ─────────

    # Admin command help / debug / npc command surfaces
    "Ajutorul Straja cere OP 3.":
        ("straja.cmd.help_op", R + "ask_comisar",
         "Straja help requires OP 3."),
    "Comanda cere OP %s.":
        ("straja.cmd.command_op", R + "ask_comisar",
         "This command requires OP %s."),
    "Revelarea răspunsurilor este dezactivată.":
        ("straja.debug.reveal_off", R + "ask_comisar",
         "Answer revealing is disabled."),
    "Debug este dezactivat sau interzis în acest mediu.":
        ("straja.debug.off", R + "ask_comisar",
         "Debug is disabled or forbidden in this environment."),
    "Rol necunoscut: %s":
        ("straja.npc.role_unknown", R + "fix_retry",
         "Unknown role: %s"),
    "Rol necunoscut: %s. Valide: %s":
        ("straja.npc.role_unknown_valid", R + "fix_retry",
         "Unknown role: %s. Valid: %s"),
    "NPC necunoscut: %s":
        ("straja.npc.npc_unknown", R + "fix_retry",
         "Unknown NPC: %s"),

    # StrajaCommands admin validations
    "Promotion necunoscută.":
        ("straja.cmd.promotion_unknown", R + "fix_retry",
         "Unknown promotion."),
    "Document necunoscut.":
        ("straja.cmd.document_unknown", R + "fix_retry",
         "Unknown document."),
    "Obligație necunoscută.":
        ("straja.cmd.obligation_unknown", R + "fix_retry",
         "Unknown obligation."),
    "Mobilizare necunoscută.":
        ("straja.cmd.mobilization_unknown", R + "fix_retry",
         "Unknown mobilization."),
    "Campanie necunoscută.":
        ("straja.cmd.campaign_unknown", R + "fix_retry",
         "Unknown campaign."),
    "Decontare necunoscută.":
        ("straja.cmd.settlement_unknown", R + "fix_retry",
         "Unknown settlement."),

    # FormSubmissionRouter validation
    "Datele ordinului nu sunt valide.":
        ("straja.form.order_invalid", R + "fix_retry",
         "The order data is not valid."),
    "Datele verificării nu sunt valide.":
        ("straja.form.verify_invalid", R + "fix_retry",
         "The verification data is not valid."),
    "Datele amenzii nu sunt valide.":
        ("straja.form.fine_invalid", R + "fix_retry",
         "The fine data is not valid."),
    "Datele contestației nu sunt valide.":
        ("straja.form.appeal_invalid", R + "fix_retry",
         "The appeal data is not valid."),
    "Datele copierii nu sunt valide.":
        ("straja.form.copy_invalid", R + "fix_retry",
         "The copy data is not valid."),

    # Misc keyed denials
    "[Straja] Ramură FAQ necunoscută.":
        ("straja.npc.faq_unknown", R + "fix_retry",
         "[Straja] Unknown FAQ branch."),
    "Rang sau grad necunoscut: %s. Folosește un rang 1-4 sau un grad (ex. ziler, meserias, inspector).":
        ("straja.admin.rank_unknown", R + "fix_retry",
         "Unknown rank or grade: %s. Use a rank 1-4 or a grade (e.g. ziler, meserias, inspector)."),
    "[Straja] Membru necunoscut: %s":
        ("straja.admin.member_unknown", R + "fix_retry",
         "[Straja] Unknown member: %s"),

    # Custody two-player gates and bound-interaction denial
    "Legarea cere doi jucători online.":
        ("straja.custody.bind_online", R + "retry",
         "Binding requires two players online."),
    "Sacul cere doi jucători online.":
        ("straja.custody.bag_online", R + "retry",
         "The captive bag requires two players online."),
    "Transportul cere doi jucători online.":
        ("straja.custody.transport_online", R + "retry",
         "Transport requires two players online."),
    "Resuscitarea cere doi jucători online.":
        ("straja.custody.rez_online", R + "retry",
         "Resuscitation requires two players online."),

    # Equipment delivery
    "Kitul nu încape în inventar. Eliberează sloturi și încearcă din nou.":
        ("straja.equipment.kit_full", R + "fix_retry",
         "The kit does not fit in your inventory. Free up slots and try again."),
    "Kitul nu a putut fi predat complet.":
        ("straja.equipment.kit_partial", R + "retry",
         "The kit could not be delivered completely."),

    # GuardService checkpoints / routes
    "Checkpoint necunoscut. Creează slotul cu /straja checkpoint add.":
        ("straja.duty.checkpoint_unknown", R + "fix_retry",
         "Unknown checkpoint. Create the slot with /straja checkpoint add."),
    "Checkpoint necunoscut: %s.":
        ("straja.duty.checkpoint_unknown_arg", R + "fix_retry",
         "Unknown checkpoint: %s."),
    "Traseul cere între %s și %s puncte distincte — %s înregistrate.":
        ("straja.duty.route_range", R + "fix_retry",
         "The route needs between %s and %s distinct points — %s registered."),

    # Mission/Policy/Reputation/Room gates
    "Cod de rezolvare necunoscut.":
        ("straja.incident.code_unknown", R + "fix_retry",
         "Unknown resolution code."),
    "Câmp necunoscut. Folosește: name|minrank|hours|risk|participants|deadline|objective|supersedesPatrol.":
        ("straja.mission.field_unknown", R + "fix_retry",
         "Unknown field. Use: name|minrank|hours|risk|participants|deadline|objective|supersedesPatrol."),
    "Doar Comisaru' sau un operator poate vedea configurația.":
        ("straja.policy.view_comisar", R + "ask_comisar",
         "Only the Commissioner or an operator can view the configuration."),
    "Doar Comisaru' sau un operator poate modifica configurația.":
        ("straja.policy.modify_comisar", R + "ask_comisar",
         "Only the Commissioner or an operator can modify the configuration."),
    "Cheie necunoscută: %s. Vezi /straja policy list.":
        ("straja.policy.key_unknown", R + "fix_retry",
         "Unknown key: %s. See /straja policy list."),
    "Comanda trebuie să fie Comisar și să aibă motiv.":
        ("straja.reputation.comisar_reason", R + "ask_comisar",
         "The caller must be the Commissioner and give a reason."),
    "Doar Comisaru' poate inversa un eveniment de reputație.":
        ("straja.reputation.reverse_comisar", R + "ask_comisar",
         "Only the Commissioner can reverse a reputation event."),
    "Selecția camerei este într-o altă dimensiune decât cea curentă.":
        ("straja.room.other_dimension", R + "fix_retry",
         "The room selection is in a different dimension than the current one."),

    # FineService payment failure paths
    "Plata a eșuat după o modificare posibilă a inventarului. Amenda este blocată pentru verificarea Comisarului; nu încerca din nou.":
        ("straja.fine.pay_failed_review", R + "wait",
         "Payment failed after a possible inventory change. The fine is locked for the Commissioner's review; do not try again."),
    "Plata nu a putut fi efectuată; monedele nu au fost debitate.":
        ("straja.fine.pay_failed", R + "retry",
         "The payment could not be completed; no coins were debited."),

    # RoomService geometry codes
    "Blocul selectat nu pare să fie în interiorul unei camere.":
        ("straja.room.not_interior", R + "fix_retry",
         "The selected block does not appear to be inside a room."),
    "Nu există camere libere. Ești pe poziția %s.":
        ("straja.room.no_free", R + "wait",
         "There are no free rooms. You are at position %s."),
    "Ai deja o cameră atribuită.":
        ("straja.room.already", R + "fix_retry",
         "You already have a room assigned."),
    "Nu ești eligibil pentru o cameră.":
        ("straja.room.not_eligible", R + "ask_comisar",
         "You are not eligible for a room."),
    "Nu există camere configurate.":
        ("straja.room.none_configured", R + "ask_comisar",
         "There are no rooms configured."),
    "Nu aveai o cameră atribuită.":
        ("straja.room.no_room", R + "fix_retry",
         "You had no room assigned."),

    # PolicyService apply-result codes
    "Valoare lipsă pentru %s.":
        ("straja.policy.empty_value", R + "fix_retry",
         "Missing value for %s."),
    "Valoare invalidă pentru %s (tip: %s).":
        ("straja.policy.bad_value", R + "fix_retry",
         "Invalid value for %s (type: %s)."),

    # ── manual sites (switches/ternaries — edited by hand) ──────────────
    "Interiorul trebuie să aibă cel puțin %s×%s×%s blocuri.":
        ("straja.room.too_small", R + "fix_retry",
         "The interior must be at least %s×%s×%s blocks."),
    "Camera depășește limita sigură de %s×%s×%s / %s blocuri.":
        ("straja.room.too_large", R + "fix_retry",
         "The room exceeds the safe limit of %s×%s×%s / %s blocks."),
    "Camera este deschisă sau depășește limita de scanare %s×%s×%s.":
        ("straja.room.open_large", R + "fix_retry",
         "The room is open or exceeds the scan limit %s×%s×%s."),
    "Pereții camerei nu sunt închiși complet.":
        ("straja.room.open_wall", R + "fix_retry",
         "The room walls are not fully closed."),
    "Camera trebuie să aibă exact o ușă standard de două blocuri.":
        ("straja.room.door_required", R + "fix_retry",
         "The room needs exactly one standard two-block door."),
    "Un bloc din zona scanată nu poate fi citit; nu s-a creat camera.":
        ("straja.room.unreadable", R + "fix_retry",
         "A block in the scanned area could not be read; the room was not created."),
    "Camera nu a putut fi validată (%s).":
        ("straja.room.invalid", R + "fix_retry",
         "The room could not be validated (%s)."),
    "Ești legat. Nu poți interacționa până nu ești eliberat.":
        ("straja.custody.bound_interact", R + "jailer",
         "You are bound. You cannot interact until you are freed."),
    "Ești încătușat. Nu poți interacționa până nu ești eliberat.":
        ("straja.custody.cuffed_interact", R + "jailer",
         "You are cuffed. You cannot interact until you are freed."),

    # ── fourth pass: line-wrapped Component.literal sites ───────────────
    "Lipsesc locațiile NPC-urilor: %s. Folosește /straja set-location <nume> înainte de setup npcs.":
        ("straja.npc.locations_missing", R + "fix_retry",
         "NPC locations are missing: %s. Use /straja set-location <name> before setup npcs."),
    "Rangul sau gradul lipsește.":
        ("straja.form.rank_missing", R + "fix_retry",
         "The rank or grade is missing."),
    "Backup Straja eșuat: %s":
        ("straja.cmd.backup_failed", R + "retry",
         "Straja backup failed: %s"),
    "Migrație eșuată: %s":
        ("straja.cmd.migration_failed", R + "retry",
         "Migration failed: %s"),
    "Lipsește %s":
        ("straja.cmd.missing_dep", R + "fix_retry",
         "Missing %s"),
}

