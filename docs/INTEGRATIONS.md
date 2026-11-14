# Integrations

## Towny

Towny is the authority source for nation membership, town placement, guard hiring permissions, and town-bank transactions. The Towny integration is optional at startup; guard hiring and rent collection are unavailable when Towny is disabled.

## SiegeWar

RaskolNPC optionally integrates with the public API in SiegeWar 3.7.0 (`com.gmail.goosius.siegewar.SiegeWarAPI`).

The adapter reads `SiegeWarAPI.getActiveSieges()` and compares the public attacker/defender government UUIDs. It reports a pair as hostile only while an active siege exists between those governments. It returns `UNKNOWN` for invalid IDs, unrelated nations, missing data, or API failures. The shared relationship policy maps `UNKNOWN` to neutral, never to hostile.

This is deliberately narrow: it does not infer alliance, permanent war, occupation, or hostility from a town name, plugin presence, or stale siege metadata. SiegeWar must be installed and enabled. The dependency is compile-only and the plugin is declared as a soft dependency, so servers without SiegeWar can still start RaskolNPC.

The adapter currently provides the verified diplomacy input for subsequent guard target-selection work. It does not itself make guards attack players or change their movement orders.

## WorldGuard

RaskolNPC optionally compiles against WorldGuard 7.0.19, which supports the project's Paper 26.2 target. The adapter queries `Flags.MOB_DAMAGE` through `RegionQuery` at a supplied location and returns ALLOWED, DENIED, or UNKNOWN. Query failures and missing locations return UNKNOWN. The adapter is initialized only when WorldGuard is installed and enabled; WorldGuard is a soft dependency.

This is a query primitive, not a claim that all guard combat is protected: the guard combat/target execution layer is not yet implemented, so the adapter is not currently used to authorize an attack. When that layer is added, it must treat DENIED as a hard block and UNKNOWN as no permission to attack.