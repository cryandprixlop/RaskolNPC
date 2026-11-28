# Integrations

## Towny

Towny is the authority source for nation membership, town placement, guard hiring permissions, and town-bank transactions. The diplomacy adapter reads nation UUIDs and declared enemy/ally relations from the public Towny API. An alliance is accepted only when both nations list each other as allies; a declared enemy on either side is hostile. Existing nations with neither relation are neutral, while missing nation data is unknown. The Towny integration is optional at startup; guard hiring, rent collection, and Towny-backed diplomacy are unavailable when Towny is disabled.

The player-subject resolver uses a player's Towny resident/nation membership, not the player's current location. Guard combat and pursuit can therefore consider a player only when that membership resolves and the resulting nation relationship is verified hostile. Players without a resolvable Towny nation, neutral players, and unknown relationships are ignored. Territory checks for guard movement and combat use the guard's hiring nation and fail closed on lookup errors.

## SiegeWar

RaskolNPC optionally integrates with the public API in SiegeWar 3.7.0 (`com.gmail.goosius.siegewar.SiegeWarAPI`).

The adapter reads `SiegeWarAPI.getActiveSieges()` and compares the public attacker/defender government UUIDs. It reports a pair as hostile only while an active siege exists between those governments. It returns `UNKNOWN` for invalid IDs, unrelated nations, missing or incomplete data, same-nation comparisons, or API failures. Null siege collections and entries are treated as unavailable data. The shared relationship policy maps `UNKNOWN` to neutral, never to hostile.

This is deliberately narrow: it does not infer alliance, permanent war, occupation, or hostility from a town name, plugin presence, or stale siege metadata. SiegeWar must be installed and enabled. The dependency is compile-only and the plugin is declared as a soft dependency, so servers without SiegeWar can still start RaskolNPC.

The SiegeWar result is composed with Towny diplomacy. A verified active siege takes precedence over Towny's stored alliance relation; absent a siege, Towny's declared alliance/enemy state is used. A verified hostile relationship is required before guard target selection. An unavailable provider or incomplete response cannot authorize pursuit or damage.

## WorldGuard

RaskolNPC optionally compiles against WorldGuard 7.0.19, which supports the project's Paper 26.2 target. The adapter queries `Flags.MOB_DAMAGE` through `RegionQuery` at a supplied location and returns ALLOWED, DENIED, or UNKNOWN. Query failures and missing locations return UNKNOWN. The adapter is initialized only when WorldGuard is installed and enabled; WorldGuard is a soft dependency.

The first bounded guard combat layer uses this adapter when WorldGuard is enabled: both the guard's location and the target's location must explicitly return ALLOWED for `MOB_DAMAGE`. DENIED and UNKNOWN both block the attack; a missing or failed adapter also blocks combat when WorldGuard is enabled. This check is in addition to verified hostile diplomacy and the Towny hiring-nation territory check. It is not a replacement for those checks, nor does it imply that every possible entity damage path on the server is governed by this plugin.

## RaskolClasses

The public repository `hayferdahmer/RaskolClasses` was reviewed at revision `2e407cbe59276e6af88857bf3a0d93595c55c52c` (2026-11-22). The verified public compatibility marker is `dev.raskol.classes.api.RaskolClassesApi`, currently `API_VERSION = 1`.

The current public API exposes combat-profile registration for externally managed living entities (`api.combat.CombatProfileRegistry` / `CombatProfile`) and an item API. It does not currently expose a supported public service for assigning, reading, or changing a player's `PlayerClass`. The six-value `PlayerClass` enum is in `dev.raskol.classes.classsystem`, outside the `api` package; it is an implementation type, not a documented stable integration contract.

RaskolNPC must not couple its domain model to that internal enum or reflect into implementation classes. Before companion class assignment/progression is implemented, obtain an explicit public API contract from the RaskolClasses maintainer (class lookup/assignment, player lifecycle events, and compatibility guarantees). Until then, keep class identity behind a provider-neutral RaskolNPC port and report the RaskolClasses class-assignment capability as unavailable. No changes were made to the RaskolClasses repository.

This review is source-level only. It does not establish runtime compatibility between the two plugins or authorize shading/redistributing RaskolClasses.
