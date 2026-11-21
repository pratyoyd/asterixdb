# PLAQUE DISTINCT filter: to-do list (written 2026-11-17, evening)

Scope, as set by the user: DISTINCT with the observer and filter on the probe side only. The build side and the helper structure are not part of this work.

## Decisions taken by the user on 2026-11-17

- Early sending: default `fj` (from the 342-run ablation, see `2026_11_17_PLAQUE_DISTINCT_FLUSH_ABLATION.md`).
- Default seen-set: roaring.
- Default level: 3. Level 3 acts only where Parquet wrote a dictionary by default; writer settings are not changed. The cap experiment is abandoned.
- Propagation (NC to CC to NC messages, the same as every earlier filter) is the default and the only way a node learns another node's values. A node must not see another node's seen-set directly.
- Next feature: SELECT DISTINCT with more than one expression.

## State of the code at the time of writing

Changed, built, and loaded on 2026-11-18 10:38. Verified on Q1 to Q4 the same day (168 runs, all results equal to Baleen off), see `2026_11_18_PLAQUE_DISTINCT_PERNODE_VERIFICATION.md`:

- `AlgebricksConfig.PLAQUE_DISTINCT_SET_DEFAULT` is now `roaring` (was `hash`).
- One seen-set per node: `PlaqueThresholdRegistry` keys the DISTINCT state by job, handle and node id. Before, the 4 NCs of the test server shared one seen-set because they run in one JVM.
- A node's seen-set is dropped when its last observer closes (`releaseDistinctState`), not when the first one does.
- The CC does not send a delta back to the node it came from (`PlaqueDistinctUpdateMessage` carries the sender).
- CC cleanup: new `PlaqueJobCleanupListener`, registered in `CCApplication`, calls `PlaqueThresholdCCState.removeJob` when a job ends. This also clears the entries of the MAX/MIN and GROUP BY filters, which were never cleared before.

The 126-run propagation on/off benchmark was stopped after 3 runs: with one seen-set per node and propagation as the only path, its "off" configuration no longer answers a question.

## To do, in order

1. DONE 2026-11-18 (Q2 now skips 21 to 26 of 30 files, median 16.0 s with the defaults). Restart the server on the new build and verify DISTINCT: Q1, Q2, Q3 (join on `lineitem`) and Q4 (`SELECT DISTINCT l.l_shipmode FROM lineitem l;`, no join), results equal to Baleen off, files skipped, deltas sent and merged. Q2's 26 of 30 skipped files now depend on propagation, so this has to be measured again (6 runs per configuration, matrix to be agreed first).
2. Verify the CC cleanup: no entries left on the CC after a job ends.
3. Coexistence: rerun the MAX/MIN, GROUP BY, Top-K and theta queries (`build_logs/bench_plaque.py`, `build_logs/bench_plaque_iceberg.py`) and compare results and plans with before today's changes to the join, the rule and the registry.
4. SELECT DISTINCT with more than one expression. Today the rule returns early unless there is exactly one expression (`PlaqueRewriteRule.handleDistinct`). Read the existing design first, state the plan, then build.
5. Check that level 3 still falls back to level 2 behaviour where the column has no dictionary. Partly covered 2026-11-18: at level 3, Q2 and Q3 behave as at level 2 with correct results; whether that is the missing dictionary or the `notinmax` limit was not separated.
6. Regression tests for DISTINCT in the test suite (there are none).
7. Commit the DISTINCT code on `baleen` (all of it is uncommitted; ask first).

## Added 2026-11-20

- Level 4 (rows dropped inside the Parquet reader by dictionary entry) is implemented for string columns and loaded on the server; unit tests pass (`PlaqueDistinctDictionaryReaderTest`, 10 tests). Single runs: Q1 8.7 and 11.4 s at level 4 against 15.3 and 19.7 s at level 3; Q4 0.5 and 0.6 s against 2.7 and 2.8 s. 6-run benchmark on Q1 and Q4 done 2026-11-20, see `2026_11_20_PLAQUE_DISTINCT_LEVEL4.md`: Q1 9.4 s against 13.1 s at level 3, Q4 0.4 s against 2.9 s (roaring). Default level is still 3; whether to make 4 the default is open.
- After the level 4 results (user, 2026-11-20): implement DISTINCT for native columnar collections. Implemented and checked with single runs on 2026-11-20, see `2026_11_20_PLAQUE_DISTINCT_NATIVE_COLUMNAR.md`; level 4 is the default since the same day. The 6-run benchmark (240 runs) waits for the user's OK.
- Then (user, 2026-11-20): think about the design for DISTINCT with more than one expression.
- Open idea, not scheduled (raised by the user 2026-11-20): row-level skipping inside the scan for the earlier filters (MAX, MIN, Top-K, GROUP BY). See `2026_11_20_PLAQUE_ROW_LEVEL_SKIPPING.md`, section 9.
- Open, offered 2026-11-20: a related-work search on row-level runtime filtering inside scans, for the paper.

## Regression tests (state on 2026-11-17, 19:40)

Added and passing (run with JUnit directly; the Maven test runner is not in the offline repository):

- `asterix-runtime/src/test/java/org/apache/asterix/runtime/operators/plaque/PlaqueDistinctStateTest.java`: 15 tests, each run for hash, flat and roaring (45 runs, all pass). New once then duplicate; nothing reported seen that was not added (200,000 values); extreme BIGINTs with each one as the first value; range check for file skip; values outside the announced range; range too wide for the flat bitmap; value listing with limit; strings and other types; own values handed out once and received values never; a delta reaching a second set; 4 threads adding the same 100,000 values report each new exactly once; hash cap at 1,000,000.
- `asterix-external-data/src/test/java/org/apache/asterix/external/input/record/reader/iceberg/PlaqueIcebergDistinctScanFilterTest.java`: 8 tests, all pass, and the 10 existing MAX/MIN scan filter tests still pass. Supported column types; file skipped only when every value between its bounds is seen; hash skips only single-value files; files with nulls or without statistics are read; no skip below level 2 or on a string column; NOT IN only at level 3, only for a non-empty set within the limit; strings in NOT IN.

Not added yet: query tests (result equal to Baleen off) and plan tests. Blocker: `asterix-app/src/test/resources/cc.conf` points the test cluster at `/scratch/AsterixDBDatasets/nc1..nc4`, the storage of the benchmark server. The standard test harness cleans its storage when it starts, so running it in this checkout would wipe the benchmark data. These tests need a test configuration with its own storage directory first.

Not done: no check that the tests fail when the code is broken on purpose.

## Query set

Every DISTINCT benchmark and check uses all four queries, Q1 to Q4. Q4 was left out of the flush benchmark, the flush ablation and the propagation check of 2026-11-17, on the expectation that the flush switches have nothing to act on without a join. That expectation was never measured. Q4 goes back into every matrix from now on; no query is dropped because it is expected to show nothing.

## Known limits, not scheduled

- The tuple filter and file skip are placed only when the path from the observer to the column goes through ASSIGN, PROJECT, EXCHANGE, SELECT and inner joins. Read from the rule's code, not tested.
- A delta that reaches the CC after the job has ended creates an entry that is never cleared. Not observed, read from the code.
- Hash seen-set cap 1,000,000; flat range 16,000,000; file skip needs BIGINT with null count 0.
- Left in place: the `notinmax` setting. The tables `lineitem_dict` and `lineitem_dict_1rg` were dropped on 2026-11-18 (AsterixDB collections only; the Iceberg files were not deleted).
