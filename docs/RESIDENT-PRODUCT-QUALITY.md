# Resident product quality and stock batches

## Status

Batch-backed stock and mastery-derived production quality are implemented on the active draft PR. Quality is available to internal service consumers but is not yet displayed to players or connected to a player-currency marketplace. No economy provider or price multiplier has been assumed.

## Implementation progress

- Schema migration 18 creates `resident_business_stock_batches`, validates quality tiers, adds a FIFO index, and backfills positive legacy stock as `STANDARD` / `LEGACY` batches.
- Quest deposits, withdrawals, profession transfers, production inputs/outputs, and daily consumption update batches and aggregate stock in the same SQLite transaction.
- Withdrawals and daily consumption consume FIFO; profession transfers preserve quality distribution.
- Production quality follows persisted profession mastery: `APPRENTICE → BASIC`, `NOVICE → STANDARD`, `SPECIALIST → FINE`, `EXPERT/MASTER → MASTERWORK`.
- The quantity-only production API remains compatible and defaults to `STANDARD`.
- `ResidentQuestService.businessStockBatches(...)` exposes batches to internal consumers; its quality-filtered overload supports market listing queries without changing stock.
- No public gameplay labels, sale prices, purchase operations, or player-currency rewards are enabled yet. A market adapter must define price policy and atomic quality-specific reservation before sales can be exposed.

## Problem

The current `resident_business_stock` table stores one aggregate quantity per business, profession, and material. Production, delivery deposits, withdrawals, inter-profession transfers, and daily consumption update that aggregate row directly. A single quality value on that row would be incorrect as soon as goods of different quality are combined.

## Decision

Represent physical stock as immutable-quality batches and retain the existing aggregate table as a compatibility projection.

A batch is identified by a generated ID and contains:

- `business_id`, `profession`, and `material` — the existing stock identity;
- `amount` — positive remaining quantity;
- `quality` — a validated quality tier;
- `created_at` — creation time used for deterministic ordering;
- `source_type` and optional `source_ref` — provenance for diagnostics and future audit.

The initial quality vocabulary is:

| Tier | Meaning |
|---|---|
| `BASIC` | Ordinary goods from an inexperienced producer |
| `STANDARD` | Baseline goods, including migrated legacy stock |
| `FINE` | Goods made by a skilled producer |
| `MASTERWORK` | Exceptional goods from a master producer |

The first gameplay implementation should use a deterministic mapping from the resident's persisted profession skill tier to product quality. Avoid random quality rolls until the server's balance rules and configuration contract are explicitly designed. Recipe minimum-skill requirements remain independent from output quality.

## Compatibility contract

- Keep `resident_business_stock` and its current primary key/capacity semantics during the transition.
- Add a versioned migration that creates the batch table and backfills every existing positive aggregate row as one `STANDARD` batch. Existing zero-amount rows do not need a batch.
- Keep aggregate reads and capacity checks compatible with existing command/service callers.
- Every stock mutation must update batches and the aggregate projection in the same SQLite transaction. No code path may update only one representation.
- Existing public repository methods keep their signatures unless a new quality-aware read is explicitly added. Existing callers that only need quantity continue to see the same aggregate amount.

## Mutation semantics

- **Quest delivery/deposit:** create a `STANDARD` batch initially. A later increment may pass an explicit quality when the source has a validated quality rule.
- **Production:** consume input batches FIFO (oldest first), then create one output batch with quality derived from the producer's persisted profession skill at production time.
- **Withdrawal/purchase:** consume FIFO by default. Add a separate explicit quality-selection operation only if a player-facing market needs it; do not silently change existing callers to prefer expensive goods.
- **Profession transfer:** move the source batches' quality and remaining provenance into target batches; do not flatten them into one target batch.
- **Daily consumption:** remove quantity FIFO across batches and update the aggregate projection atomically.
- **Capacity:** continue to enforce capacity against the sum of all remaining batches for the same business/profession/material.
- **Zero balances:** delete exhausted batches; retain or delete zero aggregate rows according to existing repository behavior, consistently.

## API direction

Introduce a small quality-aware domain model (quality enum and stock-batch record) and repository operations for listing batches and adding/removing/transferring batch quantities. Centralize mutations so production, quest completion, withdrawals, transfers, and daily consumption cannot drift out of sync.

Do not expose a quality label through commands, dialogue, item metadata, or economy integrations until the persisted batch quantity can be read back and all existing mutation paths preserve it.

## Required tests and acceptance criteria

1. Migration from an existing database backfills each positive aggregate row as exactly one `STANDARD` batch without changing amount or capacity.
2. Fresh databases create the same batch schema.
3. Aggregate quantity always equals the sum of remaining batch quantities after each mutation.
4. Capacity rejection leaves both batches and aggregate rows unchanged.
5. Production consumes all required inputs atomically and records output quality.
6. Withdrawals and daily consumption remove only the requested quantity, in deterministic FIFO order.
7. Transfers preserve quality distribution and roll back both sides on any failure.
8. Quest completion/review resolution creates stock exactly once and does not create duplicate batches.
9. Existing quantity-only commands and repository consumers remain behaviorally compatible.
10. Full test suite and CI pass before quality is enabled in gameplay.

## Rollout sequence

1. Add domain types and schema migration with legacy backfill.
2. Implement transactional batch repository primitives and consistency tests.
3. Route every existing stock mutation through the batch primitives.
4. Add quality-aware production output and service reads.
5. Add player/admin presentation only after end-to-end persistence tests pass.

## Quality-based valuation

The storage/economy layer now exposes `ResidentProductValuation`, which
calculates prices in the economy's smallest currency unit using integer
fixed-point arithmetic (basis points) rather than floating point:

| Quality | Default multiplier |
| --- | ---: |
| BASIC | 80% |
| STANDARD | 100% |
| FINE | 125% |
| MASTERWORK | 175% |

The caller supplies the material's configured base unit price. The valuation
helper applies half-up rounding to the unit price, validates positive lot
amounts, and detects total-value overflow. These are initial balancing
defaults, not a substitute for per-material pricing configuration or a
market adapter. No currency is charged or credited by this helper.

## Quality-specific stock reservation

The repository exposes an atomic quality-filtered withdrawal operation for market
adapters. It selects only batches matching the requested quality, in FIFO order
within that tier, and updates the aggregate stock projection in the same SQLite
transaction. If the requested tier does not contain the full quantity, the
operation returns `false` without changing any stock. The resident quest service
validates that the target NPC is a resident before exposing this operation.

This reservation primitive is now used by the durable trade journal. Currency
debit/credit and inventory delivery are delegated to provider interfaces; a
concrete Vault/player-wallet adapter and a Bukkit inventory-delivery adapter
still need to be supplied before player purchases are enabled in gameplay.

## Resident trade offers

The resident quest service can expose a read-only market view grouped by product
quality. Each offer contains the resident, normalized material key, quality,
available quantity, unit price, and total value in the economy's smallest
currency unit. Quantities are aggregated from persisted batches of that exact
quality, and prices use the quality valuation rules above.

Offer creation does not reserve or remove stock. A purchase flow must still
perform a fresh atomic quality-specific withdrawal immediately before delivery
and must coordinate that operation with the economy provider using a durable,
idempotent trade state. The supplied base price is intentionally provided by
the caller; this module does not assume a universal material price list or
silently couple market prices to Towny's guard-rent economy adapter.

## Durable trade journal and reservations

Schema migration 19 adds `resident_trade_transactions`, a durable purchase journal
with a unique idempotency key, buyer/resident identity, material, quality,
quantity, minor-unit prices, provider reference, status, and timestamps.

`SqliteResidentTradeRepository.reserve` reserves the requested quality tier and
inserts the journal row in the same SQLite transaction. Retrying an identical
idempotency key returns the original transaction without a second stock debit;
reusing the key with different purchase parameters is rejected. State changes
use compare-and-set transitions, so stale callbacks cannot overwrite a newer
payment state.

The provider-neutral `ResidentTradeService` coordinates the purchase lifecycle:
reserve stock, persist `PAYMENT_PENDING`, charge, persist `PAID`, deliver, and
persist `DELIVERED`. A definitive payment decline atomically cancels the trade
and restores the exact quality tier to stock. A definitive delivery failure
starts an idempotent refund; uncertain payment, delivery, or refund outcomes
move to `REVIEW_REQUIRED`.

`ResidentTradePaymentProvider` and `ResidentTradeDeliveryProvider` are explicit
integration boundaries. Implementations must use the trade ID as their external
idempotency key, and Bukkit/Vault implementations must marshal server API calls
onto the main thread. A delivery provider must distinguish definitive failure
from uncertain/possibly-partial delivery; only the former is automatically
refunded.

The first concrete delivery adapter, `BukkitResidentTradeDeliveryProvider`,
uses a persistent item metadata marker containing the trade ID. It checks for
previously delivered quantity and tops up only the missing amount, and simulates
inventory capacity before mutating the real inventory. This makes retries
idempotent and helps recover from partially persisted delivery. The adapter is
not yet wired into the plugin runtime because a player-economy provider and
admin reconciliation controls are still outstanding.

Startup now runs trade recovery. All unfinished states are moved to
`REVIEW_REQUIRED` rather than guessed as paid, unpaid, or delivered. This
prevents automatic duplicate charging or item delivery, but a reconciliation
workflow and concrete economy/inventory adapters are still required before
player purchases can be enabled in gameplay. The Towny town-bank adapter remains
reserved for guard payments and is not reused for player trading.

## Evidence-backed reconciliation

A trade moved to `REVIEW_REQUIRED` is not automatically retried. An administrator
must first verify the external economy result and the player's inventory, then
call the repository's reconciliation operation with an explicit resolution and
an evidence note:

- `DELIVERED`: confirms that items reached the player; reserved stock stays consumed.
- `CANCELLED_UNPAID`: confirms that no charge and no delivery occurred; exact-quality stock is restored.
- `REFUNDED_NOT_DELIVERED`: confirms that the charge was refunded and no items were delivered; exact-quality stock is restored.

The resolution and any stock restoration run in one SQLite transaction and only
apply while the trade is still `REVIEW_REQUIRED`. Repeated or stale admin
actions return false. If the business stock capacity no longer has room for a
release, the transaction fails without changing the journal; staff must resolve
the capacity conflict rather than silently overfill the business. Evidence is
stored in the trade's audit/error field. This repository method is a persistence
primitive; an authenticated permission-checked admin command/UI remains a
separate integration task.

## Provider-neutral purchase orchestration

The trade orchestration layer is split into three contracts:

- `ResidentTradeEconomyProvider` charges and refunds minor currency units. Every call receives the durable trade ID as its idempotency key.
- `ResidentTradeDeliveryProvider` delivers the persisted transaction. A Paper implementation is responsible for running inventory reads/writes on the server thread and must report whether delivery is confirmed, rejected before delivery, or uncertain.
- `ResidentTradeService` coordinates reservation, payment, delivery, and compensation without depending on Vault, Towny, or Bukkit.

A confirmed payment is persisted as `PAID` before delivery. A confirmed delivery is then persisted as `DELIVERED`. A definitive delivery rejection triggers a refund attempt; a confirmed refund is finalized together with stock restoration. An uncertain payment, uncertain delivery, uncertain refund, or persistence failure is sent to `REVIEW_REQUIRED`. In particular, an uncertain delivery is never automatically refunded because the item may already have reached the player.

The plugin now invokes journal recovery during startup. Any trade left in `STOCK_RESERVED`, `PAYMENT_PENDING`, `PAID`, or `REFUND_PENDING` by a previous process is moved to `REVIEW_REQUIRED` before gameplay can use it.

These are orchestration contracts, not a live marketplace integration: no concrete player-economy provider, Paper inventory adapter, purchase GUI, or permission-checked administration command is enabled yet. Those must be implemented and tested against the actual server provider before player-money trading is advertised.
