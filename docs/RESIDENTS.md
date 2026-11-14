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
| `resident quest reputation` | View your reputation |
| `resident quest active` | View your active resident quests |

Quest accept/submit and own reputation/active-quest queries do not require the administrative permission.

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

All 10 professions have sample quests in the shipped YAML. The business stock key is Town + Profession + Material. In standalone mode, stock is isolated per resident.

Resources are removed from the player's inventory on the server thread. The assignment, submission status, reputation, and business stock update are finalized in one SQLite transaction. Uncertain boundaries are marked `REVIEW_REQUIRED` and must be resolved by an administrator.

## Business stock and demand

- Successful delivery adds the delivered quantity to business stock, capped at the configured stock target.
- A quest is offered only when another full delivery fits below the target.
- Business stock can be withdrawn atomically through the repository/service API for a future market integration; stock cannot become negative.
- At each in-game day boundary, the shared resident scheduler applies the configured daily consumption percentage to stock rows. This gradually restores demand.
- `resident-business-stock.daily-consumption-percent` defaults to 10. Set it to 0 to disable automatic consumption; valid values are 0–100.

No currency is awarded by resident quests. A player-facing money economy or market provider has not been selected and is intentionally not assumed.

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
