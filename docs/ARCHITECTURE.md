# RaskolNPC Architecture

> **Status:** Conceptual architecture with implementation notes. Some resident foundations described below now exist in code; this document does not claim that all planned domains or integrations are complete.

## 1. Goals and constraints

RaskolNPC is intended to provide three major NPC domains:

1. **Kingdom guards** — a nation-level force deployed to towns and managed through ranks, squads, orders, and tactical behavior.
2. **Town citizens** — persistent simulated residents with professions, schedules, needs, relationships, and economic activity.
3. **Player companions** — class-based combat followers with progression, equipment, recovery, owner commands, and coordinated party behavior.

Dialogue, quests, administration, persistence, and shared services support these domains.

Initial target platform:

- Paper 26.2
- Java 25

These are project targets. Build tooling, API availability, and runtime compatibility must be verified before implementation.

### Design principles

- Keep the core independent of Towny, RaskolClasses, Citizens, Vault, and region-protection plugin internals.
- Integrate through narrow adapters backed by documented, supported APIs.
- Treat optional integrations as capabilities that can be unavailable without corrupting saved data.
- Keep authoritative gameplay decisions and permission checks on the server.
- Persist durable identity and state; do not rely on a live Bukkit entity as the sole source of truth.
- Avoid expensive decision-making for every NPC every tick.
- Make limits, economy rules, combat policies, recovery, and behavior configurable.
- Never silently discard NPCs, inventories, progression, or financial state.

## 1.1 Current implementation notes

The current development branch has concrete implementations for:

- Paper plugin lifecycle, SQLite persistence, versioned migrations, NPC identity and lifecycle.
- Towny-backed resident creation authorization and town-level resident limits.
- Resident profiles, professions, schedules, destinations, batched movement, daily needs, and dialogue.
- Resident delivery quests, reputation, submission recovery, administrator review, and business stock/demand.
- Existing guard hiring/rent foundations.

The following remain separate or incomplete: full tactical guard behavior and SiegeWar integration, player companions and RaskolClasses integration, resident relationship/family simulation, and a player-money marketplace. The latter is intentionally not enabled until the server's economy provider and transaction policy are selected.

Resident business stock currently uses a Town + Profession + Material key (or a resident-specific key in standalone mode). Delivery completion, reputation credit, and stock deposit are committed in one SQLite transaction. A provider-neutral stock withdrawal operation is available for later market integration.

## 2. High-level layers

The proposed dependency direction is:

```text
Paper / Bukkit runtime
        |
Plugin bootstrap and lifecycle
        |
Application services and use cases
        |
Domain modules and shared domain services
        |
Ports (interfaces)
        |
Integration adapters and infrastructure
        |
Towny / RaskolClasses / Citizens / economy / storage
```

The domain and application layers must not import implementation classes from optional plugins. Adapters translate external plugin concepts into RaskolNPC-owned domain concepts.

## 3. Proposed module boundaries

The package and Gradle module layout is not finalized. The following are logical boundaries, not a commitment to a particular physical module layout.

### 3.1 Core and lifecycle

Responsibilities:

- plugin startup and shutdown;
- dependency and capability discovery;
- service registration and lifecycle ordering;
- configuration loading and validation;
- event and task coordination;
- graceful degradation when optional integrations are absent.

The core owns shared identifiers, domain events, service contracts, and common validation. It should not contain Towny- or RaskolClasses-specific logic.

### 3.2 Guard domain

Responsibilities:

- nation guard roster and the agreed maximum of 30 guards per Nation;
- town deployments and the agreed maximum of 15 citizens per Town (citizen limits belong to the citizen domain);
- roles and authority checks for nation leaders, town mayors, generals, and administrators;
- squads, captains, membership, rally points, routes, and orders;
- guard classes: infantry, archer, heavy warrior, captain, mage, and gate guard;
- patrols, point/object protection, escort, town defense, rally, and return-to-post behavior;
- morale, loyalty, discipline, fear, and relationships;
- guard-specific injury, death, recovery, respawn, equipment, and economy policies.

A Nation has a maximum of 30 guards. Town mayors may manage deployments and posts within their authority, but may not take control of another town's guards without permission. Generals are appointed by the nation leader. Exact Towny rank and permission mappings must be verified against the supported Towny API.

### 3.3 Citizen domain

Responsibilities:

- persistent citizen identity and generated or assigned personal details;
- home, profession, workplace, schedule, skills, needs, preferences, and social relationships;
- families and reactions to relevant town events;
- periodic needs and life simulation;
- professions such as blacksmith, farmer, merchant, cook, miner, builder, healer, craft worker, and civilian guard;
- business, employment, production, consumption, wages, taxes, supply, demand, and town economic simulation.

The agreed design target is a maximum of 15 citizens per Town. Citizen simulation should continue through periodic aggregate processing even when their physical NPC representations are unloaded.

Citizens is planned as an optional adapter for physical representation and movement. If it is absent, citizen simulation data should remain stored; physical citizen behavior may be unavailable or disabled according to configuration.

### 3.4 Companion domain

Responsibilities:

- ownership and the agreed maximum of 3 companions per player;
- class assignment from classes actually available through RaskolClasses;
- progression state, experience, levels, and persistence;
- individual equipment inventory, durability, repair, and item-quality metadata;
- owner commands and autonomous combat behavior;
- follow/navigation policy and party formations;
- companion relationships, loyalty, trust, fear, and personality traits;
- companion-specific injury, death, recovery, respawn, and subscription-entitlement policy.

Companion classes are not independently redefined in RaskolNPC. Class selection is planned to use the available RaskolClasses catalog, with configurable probabilities (equal by default). The selected class is persisted. Manual class changes are not planned for the first release.

The subscription entitlement integration must be provider-neutral. If an entitlement expires, companion records and progression remain saved while subscription-gated use is suspended according to the agreed policy. In-game acquisition remains possible; paid benefits must not create unconditional combat superiority.

### 3.5 Dialogue and quest domains

Proposed dialogue services:

- `DialogueEngine`
- `DialogueRegistry`
- `DialogueConditions`
- `DialogueActions`
- `DialogueMemory`
- `DialogueProvider`

Proposed quest services:

- `QuestEngine`
- `QuestRegistry`
- `QuestGenerator`
- `QuestConditions`
- `QuestRewards`
- `QuestProgress`
- `QuestDialogueBridge`

Dialogue should support authored trees, conditional and hidden branches, player/NPC history, reputation and relationship checks, and controlled actions. YAML authoring and an in-game editor are both planned. Reloads must validate content before activation and avoid leaving active conversations in an inconsistent state.

Quests combine authored narrative chains with dynamic tasks generated from NPC needs, town economy, wars, shortages, and other world context. An AI-backed dialogue provider may be added later, but must remain optional. Any consequential action, reward, or state mutation must be validated by server-side rules.

### 3.6 Shared domain services

Likely shared services include:

- relationship and hostility policy;
- permission and authority evaluation;
- NPC identity and lifecycle management;
- navigation scheduling and movement policy;
- combat target validation;
- economy abstractions and accounting;
- equipment and item compatibility;
- recovery and lifecycle policy;
- event dispatch and scheduled simulation;
- audit logging and diagnostics.

Shared services should expose policy interfaces or profiles where guard and companion behavior intentionally differs. For example, both may use a common hostility service while applying distinct target-selection rules.

## 4. Integration adapters

Adapters isolate external APIs and normalize their data into RaskolNPC-owned types.

| Adapter | Intended responsibility | Validation required |
|---|---|---|
| Towny | Town/Nation context, membership, relations, treasury access | Supported public API, event coverage, permission semantics, treasury operations |
| RaskolClasses | Class catalog and supported combat/stat/item capabilities | Actual API surface, lifecycle, mutation support, version compatibility |
| Citizens | Physical NPC representation and navigation for citizens | Supported API, entity lifecycle, pathing behavior |
| Economy | Balances and transactions | Provider API, transaction failure handling, Vault availability |
| Region protection | Summon and movement policy checks | Supported integrations and protection semantics |
| Entitlements | Companion+ subscription status | Provider-neutral contract, expiry and outage behavior |

No undocumented capability is assumed. In particular, RaskolNPC must inspect the real RaskolClasses API before deciding how to apply companion progression, equipment effects, or combat abilities. It must not mutate another plugin's private profile data.

If an integration is missing, its dependent feature should be disabled or degraded safely, with saved data retained. The core should continue to start when optional integrations are unavailable.

## 5. Authority, permissions, and policy

Permissions must be checked at the point of each consequential action, not only when a GUI is opened.

Planned authority model:

- Nation leader: nation guard roster, appointments, upgrades, and force-wide control.
- Town mayor: guard deployments, posts, and patrols in their town, within delegated authority.
- General: assigned squad and combat-order control.
- Ordinary resident: no guard-management authority by default.
- Companion owner: commands and equipment access for their own companions.
- Administrator: technical and administrative control, gated by explicit permissions.

Towny status is an input to policy, not an unconditional attack authorization. War, alliance, neutrality, criminal status, territorial intrusion, hostile mobs, and individual allow/deny lists may inform target evaluation. The final decision must also respect server PvP rules and region protections.

## 6. Persistence and consistency

Persistent state must be independent of whether an NPC is currently spawned.

The persistence design must support:

- stable NPC identifiers;
- versioned data schemas and migrations;
- guard rosters, squads, orders, deployments, and assignments;
- citizen profiles, schedules, relationships, businesses, and economic state;
- companion ownership, class, progression, equipment, and entitlement state;
- dialogue and quest progress;
- recovery/death state and relevant timestamps;
- financial records and pending transactions where applicable.

The concrete storage engine is not selected in this document. Before implementation, compare candidate storage options for transactional safety, migration support, operational simplicity, and expected workload.

Inventory and economy operations require particular care: use explicit transaction boundaries, idempotency where possible, and recovery paths for partial failures. Never duplicate or silently delete equipment or currency when a save or provider operation fails.

## 7. Runtime scheduling and performance

The server-wide NPC cap is 150. The agreed per-owner/per-location limits are:

- Guards: up to 30 per Nation.
- Citizens: up to 15 per Town.
- Companions: up to 3 per player.

These limits are independent constraints; implementation must also enforce the global cap of 150 NPCs.

Runtime work should be divided by frequency and urgency:

- immediate event handling for damage, commands, deaths, and policy changes;
- short-interval movement/combat updates only for active entities that need them;
- slower decision cycles for tactical planning and needs;
- batched periodic citizen/economy simulation;
- persistence writes that avoid blocking the server thread.

Bukkit/Paper world and entity APIs must only be accessed from valid server execution contexts. Async work may be used for suitable storage or pure computation, but must not access live Bukkit world/entity state unsafely. Navigation and pathfinding should be scheduled and budgeted rather than recalculated continuously for every NPC.

Performance instrumentation should track active entities, decision-cycle time, pathfinding load, persistence latency, and adapter errors.

## 8. Configuration and administration

The planned administration experience includes commands and GUI tools for NPC management, subsystem configuration, diagnostics, content editing, integration status, economy inspection, and performance monitoring.

Configuration should be validated before it is applied. Where hot reload is supported, changes should be staged and activated safely. Settings that cannot be changed safely at runtime must be documented as restart-required.

Administrative permissions must remain separate from in-game authority such as nation leader or town mayor.

## 9. Open technical decisions

The following are deliberately not finalized by this conceptual architecture:

- Gradle module and package layout;
- persistence engine and schema;
- exact Paper API usage and plugin lifecycle details;
- exact Towny, Citizens, Vault, region-protection, and RaskolClasses API contracts;
- supported external plugin versions and optional dependency declarations;
- exact scheduling intervals and performance budgets;
- public extension API and compatibility policy;
- license and contribution policy.

Each item should be resolved with evidence and reviewed before the implementation that depends on it.
