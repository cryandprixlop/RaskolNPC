# Guard stage audit report

**Audited revision:** `67a184a13441ca7e984fed387ddff70e0f901bdb`  
**Branch:** `feature/resident-behavior-movement`  
**Pull request:** #1 (Draft; intentionally not merged)  
**Audit date:** 2026-11-14

## Scope

Reviewed guard hiring and capacity reservation, durable hiring-payment journal, daily rent collection and recovery, debt handling and deletion, persisted guard orders, startup sequencing, and plugin shutdown cleanup.

## Results

- Hiring cost and daily rent are checked for finite values before Towny placement and kingdom-capacity reservation.
- Nation guard reservations count pending hires and are synchronized; the concurrency regression test verifies that simultaneous attempts cannot exceed the configured limit.
- Hiring writes a durable journal entry before the Towny bank withdrawal. Ambiguous debit/refund outcomes are moved to manual review; automatic refund is withheld when NPC cleanup cannot be confirmed.
- Daily rent uses a durable per-guard/per-due-time ledger. An unfinished payment is not automatically retried after restart because the Towny bank result may be ambiguous.
- Confirmed rent outcomes are applied transactionally with NPC state/metadata. Repeated unpaid rent deactivates the guard and the configured maximum debt path queues deletion while preserving payment history.
- Startup recovers rent journal entries and moves unfinished hiring/refund attempts to manual review before NPC entity recovery and guard task startup.
- Shutdown cancels resident, guard-order, combat, and rent tasks before clearing runtime NPC state and closing SQLite.
- Guard order restoration occurs before the periodic order controller starts; startup fails if persisted orders cannot be restored.
- Existing regression coverage includes concurrent reservations, capacity boundaries, hiring journal transitions/recovery, rent idempotency/recovery, transaction rollback, deletion queueing, and guard order restoration/cleanup.

## Automated verification

GitHub Actions run `43615794679` for this audited revision completed successfully.

Observed workflow steps:
- `gradle build --no-daemon`
- Gradle `:test`
- Gradle `:check`
- Gradle `:build`

Final result: **BUILD SUCCESSFUL** (Java 25; Gradle 9.9.1).

## Packaging

The CI workflow now uploads the built `build/libs/*.jar` as a GitHub Actions artifact named `RaskolNPC-<commit SHA>`, retained for 14 days. This makes the exact CI-built plugin package retrievable from the workflow run.

## Not run / external acceptance required

These checks cannot be honestly marked PASS from repository CI alone:
- Live Paper server startup, restart, shutdown and persistence acceptance.
- Towny bank debit/refund behavior against a disposable staging Towny server.
- SiegeWar live siege start/end and bidirectional hostility acceptance.
- WorldGuard live region allow/deny acceptance.
- 150-NPC load test for at least 30 minutes with MSPT, CPU, heap, chunk-load and SQLite measurements.

Use `docs/PAPER-ACCEPTANCE.md` for the live test procedure and result record. These items remain **NOT RUN** until performed on a staging server.

## Disposition

The code-level guard lifecycle audit and automated CI verification are complete for this revision. The pull request remains Draft and unmerged; `main` is unchanged.

## Follow-up audit — 2026-11-21

**Audited branch head:** `9429930286c0a7c1588aa84757dcc5799419a012`  
**Pull request:** #1 (Draft; not merged)

The current branch now includes additional guard combat-condition and equipment functionality beyond the 2026-11-14 audit above. Source review confirmed:

- Guard morale and injury condition are stored through `GuardConditionRepository` / `SqliteGuardConditionRepository`; the plugin loads conditions before starting guard runtime tasks and stops the morale service during shutdown.
- Incoming final damage drives morale loss and injury severity. The existing damage listener is registered with `ignoreCancelled = true`, so cancelled damage events are excluded.
- Morale thresholds are clamped from configuration and enforce a separate retreat and rally threshold. The order controller returns a routed guard toward its persisted spawn/post without replacing the durable order.
- Equipment wear uses native Bukkit item durability. The repair command first checks the guard is visible to the player's kingdom (or the player is an administrator); the repair service separately requires a loaded guard within six blocks.
- Repair cost is validated for every supported damaged slot before materials are removed. The repair operation and inventory/entity mutation run on the server thread. Insufficient materials leave both inventory and equipment unchanged in the covered regression test.
- Unsupported item types are reported and left unchanged; the documented recipes cover supported material tiers.

Relevant automated test classes present on the branch include `GuardMoraleServiceTest`, `GuardEquipmentWearServiceTest`, `GuardEquipmentRepairServiceTest`, `GuardOrderControllerTest`, and `SqliteGuardConditionRepositoryTest`. This source audit does not substitute for executing those tests against the current commit.

### Current CI and release disposition

GitHub Actions run **#782** for the audited head was still **in progress** at the time of this report. Java 25 and Gradle setup steps had completed; `Build plugin` was running. No successful build or test result is claimed here.

The live Paper/Towny/SiegeWar/WorldGuard acceptance plan and 150-NPC load test in `docs/PAPER-ACCEPTANCE.md` remain **NOT RUN** until executed on a staging server. The PR remains Draft and unmerged; `main` is unchanged.
