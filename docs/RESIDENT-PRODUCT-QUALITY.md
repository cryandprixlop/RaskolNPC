# Resident product quality and stock batches

## Status

Design decision for the next resident-economy increment. This document defines the storage contract before quality is exposed to players or used by gameplay. It does not claim that product quality is implemented yet.

## Implementation progress

- Schema migration 18 creates `resident_business_stock_batches` with validated quality tiers and FIFO index.
- Existing positive aggregate stock is backfilled as one `STANDARD` / `LEGACY` batch; zero balances are skipped.
- Added `ResidentProductQuality` and immutable `ResidentBusinessStockBatch` domain types.
- Added a read-only repository query ordered by `created_at, batch_id`.
- Existing mutation paths still write only the aggregate table. The batch table is therefore not yet the source of truth, and quality must not be exposed in gameplay until mutation routing and consistency checks are complete.

## Implementation progress

- Schema migration 18 creates `resident_business_stock_batches` and backfills positive legacy balances as `STANDARD` batches.
- Domain types `ResidentProductQuality` and `ResidentBusinessStockBatch` are present.
- Batch reads are available in deterministic FIFO order.
- Quest deposits, withdrawals, profession transfers, production input/output, and daily consumption now update batches in the same transaction as the aggregate stock projection.
- Withdrawals and daily consumption consume FIFO; profession transfers preserve the quality split across moved batches.
- Newly deposited goods use `STANDARD` quality.
- Newly produced goods receive deterministic quality from persisted profession mastery: `APPRENTICE → BASIC`, `NOVICE → STANDARD`, `SPECIALIST → FINE`, `EXPERT/MASTER → MASTERWORK`.
- The existing quantity-only production API remains compatible and defaults to `STANDARD`; quality-aware callers use the overload that supplies an explicit tier.
- Gameplay presentation remains disabled; the compatibility and transactional test suite is being expanded.
- `ResidentQuestService.businessStockBatches(...)` exposes persisted batches to future market adapters while rejecting missing/non-resident NPCs; it does not assign prices or mutate stock.

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
