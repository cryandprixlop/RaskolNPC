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

## Follow-up audit — 2026-11-22: daily guard rent and debt

**Audited branch head:** `6042343d2224a1e5eb3508bf8c242740108a2b84`  
**Scope:** Town-bank payer binding, daily rent scheduling, durable payment idempotency, restart recovery, debt progression, deletion, and startup ordering.

Source review confirms:

- Hiring snapshots the placement town UUID into `payerTownId`; subsequent daily rent uses that exact town bank, not the king's/player's personal account or the nation account.
- The rent amount is derived from guard level at hiring. Invalid/non-finite metadata is rejected before a withdrawal; Towny and entity operations are kept on the server thread.
- A unique database constraint on `(npc_id, rent_due_at)` plus `createIfAbsent` prevents a second journal entry for the same guard and due timestamp. The journal entry is committed before calling Towny.
- A definitive Towny success/failure is transitioned only from `IN_PROGRESS`. Exceptions and uncertain persistence outcomes are not retried automatically; unresolved `IN_PROGRESS` entries are moved to `REVIEW_REQUIRED` at startup.
- Confirmed payment/debt effects and NPC state/metadata changes are applied in one SQLite transaction. A paid guard is activated and debt resets to zero; a failed payment increments debt and deactivates the guard. At the configured maximum (clamped to 1–3 days; default 3), a failed payment atomically queues guard deletion and marks the ledger entry applied.
- Startup loads NPC records, recovers rent payments and hiring journal entries, then recovers entities, marks lifecycle ready, and only then starts rent polling. Without Towny, recovery remains available but new withdrawals and polling are disabled.
- Existing tests cover unique due-time idempotency, restart conversion of ambiguous payments to review, one-way payment state transitions, atomic NPC/ledger application and rollback, third-failure deletion/history retention, and review resolution auditing.

**Code-review result:** no source-level defect was identified in the audited rent flow. The design deliberately favors manual reconciliation over a potentially duplicated bank debit when Towny's result is ambiguous.

**Limitations:** this was a source and regression-test review, not a live Towny bank test. The tests must still execute successfully on the current branch head; live Paper/Towny debit, insufficient-balance, server-restart, and 150-NPC acceptance remain **NOT RUN**.

## Follow-up audit — 2026-11-22: guard hiring debit and compensation

**Audited branch head before this report:** `d5f84b016c2fa0856a297417037d33f49ec3b8f7`  
**Scope:** town-bank hiring debit, journal transitions, NPC creation/spawn failures, compensating refund, and restart behavior.

Source review confirms:

- Hiring requires configured Towny authorization, a valid placement, available nation capacity, a payment journal, and level 1–10. Hire cost and daily rent are checked for finite values before reservation or Towny access.
- The durable hiring journal is created before the Towny withdrawal. The payer town ID, nation ID, owner UUID, guard name, level, hire cost, and rent are snapshotted in that record.
- A definitive insufficient-balance result is recorded as `DEBIT_FAILED`; an exception during withdrawal is treated as ambiguous and routed to `REVIEW_REQUIRED`. Neither path creates a guard.
- NPC creation and entity spawning happen only after a confirmed debit. The journal must advance through expected-state transitions; NPC ID attachment is allowed only once.
- If spawn fails, compensation is attempted only after entity removal and persistent NPC deletion both succeed. The journal first enters `REFUND_IN_PROGRESS`; only then is the original town bank credited. A failed/ambiguous Towny deposit or failed final journal transition is sent to manual review.
- Startup moves `IN_PROGRESS`, `REFUND_IN_PROGRESS`, and unresolved `DEBIT_CONFIRMED` records to `REVIEW_REQUIRED`. It never automatically repeats an uncertain debit or refund. Administrator resolutions are recorded with UUID and timestamp and do not themselves call Towny.
- Existing tests cover one-way journal transitions, duplicate payment creation, one-time NPC ID attachment, restart recovery of unfinished debit/refund operations, administrator resolution audit fields, level-scaled pricing, authorization/capacity checks, failed balance, and the simple service's creation-failure refund.

**Code-review result:** no defect was found in the journaled Towny hiring/refund sequence. Its key safety property is deliberate: uncertain bank outcomes stop for reconciliation instead of triggering a potentially duplicated debit or credit.

**Testing limitation:** the repository has unit/regression tests for the journal and policy service, but this review did not execute them. CI for the report's parent commit was still running at the time of review. Live Towny bank behavior, crash-window simulation against a real Towny economy, and Paper entity-spawn acceptance remain **NOT RUN**.


## Final guard-system consistency audit — 2026-11-22

**Audited branch head:** `4e220d66188b469c76e3c37b9ca7e3b5f534c3d1`  
**Scope:** persisted orders and squad membership, runtime movement, territory boundaries, entity death/unload recovery, deletion/capacity lifecycle, and interactions between combat, morale, equipment and rent.

### Results

- Guard orders are validated by type before persistence. The SQLite repository writes the order header and waypoint replacement in one transaction; startup loads the durable snapshot before the shared movement task starts. Deleted or retyped NPCs are excluded from the runtime order cache.
- Squad membership is stored separately from per-guard orders. The repository checks that the NPC is a guard and enforces the configured member ceiling transactionally; the service publishes an immutable in-memory formation snapshot so movement does not query SQLite each tick. Shared squad orders are persisted per member and partial failures are counted rather than silently reported as complete.
- Movement uses one bounded scheduler, checks loaded chunks before moving, and checks hiring-nation territory for the intended step and any sidestep destination. Territory-provider exceptions and linkage failures deny movement. Pursuit candidates are independently checked for registration, hostility, world, range and territory; unknown diplomacy remains non-hostile.
- Combat checks territory for both attacker and target. When WorldGuard is required, both locations must explicitly allow MOB_DAMAGE; a disabled or failing provider denies the action. Morale retreat takes precedence over normal orders and returns the guard toward its saved post without overwriting the durable order. Equipment repair is a separate authorized server-thread operation; rent state is applied through its own durable payment journal and lifecycle state transitions.
- Recovery removes stale or pending-deletion entities before considering respawn. Only ACTIVE records are eligible for recovery; intentionally UNLOADED records are not respawned. Recovery checks the saved spawn chunk and does not force-load it. Unexpected death recovery verifies the exact currently-bound entity UUID and checks pending deletion before spawning a replacement.
- The total NPC creation path reserves capacity before asynchronous insertion and releases the reservation on completion. Guard nation-capacity reservations and payment/deletion journal behavior are covered in their respective service and repository tests. Deletion history is retained where required by the rent/trade audit policies.

**Code-review result:** no additional source-level defect was identified in this consistency pass. This is a source audit; it is not a claim that the current branch's automated tests have executed successfully.

### Remaining validation / release blockers

- CI run `45020506066` for the audited head was still running during this audit; the build step had not completed. No PASS result is claimed.
- Live Paper startup/restart/shutdown, Towny bank debit/refund and debt cases, SiegeWar start/end diplomacy, WorldGuard region behavior, and the 150-NPC/30-minute performance test remain **NOT RUN**. Use `docs/PAPER-ACCEPTANCE.md` on a disposable staging server and record the exact commit and JAR hash.
- Complex obstacle-aware pathfinding and synchronized squad formations remain outside the current implementation. Squad members move independently toward deterministic formation offsets; RALLY uses persisted spread destinations.

**Disposition:** PR #1 remains Draft and unmerged. `main` is unchanged; no release is authorized by this audit.
