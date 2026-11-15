# Resident systems

This guide documents the resident systems currently implemented in the development branch.

## Resident limits and behavior

- Towny resident creation is authorized by the Towny adapter. When Towny is absent, the plugin can operate in standalone mode.
- The default limit is 15 residents per Town. The global NPC cap is configured separately and defaults to 150.
- Resident schedules are weekly. An entry contains a day, start/end time, activity, and destination type.
- When no schedule entry is active, a resident selects HOME.
- One shared scheduler processes a bounded batch of residents. Movement uses short, collision-checked steps and does not load chunks.
- Needs are advanced once per in-game day in batches. They are persisted with the resident profile.

## Commands

All commands use `/raskolnpc` (alias: `/rnpc`).

### Player commands

| Command | Description |
|---|---|
| `resident quest accept <residentUUID> <questID>` | Accept a delivery quest |
| `resident quest submit <residentUUID> <questID>` | Deliver the required items |
| `resident quest abandon <residentUUID> <questID>` | Cancel an accepted quest before starting a submission |
| `resident quest reputation` | View your reputation |
| `resident quest active` | View your active resident quests |

Quest accept/submit/abandon and own reputation/active-quest queries do not require the administrative permission. An assignment cannot be abandoned once its submission is in progress or awaiting review.

### Administrative commands

Requires `raskolnpc.admin`.

| Command | Description |
|---|---|
| `resident quest reputation <playerUUID>` | View a player's reputation |
| `resident quest active <playerUUID>` | View a player's active quests |
| `resident quest review` | List up to 25 submissions requiring review |
| `resident quest resolve <playerUUID> <residentUUID> <questID> complete` | Confirm delivery, complete the quest, and credit its saved reward once |
| `resident quest resolve <playerUUID> <residentUUID> <questID> retry` | Clear the uncertain reservation without a reward so the player can retry |
| `resident stock <residentUUID>` | Inspect the resident business stock and current delivery demand |
| `resident schedule list <residentUUID>` | View the weekly schedule |
| `resident schedule add <residentUUID> <day> <start> <end> <activity> <destination>` | Add a schedule entry |
| `resident setpoint <residentUUID> <HOME|WORK|MARKET|COMMUNITY> [label]` | Save the player's current location as a destination |
| `resident delpoint <residentUUID> <HOME|WORK|MARKET|COMMUNITY>` | Delete a destination |
| `resident reload-dialogues` | Reload dialogue YAML after validation |

Schedule days accept English day names and the supported Russian abbreviations. Times use `HH:mm`. Activities are `SLEEP`, `WORK`, `EAT`, `SOCIALIZE`, `LEISURE`, `REST`, and `ERRAND`.

A `complete` review resolution uses the reward and stock snapshot captured when the submission was reserved. A `retry` resolution removes the review record only if the original assignment is still active. Neither operation can be applied twice.

## Quest configuration

Example:

```yaml
quests:
  miner_iron_delivery:
    profession: MINER
    required-material: IRON_INGOT
    required-amount: 32
    reputation-reward: 5
    stock-target: 96
```

- `profession` must match the resident's profession.
- `required-material` is a Bukkit material key.
- `required-amount` is the number of items required.
- `reputation-reward` is the reputation credited after confirmed delivery.
- `stock-target` is the target business stock for that profession/material. A value of 0 disables stock gating. A non-zero target must be at least the delivery amount.
- A stock-gated catalog may define only one quest per profession/material pair. This keeps active delivery reservations aligned with the shared stock key; use separate materials for separate simultaneous delivery lines.

All 10 professions have sample quests in the shipped YAML. The business stock key is Town + Profession + Material. In standalone mode, stock is isolated per resident.

Resources are removed from the player's inventory on the server thread. The assignment, submission status, reputation, and business stock update are finalized in one SQLite transaction. Uncertain boundaries are marked `REVIEW_REQUIRED` and must be resolved by an administrator.

## Business stock and demand

- Successful delivery adds the delivered quantity to business stock, capped at the configured stock target.
- A quest is offered only when another full delivery fits below the target. Accepted active deliveries also reserve that demand capacity, preventing several players from overfilling the same business stock.
- Business stock can be withdrawn atomically through the repository/service API; stock cannot become negative.
- Stock can be transferred between different professions in the same Town. The source debit and target capacity check share one SQLite transaction. Transfers between different towns, between identical professions, or beyond available source/target capacity are rejected.
- At each in-game day boundary, the shared resident scheduler applies the configured daily consumption percentage to stock rows. This gradually restores demand.
- `resident-business-stock.daily-consumption-percent` defaults to 10. Set it to 0 to disable automatic consumption; valid values are 0–100.

No currency is awarded by resident quests. A player-facing money economy or market provider has not been selected and is intentionally not assumed.

## Blacksmith interaction

When a resident's profession is `BLACKSMITH`, the conversation offers repair actions for up to five loaded guards within six blocks that currently have damaged equipment. Selecting an action invokes the standard `/raskolnpc guard repair <UUID>` workflow; kingdom authorization, distance, supported item recipes, and the player's material inventory are validated again by the repair service. Guards with no damaged equipment are not offered. The blacksmith interaction does not bypass guard permissions or provide free repairs.

## Dialogue configuration

`resident-dialogues.yml` supports:

- `greetings` — randomized greetings.
- `traits` — lines selected from one of the resident's personality traits.
- `professions` — profession-specific lines.
- `reputation` — numeric thresholds; the highest reached threshold contributes one additional line.

Invalid YAML does not replace the currently active dialogue catalog. After editing, run `resident reload-dialogues`.

## Persistence and recovery

SQLite migrations currently cover NPC identity, resident profiles, schedules, quests, reputation, review records, reward snapshots, and business stock. The resident quest flow is designed to prevent duplicate rewards after replay or restart. Review records should be inspected before manual resolution; `complete` means an administrator has verified that the delivery should be treated as completed.

## Performance notes

- No per-resident repeating tasks are created.
- Resident movement checks chunk-loaded state before block access and does not force-load chunks.
- Daily needs are processed in batches and persisted asynchronously.
- Business stock consumption is one database update per in-game day boundary.
- The intended global load ceiling is 150 NPCs; an in-server acceptance/load test remains part of release preparation.


## Resident production recipes

The repository exposes an atomic production operation for a resident business. A recipe consumes every configured input from that resident profession's town stock and credits its output in the same SQLite transaction. If any input is missing or the output would exceed its existing stock capacity, the transaction leaves all stock unchanged. Recipe definitions and NPC schedules are intentionally separate from this persistence primitive.


## Production recipe configuration

Production recipes are loaded from `plugins/RaskolNPC/production-recipes.yml`. The complete file is parsed and validated before the active catalog is replaced; an invalid reload leaves the previous catalog in memory.

Example:

```yaml
recipes:
  blacksmith_iron_sword:
    profession: BLACKSMITH
    inputs:
      IRON_INGOT: 4
      COAL: 2
    output:
      material: IRON_SWORD
      amount: 1
      capacity: 32
```

- Each recipe has a unique 1–64 character ID and one required profession.
- `inputs` contains 1–16 valid Bukkit item material keys, each with an integer amount from 1 to 2304.
- `output.material` must be a valid Bukkit item material; `output.amount` is 1–2304.
- `output.capacity` is the maximum stored amount for the output material and cannot be lower than the produced amount.
- `minimum-skill-level` optionally gates a recipe behind APPRENTICE, NOVICE, SPECIALIST, EXPERT, or MASTER. It defaults to APPRENTICE for existing recipes and old configuration files.
- Recipe execution is available through the resident production service API. It verifies the resident type, profession, and minimum mastery tier before consuming anything, then consumes all inputs and credits output in the existing atomic SQLite transaction. Locked recipes leave stock unchanged and award no experience.
- During a resident's scheduled `WORK` activity, the shared behavior timer attempts production when the resident has reached its configured `WORK` destination. Recipes are tried in stable ID order; the first recipe with sufficient inputs and output capacity succeeds.
- Production yield scales with the resident's persisted mastery tier for the current profession: APPRENTICE +0%, NOVICE +5%, SPECIALIST +10%, EXPERT +15%, MASTER +20%. Recipe unlock requirements are configured independently with `minimum-skill-level`, so servers can introduce advanced production without changing the base yield curve. Fractional bonus items are rounded down; recipe inputs are unchanged and the atomic stock operation still enforces output capacity.
- Every successful production operation awards 10 XP to the resident's recipe profession; failed recipes award nothing. XP and the resulting mastery tier are persisted together in one SQLite transaction.
- Cumulative XP thresholds are APPRENTICE 0, NOVICE 100, SPECIALIST 300, EXPERT 700, and MASTER 1500. Progress beyond MASTER is retained up to the integer storage limit but does not increase the tier.
- Saving a resident profile does not reset accumulated XP. Existing databases are upgraded by schema migration 17; previous skill rows start at 0 XP.
- Each resident has a work-action cooldown, and an in-flight guard prevents overlapping asynchronous production attempts. Production is bounded by the existing resident behavior batch size; it does not create a task per NPC.
- The cooldown is configured by `resident-behavior.work-action-interval-ticks` in `config.yml` (default 60; valid range 1–24000 ticks).
- A recipe is not run outside a `WORK` schedule entry or when the resident is not at its work destination. If no recipe can be completed, no stock is changed and a later work cycle may retry.
- Production stock is persisted in SQLite, so successful output survives server restarts. No offline catch-up production is simulated: only work cycles processed while the server is running can produce goods.

The shipped file includes example recipes for a blacksmith, cook, and builder. Extend it with server-specific recipes after checking material names and stock capacities.
