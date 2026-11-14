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

The current movement layer is intentionally simple: it does not yet perform full pathfinding around complex terrain, perform full pathfinding around complex terrain, enforce region flags in an active combat loop, or implement combat target execution, squads, rally coordination, morale, equipment, injury, and recovery policies. Those remain later guard-system increments and must not be considered complete merely because orders are persisted.

## Configuration

`config.yml`:

- `limits.guards-per-nation` — guard roster cap per nation (default 30).
- `guards.order-batch-size` — maximum orders advanced per scheduler pass (default 6).
- `guards.movement-step` — movement step in blocks (default 0.6, maximum 1.5).
- `guards.base-hire-cost` and `guards.base-daily-rent` — level-one prices.
- `guards.maximum-debt-days` — missed rent threshold before dismissal.

## Integration diagnostics

Administrators can use `/raskolnpc integrations` to inspect detected providers. A detected plugin is not automatically treated as a supported adapter. WorldGuard's API adapter can query the `MOB_DAMAGE` flag, but guard combat enforcement is not yet wired to it; RaskolNPC does not claim that guard attacks are currently blocked by region flags.
