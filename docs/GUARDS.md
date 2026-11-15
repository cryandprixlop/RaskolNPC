# Kingdom guards

RaskolNPC stores guards as persistent NPC records. When Towny is enabled, hiring and roster access are restricted to the king of the nation that owns the placement town (or an administrator). The configured maximum is 30 guards per nation; the global NPC cap remains 150.

## Hiring and rent

- `/raskolnpc guard hire <level> <name>` hires a guard at the player's current town.
- Hiring cost and daily rent scale linearly with guard level.
- The town bank pays both hiring and daily rent.
- Uncertain bank results are written to the payment journal and are not automatically retried.
- Administrators can inspect and resolve uncertain operations with the `guard hire review/resolve` and `guard rent review/resolve` commands. Resolution records the confirmed outcome; it does not move money.

## Roster and generals

- `/raskolnpc guard list` lists guards visible to the player's nation.
- `/raskolnpc guard info <UUID>` displays a guard visible to the player's nation.
- `/raskolnpc guard general appoint <player>` and `dismiss <player>` manage the already-configured Towny nation rank. RaskolNPC does not create or rewrite Towny rank configuration.

## Orders

A nation king or administrator can issue orders to a guard visible in their nation:

- `/raskolnpc guard order <UUID> hold` — remain at the current position.
- `/raskolnpc guard order <UUID> return` — return to the guard's persisted post.
- `/raskolnpc guard order <UUID> defend` — move to the issuer's current position and hold there.
- `/raskolnpc guard order <UUID> escort` — follow the player who issued the order.
- `/raskolnpc guard order <UUID> patrol <x1> <y1> <z1> <x2> <y2> <z2>` — patrol between two waypoints in the issuer's current world.
- `/raskolnpc guard order <UUID> clear` — remove the persisted order.

Orders and patrol waypoints are stored in SQLite. One shared scheduler processes only a configured number of guards per pass. Movement uses small steps, checks passable feet/head blocks, attempts a simple left/right sidestep around a single blocked step, and never force-loads destination chunks. If the destination world or chunk is unavailable, movement pauses until it is available.

The current movement layer is intentionally simple and does not yet perform full pathfinding around complex terrain. A first bounded pursuit layer is now available: guards check nearby entities in a configured radius and temporarily move toward the nearest registered hostile NPC or a player whose Towny membership resolves to a verified hostile nation. Unregistered entities, players whose Towny membership cannot be resolved, neutral/unknown relations, and targets outside the configured radius are ignored. When no eligible target remains, the guard resumes its persisted order on the next movement pass. This is not obstacle-aware pathfinding; movement still uses the existing small-step passability checks and never force-loads chunks. A verified retaliation layer is also active: when a guard receives direct entity damage from a registered hostile NPC or a Towny-resolved hostile player, it prioritizes that attacker for up to 10 seconds. Environmental damage, projectiles, unresolved attackers, neutral/unknown relations, and attackers failing territory/WorldGuard checks do not create retaliation targets. If the attacker dies, leaves range, or ceases to be eligible, the guard falls back to its normal target selection.

## Configuration

`config.yml`:

- `limits.guards-per-nation` — guard roster cap per nation (default 30).
- `guards.order-batch-size` — maximum orders advanced per scheduler pass (default 6).
- `guards.movement-step` — movement step in blocks (default 0.6, maximum 1.5).
- `guards.combat.max-pursuit-distance` — maximum distance for pursuit of explicitly hostile registered NPCs and Towny-resolved players (default 16, maximum 32; set to 0 to disable).
- `guards.base-hire-cost` and `guards.base-daily-rent` — level-one prices.
- `guards.maximum-debt-days` — missed rent threshold before dismissal.
- `guards.morale.retreat-threshold`, `guards.morale.rally-threshold`, and `guards.morale.allied-death-penalty` — morale routing and casualty response.
- `guards.injury.light-damage`, `guards.injury.severe-damage`, and `guards.injury.recovery-delay-seconds` — injury thresholds and quiet-period recovery.
- `guards.equipment.weapon-wear-per-hit` and `guards.equipment.armor-wear-per-hit` — native weapon and armor durability lost per combat hit; zero disables that wear type.

## Integration diagnostics

Administrators can use `/raskolnpc integrations` to inspect detected providers. A detected plugin is not automatically treated as a supported adapter. In the active basic guard combat loop, WorldGuard's `MOB_DAMAGE` flag must explicitly allow both the attacker and target locations; missing adapters and failed/unknown queries deny the attack.


## Guard squads (initial management layer)

Small persistent squads can be created and managed by server administrators. A squad stores its name, creator, and member guard UUIDs in SQLite. Each guard can belong to at most one squad, and the configured squad size is capped at 10 (`guards.squad-max-members`). Deleting a squad removes its membership rows without deleting the guards.

Commands:
- `/raskolnpc guard squad create <name>`
- `/raskolnpc guard squad list`
- `/raskolnpc guard squad add <squad-uuid> <guard-uuid>`
- `/raskolnpc guard squad remove <guard-uuid>`
- `/raskolnpc guard squad delete <squad-uuid>`
- `/raskolnpc guard squad order <squad-uuid> hold|return|defend|escort`
- `/raskolnpc guard squad order <squad-uuid> rally` — gather the squad around the issuing player's current position using separate nearby destinations.
- `/raskolnpc guard squad order <squad-uuid> patrol <x1> <y1> <z1> <x2> <y2> <z2>`
- `/raskolnpc guard squad order <squad-uuid> clear`

A shared order is persisted as an individual order for each current squad member. The command reports successful and failed member updates separately; a failed save for one guard does not hide successful saves for the others. A squad with no members produces a zero-member result. For patrol, defend, escort, and return orders, the movement controller applies deterministic wedge slots around the shared destination, oriented by the destination's yaw. Each member keeps a stable slot based on its guard UUID. The rally command continues to persist separate radial destinations and is not offset a second time. Squad membership is loaded into an immutable in-memory snapshot during startup and refreshed after membership changes; the movement tick never queries SQLite. Guards still advance independently through the bounded movement scheduler, so this is coordinated destination formation rather than shared physics or group pathfinding. Squads now share a focus target during combat: the stable leader selects the nearest verified hostile target, and other members attack that same target only while it remains valid, hostile, inside their own detection radius, and permitted by territory/WorldGuard checks. If the shared target becomes invalid or is no longer eligible for the leader, the lock is discarded and a new target is selected. This is target coordination, not advanced tactical roles or group pathfinding.


## Basic combat

- A bounded shared task checks only a small batch of registered guards per pass.
- Guards can attack registered NPC entities and Towny residents only when a hostile Towny/SiegeWar relationship is verified; players without a resolvable Towny resident/nation, unknown relations, and neutral relations are ignored.
- If WorldGuard is installed, both the attacker and target locations must explicitly allow `MOB_DAMAGE`. Missing or failed WorldGuard checks deny the attack.
- Detection radius, damage, batch size, attack cooldown, and maximum pursuit distance are configurable under `guards.combat`. Combat detection applies a true spherical distance check after Bukkit's nearby-entity query, so diagonal targets outside the configured radius are not attacked.
- Pursuit only changes the movement target temporarily; it does not replace or rewrite the durable order. If the target disappears, dies, leaves range, or is no longer explicitly hostile, the guard returns to its saved order.
- A guard that takes direct damage from a verified hostile entity prioritizes that attacker for up to 10 seconds. Projectiles and environmental damage do not trigger retaliation; all existing hostility, territory, range, and WorldGuard checks are repeated before an attack.

## Morale and recovery

Guard morale is stored in SQLite independently of the spawned entity and survives server restarts. A guard starts at 100 morale. Every point of final incoming damage removes two morale points (rounded up), down to 0. Outgoing combat damage scales linearly from 50% at 0 morale to 100% at 100 morale. Morale begins recovering after two minutes without damage, at one point per minute. At or below `guards.morale.retreat-threshold` (default 20), a guard stops attacking and retreats toward its persisted post instead of pursuing hostiles. Retreat remains active until morale reaches `guards.morale.rally-threshold` (default 35), preventing rapid switching around the threshold. Retreat movement uses the normal territory, passability, and loaded-chunk safeguards; if the post is unavailable, the guard holds rather than force-loading chunks. Both thresholds are configurable, and the rally threshold must be greater than the retreat threshold. The condition row is deleted automatically when its NPC record is deleted. When two or more additional verified hostiles are nearby compared with living same-nation guards, morale loses one point at most once every five seconds. The comparison uses only registered guards and targets that pass the existing hostility and territory checks. When a guard dies, living guards from the same known nation within 12 blocks lose the configurable `guards.morale.allied-death-penalty` (default 8) morale points. Guards without a known nation are not grouped together. Guard injuries are also persisted in SQLite. A single hit of at least `guards.injury.light-damage` (default 4 damage) adds a light injury; a hit at or above `guards.injury.severe-damage` (default 8) adds a severe injury. Severity is capped at two stages and stacks across qualifying hits. Light injuries reduce outgoing damage by 15%, severe injuries by 40%; this multiplier is combined with the morale multiplier. After the configured quiet period (default five minutes without damage), a loaded injured guard regenerates one health point per minute, and injury severity recovers by one stage per quiet period. The recovery timestamps and severity survive restarts. This is an initial injury/recovery model; equipment wear, explicit medical items, healer NPCs, surrender, and death policies remain future work.


## Equipment wear

Guard equipment uses Minecraft's native item durability. A successful direct melee hit by a registered guard reduces the main-hand damageable item's durability by `guards.equipment.weapon-wear-per-hit` (default 1). When a registered guard receives positive damage, each equipped damageable armor piece loses `guards.equipment.armor-wear-per-hit` durability (default 1). Non-damageable items, empty slots, projectile weapons, and non-guard entities are not modified by the weapon-wear handler. Items break at their material's normal maximum durability and are removed from the equipment slot. Set either wear value to 0 to disable that wear type.

Durability is stored by Minecraft with the entity's equipment data, rather than duplicated in the plugin database. It therefore follows normal entity/chunk persistence across unloads and restarts. This feature currently handles durability loss and breakage only; repair costs, blacksmith/healer NPC interactions, and player-facing repair commands remain future work.
