# Integrations

## Towny

Towny is the authority source for nation membership, town placement, guard hiring permissions, and town-bank transactions. The Towny integration is optional at startup; guard hiring and rent collection are unavailable when Towny is disabled.

## SiegeWar

RaskolNPC optionally integrates with the public API in SiegeWar 3.7.0 (`com.gmail.goosius.siegewar.SiegeWarAPI`).

The adapter reads `SiegeWarAPI.getActiveSieges()` and compares the public attacker/defender government UUIDs. It reports a pair as hostile only while an active siege exists between those governments. It returns `UNKNOWN` for invalid IDs, unrelated nations, missing data, or API failures. The shared relationship policy maps `UNKNOWN` to neutral, never to hostile.

This is deliberately narrow: it does not infer alliance, permanent war, occupation, or hostility from a town name, plugin presence, or stale siege metadata. SiegeWar must be installed and enabled. The dependency is compile-only and the plugin is declared as a soft dependency, so servers without SiegeWar can still start RaskolNPC.

The adapter currently provides the verified diplomacy input for subsequent guard target-selection work. It does not itself make guards attack players or change their movement orders.

## WorldGuard

WorldGuard detection is diagnostic only at this stage. No region protection behavior is claimed until the adapter can be tested against the target WorldGuard API and the exact NPC actions that must be restricted.