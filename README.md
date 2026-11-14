# RaskolNPC

RaskolNPC is a Paper plugin for persistent NPC systems in a Minecraft RPG server. The current development branch contains the resident foundation and the first playable resident systems: professions, schedules, dialogue, delivery quests, reputation, needs, and town business stock.

> **Project status:** Active development. The resident milestone is being implemented and tested on the `feature/resident-behavior-movement` branch. The pull request is still a draft and has not been merged into `main`.

## Implemented resident features

- Persistent resident profiles with all 10 professions and personality traits.
- Weekly schedules with HOME, WORK, MARKET, and COMMUNITY destinations.
- One shared, batched behavior scheduler; no per-NPC repeating tasks.
- Collision-checked movement with small obstacle sidesteps; unloaded chunks are never force-loaded.
- Daily needs progression for hunger, energy, rest, social need, and mood.
- Profile-, profession-, trait-, and reputation-aware YAML dialogue.
- Repeatable resource-delivery quests with persistent assignments and reputation.
- Idempotent submission handling, restart recovery, and administrator review for ambiguous submissions.
- Town business stock shared by profession, stock-based quest demand, atomic resource deposits/withdrawals, and configurable daily stock consumption.
- Administrative tools for reputation, active quests, review queue resolution, and business stock inspection.

The current YAML catalog contains sample delivery tasks for all 10 professions. Currency rewards are intentionally not enabled: resource deliveries award reputation and contribute goods to business stock. A player-money trade provider has not yet been selected.

## Current project constraints

- Global NPC limit: **150**.
- Resident limit: **15 per Town** when Towny is enabled.
- Guard limit: **30 per Nation**.
- Companion limit: **3 per player** (companion gameplay remains a separate milestone).
- Paper API: **26.2**.
- Java: **25**.
- Storage: SQLite with versioned schema migrations.

## Optional integrations

- **Towny** — resident creation authority, Town/Nation context, and existing guard systems. Towny is optional.
- **RaskolClasses** — planned companion class integration; the companion milestone is not part of the current resident work.
- **Economy provider** — a provider-neutral trade integration remains a future decision. No player-money rewards are issued by the resident quest system.

## Resident configuration

Main configuration is in `plugins/RaskolNPC/config.yml`:

- `limits.residents-per-town` — maximum residents per Town (default 15).
- `resident-behavior.interval-ticks` — shared behavior timer interval.
- `resident-behavior.batch-size` — residents processed per timer pass.
- `resident-behavior.movement-step` — maximum movement step.
- `resident-business-stock.daily-consumption-percent` — percentage of stock capacity consumed at each in-game day boundary; set to 0 to disable.

Resident content is configured in:

- `resident-dialogues.yml` — greetings, profession and trait lines, reputation tiers.
- `resident-quests.yml` — delivery requirements, reputation rewards, and business stock targets.

See [Resident systems guide](docs/RESIDENTS.md) for commands and configuration details.

## Build and tests

Use Java 25:

```bash
./gradlew clean build
```

The CI workflow compiles the plugin, compiles test sources, runs JUnit tests, and packages the plugin JAR.

## Documentation

- [Resident systems guide](docs/RESIDENTS.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Roadmap](docs/ROADMAP.md)

## License

No license has been selected yet. Unless and until a license is added, the repository should not be treated as granting permission to reuse, modify, or redistribute its contents.
