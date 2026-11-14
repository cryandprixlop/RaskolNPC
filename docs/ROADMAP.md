# RaskolNPC Roadmap

> **Status:** Long-term product roadmap. Resident and initial guard implementation is present on the active draft pull request; it is not yet a merged or released milestone.

The project is developed in small, reviewable stages. Each stage has an implementation scope, tests, and acceptance criteria. Automated tests do not replace live-server acceptance and load testing.

## Current implementation status — resident milestone

The active development branch contains the resident work:

- Resident profiles and all 10 professions.
- Weekly schedules, destinations, shared batched behavior scheduling, and chunk-safe movement with simple obstacle sidesteps.
- Daily needs progression and asynchronous profile persistence.
- YAML dialogue with greeting, trait, profession, and reputation tiers.
- Repeatable delivery quests, reputation, replay protection, restart recovery, and administrator review resolution.
- Town business stock, delivery-demand gating, atomic deposits/withdrawals, and configurable daily consumption.
- Administrative commands and an operator guide.

The resource-side supply/demand loop is implemented. Player-currency rewards and a player-money marketplace remain intentionally disabled until the server's economy provider and transaction policy are selected. No economy provider is assumed.

The resident milestone still needs full in-server acceptance and load testing at the global NPC cap of 150. The work remains on the open draft pull request and has not been merged into `main`.

## Current implementation status — stages 2–4 in progress

The resident milestone and its automated validation are present on the active pull-request branch. The following work extends the same branch:

- **Stage 2:** SQLite migrations 1–13, versioned schema checks, NPC lifecycle capacity reservations, entity binding persistence, deletion recovery, and restart reconciliation. A newer unsupported schema version is rejected before application startup.
- **Stage 3:** Towny authority/placement and Town-bank operations use existing verified Towny APIs. A provider-neutral relationship policy combines Towny declared alliances/enemies with active SiegeWar attacker/defender pairs; incomplete or unavailable diplomacy remains unknown and cannot authorize hostility. The WorldGuard 7.0.19 adapter is wired into the first guard combat layer and requires explicit `MOB_DAMAGE` permission at both attacker and target locations. Administrator diagnostics report adapter availability.
- **Stage 4:** Towny guard hiring, rent, nation roster, general appointments, payment review, durable guard orders, patrol waypoints, escort, defend, hold, return-to-post movement, persistent squad membership, shared squad order assignment, and basic rally-point spreading are implemented. A squad order is saved per member and reports partial failures; members still move independently through one bounded scheduler, and unloaded chunks are not force-loaded. Guard combat uses bounded batches, configurable damage/cooldown, verified hostile relationships, territory checks, and optional WorldGuard authorization.

**Remaining guard work:** Current pursuit selects the nearest verified hostile registered NPC or Towny-resolved player within the configured radius (default 16 blocks) and temporarily moves toward it without replacing the durable order. If the target becomes invalid, leaves range, or is no longer verified hostile, the guard resumes its saved order. Unknown/unregistered entities, unresolved players, neutral relations, and incomplete diplomacy responses are ignored. Movement is deliberately simple: it checks passability and can sidestep a single blocked step, but it is not full pathfinding around complex terrain. Synchronized squad movement/formation, coordinated squad combat, retaliation policy beyond bounded nearby-target selection, morale, equipment, injury, and recovery policies remain future increments. The guard milestone also still requires live Paper/Towny/SiegeWar/WorldGuard acceptance tests, including failure cases and the 150-NPC load test.

## Phase 0 — Product and technical validation

- Review the agreed NPC systems and resolve ambiguous rules.
- Inspect the actual RaskolClasses repository and document its public API and supported capabilities.
- Verify target Paper 26.2 and Java 25 toolchain/runtime availability.
- Review Towny, Citizens, economy, and region-protection APIs relevant to the intended integrations.
- Decide project license and contribution policy.
- Select persistence technology and define migration requirements.

**Exit criteria:** compatibility findings and technical decisions are documented; no integration is designed around assumed or undocumented APIs.

## Phase 1 — Project foundation

- Establish the build system and Java toolchain.
- Define package/module boundaries and dependency rules.
- Implement plugin lifecycle, configuration validation, logging, and service registration.
- Define stable NPC identifiers, domain events, and core ports.
- Add automated build and test conventions.

**Exit criteria:** the plugin builds against the verified target platform, starts and shuts down cleanly, and optional integrations can be absent without startup failure.

## Phase 2 — Persistence and NPC lifecycle

- Implement versioned persistence and schema migration.
- Define NPC creation, loading, unloading, state transitions, and deletion policies.
- Add safe save behavior and recovery from partial failures.
- Add diagnostics for persistence and lifecycle operations.

**Exit criteria:** test NPC records survive restart and reload; failed writes do not silently destroy state.

## Phase 3 — Shared policies and integration adapters

- Implement permission/authority abstractions.
- Implement shared relationship and hostility policy.
- Add Towny adapter using verified public APIs.
- Add economy and region-protection ports/adapters as supported.
- Add integration capability reporting and graceful degradation.

**Exit criteria:** adapter contracts are tested; unsupported or unavailable integrations fail safely and retain persisted data.

## Phase 4 — Kingdom guards

- Implement nation roster and guard limits.
- Implement town deployments, role permissions, and general appointments.
- Implement guard classes and squads.
- Implement orders, patrols, escort, object protection, defense, rally, and return-to-post behavior.
- Implement tactical combat in incremental layers.
- Add guard morale, equipment, economy, and configurable injury/death/recovery policies.

**Exit criteria:** authority boundaries, roster limits, orders, combat target policies, and persistence are covered by automated tests and an in-server acceptance test.

## Phase 5 — Town citizens and simulation

- Implement citizen profiles, professions, schedules, needs, and relationships.
- Integrate optional Citizens representation through its adapter.
- Implement businesses, employment, production, consumption, and periodic town economy simulation.
- Add event reactions and persistence while NPC representations are unloaded.

**Exit criteria:** citizen data and economy progress remain consistent across unloads/restarts; simulation work is bounded and measurable.

## Phase 6 — Player companions

- Implement ownership and the maximum of three companions per player.
- Integrate the verified RaskolClasses class catalog and supported combat capabilities.
- Implement progression, equipment, owner commands, following, and party coordination.
- Implement companion relationship traits and independent recovery policies.
- Add provider-neutral entitlement checks for Companion+.

**Exit criteria:** ownership, class assignment, progression, inventory safety, summon policies, entitlement expiry, and party behavior pass tests without duplicating or corrupting player/plugin data.

## Phase 7 — Dialogue and quests

- Implement dialogue registry, conditions, actions, and conversation state.
- Support validated YAML content and safe reload.
- Implement authored quests and progress/reward handling.
- Add dynamic quest generation from approved world/economy context.
- Add an in-game content editor after the underlying content model is stable.
- Keep any AI provider optional and validate all consequential actions server-side.

**Exit criteria:** invalid content is rejected safely; quest rewards and state changes are authoritative and cannot be duplicated by replaying interactions.

## Phase 8 — Administration and observability

- Add administrative commands and GUI workflows.
- Add subsystem and adapter diagnostics.
- Add NPC, economy, and performance inspection.
- Add safe configuration editing and reload where supported.
- Add audit logs for privileged actions.

**Exit criteria:** administrators can diagnose and manage supported systems without bypassing permission checks or directly editing internal plugin data.

## Phase 9 — Hardening and release preparation

- Run load tests against the global NPC cap of 150.
- Profile combat decisions, navigation, simulation, and persistence.
- Test restart, crash-recovery, missing-adapter, and migration scenarios.
- Review permission boundaries, exploit surfaces, inventory duplication, and economy transaction safety.
- Document configuration, permissions, integrations, limitations, and upgrade procedures.
- Prepare release packaging and compatibility matrix.

**Exit criteria:** release candidate meets documented performance and correctness budgets, has reproducible builds, and has complete operator documentation.

## Working agreement

- Discuss and approve each significant design decision before implementation.
- Do not modify external repositories or dependencies without explicit authorization.
- Before every upload to this repository, present the exact file list, explain each change, identify replacements/deletions, and ask for permission.
- A general authorization to work on the project is not blanket permission to publish future changes.
