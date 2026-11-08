# RaskolNPC

RaskolNPC is an extensible NPC framework for a Minecraft RPG server built around Paper. Its planned systems include kingdom guards, simulated town citizens, player companions, tactical combat, dialogue, quests, and integrations with Towny and RaskolClasses.

> **Project status:** Early planning and architecture. The repository currently documents the intended design; gameplay systems and integrations are not yet implemented.

## Planned capabilities

- **Kingdom guards** — nation-wide forces, town deployments, squads, patrols, tactical orders, morale, equipment, and configurable upkeep.
- **Town citizens** — professions, schedules, needs, relationships, businesses, and an offline-capable town-economy simulation.
- **Player companions** — class-based combat followers, progression, equipment, configurable recovery, and coordinated party behavior.
- **Dialogue and quests** — authored dialogue trees, conditions and actions, narrative quest chains, and context-driven dynamic tasks.
- **Administration** — tools for managing NPCs, inspecting system state, configuring behavior, and diagnosing integrations and performance.

## Planned integrations

RaskolNPC is designed around adapters so that its core domain logic does not depend directly on optional plugins.

- **Towny** — towns, nations, territorial context, relationships, and treasury integration through supported public APIs.
- **RaskolClasses** — class and combat capabilities through the actual public API exposed by that project.
- **Citizens** — optional physical NPC representation and navigation for town citizens.
- **Vault or another economy provider** — optional economy bridge.

The exact capabilities and compatibility of each integration must be verified against the corresponding plugin version and API before implementation. RaskolNPC will not assume undocumented APIs or modify another plugin's internal data.

## Target platform

- Paper: **26.2** (target; compatibility to be verified during implementation)
- Java: **25**

## Design principles

1. Keep domain logic separate from plugin integrations.
2. Make optional integrations replaceable and safely disableable.
3. Persist NPC identity and state across unloads and server restarts.
4. Perform Bukkit/Paper entity and world operations on the appropriate server thread.
5. Use event-driven and scheduled work rather than expensive per-NPC logic every tick.
6. Make gameplay limits, costs, behavior, and recovery rules configurable.
7. Preserve player-facing and administrative actions behind explicit permission and policy checks.

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — planned modules, boundaries, adapters, persistence, and runtime principles.
- [Roadmap](docs/ROADMAP.md) — proposed implementation phases and validation gates.

## Project status and contributions

The design is being developed incrementally. Architectural decisions and integration contracts will be validated before implementation. Contributions and proposals should respect the project's staged design process.

No license has been selected yet. Unless and until a license is added, the repository should not be treated as granting permission to reuse, modify, or redistribute its contents.
