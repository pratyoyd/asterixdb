# PLAQUE DISTINCT filter: to-do list (written 2026-11-17, evening)

Scope, as set by the user: DISTINCT with the observer and filter on the probe side only. The build side and the helper structure are not part of this work.

## Decisions taken by the user on 2026-11-17

- Early sending: default `fj` (from the 342-run ablation, see `2026_11_17_PLAQUE_DISTINCT_FLUSH_ABLATION.md`).
- Default seen-set: roaring.
- Default level: 3. Level 3 acts only where Parquet wrote a dictionary by default; writer settings are not changed. The cap experiment is abandoned.
- Propagation (NC to CC to NC messages, the same as every earlier filter) is the default and the only way a node learns another node's values. A node must not see another node's seen-set directly.
- Next feature: SELECT DISTINCT with more than one expression.

## State of the code at the time of writing

Changed and built, but the server has NOT been restarted and nothing below has been run yet:

- `AlgebricksConfig.PLAQUE_DISTINCT_SET_DEFAULT` is now `roaring` (was `hash`).
- One seen-set per node: `PlaqueThresholdRegistry` keys the DISTINCT state by job, handle and node id. Before, the 4 NCs of the test server shared one seen-set because they run in one JVM.
- A node's seen-set is dropped when its last observer closes (`releaseDistinctState`), not when the first one does.
- The CC does not send a delta back to the node it came from (`PlaqueDistinctUpdateMessage` carries the sender).
- CC cleanup: new `PlaqueJobCleanupListener`, registered in `CCApplication`, calls `PlaqueThresholdCCState.removeJob` when a job ends. This also clears the entries of the MAX/MIN and GROUP BY filters, which were never cleared before.

The 126-run propagation on/off benchmark was stopped after 3 runs: with one seen-set per node and propagation as the only path, its "off" configuration no longer answers a question.

## To do, in order

1. Restart the server on the new build and verify DISTINCT: Q1, Q2, Q3 (join on `lineitem`) and Q4 (`SELECT DISTINCT l.l_shipmode FROM lineitem l;`, no join), results equal to Baleen off, files skipped, deltas sent and merged. Q2's 26 of 30 skipped files now depend on propagation, so this has to be measured again (6 runs per configuration, matrix to be agreed first).
2. Verify the CC cleanup: no entries left on the CC after a job ends.
3. Coexistence: rerun the MAX/MIN, GROUP BY, Top-K and theta queries (`build_logs/bench_plaque.py`, `build_logs/bench_plaque_iceberg.py`) and compare results and plans with before today's changes to the join, the rule and the registry.
4. SELECT DISTINCT with more than one expression. Today the rule returns early unless there is exactly one expression (`PlaqueRewriteRule.handleDistinct`). Read the existing design first, state the plan, then build.
5. Check that level 3 still falls back to level 2 behaviour where the column has no dictionary.
6. Regression tests for DISTINCT in the test suite (there are none).
7. Commit the DISTINCT code on `baleen` (all of it is uncommitted; ask first).

## Query set

Every DISTINCT benchmark and check uses all four queries, Q1 to Q4. Q4 was left out of the flush benchmark, the flush ablation and the propagation check of 2026-11-17, on the expectation that the flush switches have nothing to act on without a join. That expectation was never measured. Q4 goes back into every matrix from now on; no query is dropped because it is expected to show nothing.

## Known limits, not scheduled

- The tuple filter and file skip are placed only when the path from the observer to the column goes through ASSIGN, PROJECT, EXCHANGE, SELECT and inner joins. Read from the rule's code, not tested.
- A delta that reaches the CC after the job has ended creates an entry that is never cleared. Not observed, read from the code.
- Hash seen-set cap 1,000,000; flat range 16,000,000; file skip needs BIGINT with null count 0.
- Left in place, unused: the `notinmax` setting, the tables `lineitem_dict` and `lineitem_dict_1rg`.
