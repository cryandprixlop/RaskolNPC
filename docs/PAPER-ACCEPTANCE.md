# Paper acceptance and load-test plan

This checklist is for a live integration server. Unit tests and CI do not replace these checks.

## Supported test baseline

- Paper API: 26.2 (the version declared by `build.gradle.kts` and `plugin.yml`).
- Java: 25.
- Towny: 0.103.2.0.
- SiegeWar: 3.7.0.
- WorldGuard: 7.0.19.
- Configured hard NPC ceiling: 150.
- Guard ceiling: 30 per Nation.
- Resident ceiling: 15 per Town.

Run this plan on a disposable staging server with backups. Do not run payment scenarios against a live town bank.

## 1. Startup, persistence and shutdown

- Start with Towny, SiegeWar and WorldGuard enabled; confirm RaskolNPC reports successful data/entity recovery.
- Restart with a mix of guards and residents; verify each NPC UUID is restored once and no duplicate entity is created.
- Stop/restart while NPC chunks are unloaded; confirm recovery does not force-load those chunks.
- Disable RaskolNPC cleanly; confirm shared scheduler tasks are cancelled and SQLite closes without outstanding writes.

## 2. Resident runtime roster

- Start with zero residents, create one resident after startup, assign HOME and WORK destinations, and verify it begins moving without a plugin restart.
- Create additional residents, then delete one; verify the behavior roster follows the registry and deleted residents are no longer processed.
- Change a schedule/destination while residents are active; verify the next loaded behavior snapshot is used.
- Put a destination in an unloaded chunk; verify no chunk is force-loaded and the resident waits until it is loaded.
- Place a one-block obstacle in a route; verify a safe sidestep or a stationary resident, never a teleport into a solid block.

## 3. Guard behavior and diplomacy

- Verify HOLD_POSITION, PATROL, DEFEND/RALLY, ESCORT and RETURN_TO_POST.
- Verify a neutral, allied, unresolved or unregistered entity is never treated as hostile.
- Verify a confirmed hostile registered NPC can be pursued and attacked only within the hiring Nation's territory.
- Verify a Towny-resolved hostile player is eligible; remove that player from the Towny roster and verify pursuit stops and the persisted order resumes.
- Make the nearest hostile target ineligible by territory; verify the guard selects another eligible target rather than becoming blocked by the ineligible one.
- Simulate Towny, diplomacy and territory lookup errors; verify movement/attack fails closed.

## 4. SiegeWar end-to-end

Use two disposable Nations and a controlled siege:

1. Confirm the Nations are neutral before the siege; guards must not attack one another.
2. Start a SiegeWar siege and verify the adapter resolves the attacker and defender Nation UUIDs.
3. Verify hostility is recognized in both directions only while SiegeWar reports an active, valid siege.
4. Verify a guard pursues/attacks only a verified hostile target that also passes hiring-territory and WorldGuard checks.
5. End or cancel the siege; verify the relation is no longer hostile and guards resume their persisted orders.
6. Repeat with incomplete/invalid siege data and with SiegeWar disabled; unknown data must never create hostility.

Record the SiegeWar version, server build, plugin load order, relevant logs and each observed result. A mocked adapter test is not evidence of this live integration test.

## 5. WorldGuard end-to-end

- With WorldGuard enabled, test a region allowing mob damage and one denying it.
- Confirm both attacker and target locations must return ALLOWED.
- Confirm missing adapter, null/unknown query results and thrown exceptions deny the attack.
- Repeat with WorldGuard absent; record the configured optional-integration behavior.

## 6. Load test at 150 NPCs

Create a disposable test layout with:
- 30 guards distributed across the test Nations (respecting the 30-per-Nation cap);
- 120 residents distributed across at least 8 Towns (respecting the 15-per-Town cap);
- 150 total registered NPCs, with representative loaded and unloaded chunks.

Run a baseline with no NPCs, then 150 NPCs for at least 30 minutes. Include normal movement, guard patrol, combat detection, resident schedules and at least one in-game day boundary.

Observe and record:
- median and p95 MSPT / tick duration before and during the test;
- CPU and heap usage before and during the test;
- active entity count and registered NPC count;
- chunk-load events and whether any NPC task causes a forced load;
- scheduler task count (must remain shared/bounded, not one repeating task per NPC);
- exceptions, warnings, SQLite latency and missed/late behavior updates.

The configured defaults currently process up to 6 guard orders and 4 combat checks per 10 ticks, and up to 8 residents per 10 ticks. Confirm actual task cadence and batch limits in the running build. Do not increase batch sizes merely to make the test finish faster; first inspect MSPT and the effect on server responsiveness.

## Result record

For each run, save:
- commit SHA and plugin JAR SHA-256;
- Paper build, Java version and integration plugin versions;
- exact NPC counts and distribution;
- test duration and workload;
- baseline and loaded median/p95 MSPT, CPU and heap;
- logs, exceptions and chunk-load observations;
- PASS / FAIL / NOT RUN for every section above.

A section is PASS only when it was executed on the live staging server and its expected behavior was observed. CI success alone is not a live-server pass.
