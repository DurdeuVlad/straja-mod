# Wanted posters & face-cover evasion — implementation issues

**Goal:** hunted players are publicly announced on self-updating
posters, and covering your face genuinely evades recognition — with
no name tags on the server, the poster teaches the face and the mask
hides it back. Two issues, one identity pipeline.

## Design

- **POSTER-1 display-entity posters.** Admins declare poster spots with
  `/straja poster add|remove|list` at a looked-at wall position; each
  spot owns two vanilla display entities — a `Display.ItemDisplay`
  showing a `player_head` of the current target and a
  `Display.TextDisplay` beneath it (`CĂUTAT` / name / reward, center
  billboard). The service cycles `BountyService.activeBounties()`
  **only while unobserved** — a spot holding a player inside
  `poster.holdRadius` (default 14) never swaps mid-look. Spots with no
  active bounties show the "Nimic de căutat" idle face. Anchors persist
  in `SetupData.posterSpots` (same store as bribePosts/jobPosts);
  entities are tagged (`strajaPoster=<spotId>` in persistent data) so
  the service adopts-or-respawns them on chunk reload, never
  duplicates. Skin resolution at render time through the server profile
  cache, texture props captured into the head stack — an offline
  player keeps their face; an unresolvable profile degrades to a plain
  head while name + reward still show.
- **MASK-1 face-cover evasion.** A wanted player wearing a full-face
  cover — carved pumpkin, `straja:head_sack`, or any mob head
  (`mask.itemIds` policy) — is *unrecognized*: `PatrolService.isHuntable`
  suppresses them unless the engaging officer is inside
  `mask.recognitionRadius` (default 3). Checkpoints are the
  institution's counter — an ID check ignores masks entirely (no code
  needed: the checkpoint scan path stays as-is). Marks and BOLOs are
  unaffected — the mask suppresses *recognition*, not the record.
  Deferred to a later milestone: dwell-suspicion heat meter and
  crime-in-sight instant recognition.

## Issues

| # | Issue | Status | Commit | Notes |
|---|-------|--------|--------|-------|
| POSTER-1 | Display-entity poster spots, unobserved cycling, bounty feed, admin wand | done | 78f1f04 | 53/53 GameTests; captured face comes down next sweep |
| MASK-1 | Full-face items suppress patrol recognition within policy radius | done | 78f1f04 | Binary masking + 3-block recognition radius; heat meter deferred |

## Execution receipt

| Issue | Commit | State |
|---|---|---|
| POSTER-1 | 78f1f04 | verified — `wantedPosterShowsAndClearsFace` live smoke + `PosterServiceTest` |
| MASK-1 | 78f1f04 | verified — `maskedWantedEvadesPatrolScan` live smoke + `PatrolServiceTest` mask cases |
