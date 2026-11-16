# Iceberg data skipping: review findings and to-do list (2026-11-16)

Review of commit `0be0b5caf5` on `baleen` ("Baleen: runtime data skipping for Iceberg scans (scalar MAX/MIN)").
No wrong result was found, but the work is not finished. This file is the list of what is left.

## Status after the fixes (2026-11-16, code not yet committed)

| # | What was done | How it was checked |
|---|---|---|
| 1 | `findDefiningAssign` now descends only through ASSIGN, SELECT, PROJECT, EXCHANGE and inner joins; the walk from the ASSIGN to the scan allows only the first four. | EXPLAIN: plain `MAX` and inner join still show the scan filter; `ORDER BY ... LIMIT` subquery and left outer join no longer do. ORDER/LIMIT query: 99939 off and on, no reader used a scan filter. |
| 2 | New `PlaqueIcebergScanFilterTest`, 10 unit tests (supported columns, threshold decoding and type mismatch, upper/lower bound, missing bound, NaN count, strict skip with ties kept, row-group operator, task order). | 10 of 10 pass, run directly with JUnit (the surefire JUnit provider is not in the offline Maven repository). No test for the optimizer rule itself; that is covered only by the EXPLAIN checks above. |
| 3 | Theta join on Iceberg run for the first time: `MAX(l_discount), COUNT(*)` from `lineitem_by_suppkey l, smoke s WHERE s.id >= 4990 AND s.id < 5000 AND l.l_suppkey < s.id`. | Same result off and on (0.1, 29,955,193). 66.1 s off, 17.8 s on; 26 of 30 files skipped. One run each. |
| 4 | The row-group filter is now passed to the variant-pruned read as well (both its delete and no-delete branches). | Compiles; the existing overloads used by upstream tests are unchanged. Not exercised: our tables have no VARIANT column. |
| 5 | EXPLAIN shows `plaque-scan-filter: MAX(l_suppkey), plaque-$$44` on the scan (text and JSON); `equals()` compares the three fields. | Seen in EXPLAIN output. |
| 6.2 | The rule attaches the filter only when the scan's dataset is an Iceberg table (`DatasetUtil.isIcebergTable`). | Iceberg plans still show the filter. The negative case (a non-Iceberg external dataset) was not checked. |

### Benchmark on the fixed build (Iceberg, SF10, median of 3, seconds)

| Query | Off | On | Speedup | Before the fixes |
|---|---|---|---|---|
| MAX(l_suppkey), lineitem | 18.2 | 17.8 | 1.02x | 0.97x |
| MAX(l_suppkey), lineitem_by_suppkey | 17.3 | 2.8 | 6.27x | 6.26x |
| MIN(l_suppkey), lineitem_by_suppkey | 17.1 | 2.5 | 6.99x | 7.18x |
| MAX(l_orderkey), lineitem | 18.4 | 2.9 | 6.26x | 7.24x |
| Join MAX(l_suppkey), lineitem x orders | 34.0 | 33.4 | 1.02x | 1.06x |
| Join MAX(l_suppkey), lineitem_by_suppkey x orders | 44.5 | 10.9 | 4.08x | 4.09x |
| GROUP BY MAX(l_discount) BY l_shipmode | 53.7 | 32.0 | 1.68x | 1.71x |
| Cross-table MAX(l_extendedprice - ps_supplycost) | 57.0 | 45.6 | 1.25x | 1.26x |
| Scan only, no Baleen operator | 27.7 | 26.9 | 1.03x | 0.96x |

All results match between off and on; 0 ERROR lines in the server log. File-skip counters are as before (26 of 30 files skipped on ordered data, none on unordered). Raw output: `build_logs/bench_result_iceberg_reviewfix.md`.

Still open after the later work below: 9 (not investigated, by decision) and a test for the optimizer rule.

Note on item 3: the first theta query tried (`ps.ps_partkey = 1` on `partsupp`) fails with Baleen off too, because of item 8. The query above avoids it by filtering on the same column it outputs.

Note on item 9: restarting the server did make `smoke.L` readable again.

## Items 7 and 8 done (2026-11-16, later the same day; code not yet committed)

### Item 7: ties are now dropped

A row equal to the current threshold cannot change a MAX or MIN, so three filters changed from "keep ties" to "drop ties":

- scalar MAX/MIN tuple filter (`PlaqueRewriteRule.createPlaqueFilter` now asks for a strict filter; `PlaqueThresholdState.passes` takes the strict flag from the caller, because the flag stored in the state is that of whichever operator created it first);
- GROUP BY filter (`PlaqueGroupThresholdState.passes`);
- Iceberg file skip (`bound <= threshold` is skipped for MAX) and row-group filter (`>` / `<`).

Top-K and the cross-table filter are unchanged.

Iceberg, SF10, median of 3, seconds. The machine was slower during this run (scan-only baseline 36.1 s against 27.7 s earlier), so compare speedups, not absolute times.

| Query | Off | On | Speedup | With ties kept |
|---|---|---|---|---|
| MAX(l_suppkey), lineitem (unordered) | 20.4 | 2.7 | 7.68x | 1.02x |
| MAX(l_suppkey), lineitem_by_suppkey | 16.9 | 2.7 | 6.38x | 6.27x |
| MIN(l_suppkey), lineitem_by_suppkey | 17.3 | 2.4 | 7.22x | 6.99x |
| MAX(l_orderkey), lineitem | 17.8 | 2.6 | 6.80x | 6.26x |
| Join MAX(l_suppkey), lineitem x orders | 34.7 | 11.2 | 3.10x | 1.02x |
| Join MAX(l_suppkey), lineitem_by_suppkey x orders | 46.5 | 10.7 | 4.33x | 4.08x |
| GROUP BY MAX(l_discount) BY l_shipmode | 62.7 | 36.1 | 1.74x | 1.68x |
| Cross-table MAX(l_extendedprice - ps_supplycost) | 60.4 | 48.5 | 1.25x | 1.25x |
| Scan only, no Baleen operator | 36.1 | 36.9 | 0.98x | 1.03x |

Internal tables:

| Query | Off | On | Speedup | With ties kept (2026-11-15) |
|---|---|---|---|---|
| M1 single-table MAX (column) | 24.5 | 2.2 | 11.26x | 12.05x |
| M1 single-table MAX(l_shipdate) (row) | 14.0 | 13.5 | 1.04x | 1.05x |
| M3 join MAX + page filter (column) | 33.7 | 6.9 | 4.89x | 3.79x |
| GROUP BY MAX(l_discount) (row) | 37.4 | 19.5 | 1.91x | 1.71x |
| GROUP BY MAX(l_discount) (column) | 53.6 | 36.6 | 1.47x | 1.38x |
| Cross-table MAX (column) | 53.9 | 7.2 | 7.43x | 6.72x |

All results match between off and on; 0 ERROR lines in the server log.

What the numbers say:

- The two large jumps are on unordered `lineitem` (1.02x to 7.68x, and the join 1.02x to 3.10x). Every file there has the same upper bound for `l_suppkey` (100000, the global maximum). Once one reader has seen 100000, every other file's bound ties with the threshold and is now skipped. This is a property of that column (a low-cardinality key whose maximum is in every file), not a general gain on unordered data. In the join, one of the three Baleen runs took 20.5 s against 11.2 and 11.1.
- GROUP BY on Iceberg: the filter now passes 144,010 of 59,986,052 rows (0.24%), down from 5,600,488 (9.3%); every one of them passed before its partition had a threshold. The speedup barely moved (1.68x to 1.74x): the sort was already down to about 2.5 s per partition, so what remains is the scan and the filter itself.

### Item 8: WHERE failure fixed

`StreamSelectPOperator.java` now passes upstream's projection list to the runtime instead of `null`. Both branches of `StreamSelectRuntime.nextFrame` (normal and dynamic filter) already honoured the list.

Checked with Baleen off and on; all nine pairs return the same result, 0 ERROR lines:

| Query | Off | On |
|---|---|---|
| Iceberg `MAX(l_suppkey)` from `lineitem_by_suppkey` `WHERE l_quantity < 3` | 33.9 s | 3.9 s |
| Iceberg `MIN(l_suppkey)` from `lineitem_by_suppkey` `WHERE l_shipmode = 'AIR'` | 35.0 s | 3.9 s |
| Iceberg `MAX(l_orderkey)` from `lineitem` `WHERE l_quantity < 3.0` | 28.6 s | 4.3 s |
| Iceberg `MAX(l_suppkey)` `WHERE l_suppkey < 3` | 1.0 s | 1.0 s |
| Iceberg `smoke`: `MAX(s.v) WHERE s.id < 3` | 0.3 s | 0.3 s |
| Iceberg `smoke`: `SELECT VALUE s.v WHERE s.id NOT IN [0,1,2] ORDER BY s.id LIMIT 3` | 2.2 s | 1.1 s |
| Iceberg theta join with `ps.ps_partkey = 1` (the query that failed earlier) | 38.6 s | 10.2 s |
| Internal `smoke.L`: `MAX(s.price) WHERE s.disc < 500` | 0.8 s | 0.3 s |
| Internal `smoke.L`: `COUNT(*)` | 0.2 s | 0.1 s |

One run each. On the three ordered-data queries with a WHERE clause, 26 of 30 files were skipped.

Not checked: the full set of `NOT IN` shapes that failed before (one array form was run), and the upstream test suites.

## To do

| # | Item | Status |
|---|---|---|
| 1 | Plan guard: the rule attaches the scan filter without checking which operators sit between the aggregate and the scan. Allow only ASSIGN, SELECT, PROJECT and inner-join inputs; bail out otherwise. | Done 2026-11-16 |
| 2 | Tests: unit tests for `PlaqueIcebergScanFilter` (missing bound, NaN count, type tag mismatch, MAX/MIN direction, ordering) and a plan-level test for the rule. | Done 2026-11-16 |
| 3 | Theta join: the theta-join caller now also enables file skipping on Iceberg. Run a theta-join query off/on on Iceberg and check the threshold meaning. | Done 2026-11-16 |
| 4 | Variant-pruned read: the row-group filter is not applied on that path and the "filtered" counter is still incremented. | Done 2026-11-16 |
| 5 | Show the scan filter in the plan (`print()`), and include it in `equals()`. | Done 2026-11-16 |
| 6.2 | Restrict the rule to Iceberg scans; today it marks any external scan. | Done 2026-11-16 |
| 7 | Strict comparison: use `>` instead of `>=` (MAX) and `<` instead of `<=` (MIN) so ties are dropped, both in the file skip / row-group filter and, where valid, in the tuple filter. Pratyoy's suggestion, 2026-11-16. | Done 2026-11-16 for scalar MAX/MIN, GROUP BY and the Iceberg scan filter; Top-K and cross-table unchanged |
| 8 | `WHERE` predicate on the Iceberg tables fails at compile time with a NullPointerException, with Baleen off as well. | Fixed 2026-11-16 |
| 9 | After a failed query on the internal columnar dataset `smoke.L`, every scan of it fails with `Invalid pinCount`. Seen once, after the probe queries for item 8. | Not investigated; a server restart is expected to clear it (not verified) |

## Item 8: root cause

- It is a merge regression, not an Iceberg problem. It fails at run time on the first frame, on internal datasets too.
- Upstream lets the optimizer push a projection into SELECT (commit `6befed4061`, ASTERIXDB-3531). The merge kept our older `StreamSelectPOperator.java`, which passes `null` as the projection list to `StreamSelectRuntimeFactory` (line 85). Upstream passes `select.isProjectPushed() ? JobGenHelper.projectVariables(inputSchemas[0], select.getProjectVariables()) : null`.
- Effect: the plan says the select outputs one field, the runtime forwards the full tuple, and the operator above reads the wrong field. Here that gives a null type tag; in other query shapes a silent wrong result is conceivable (not observed).
- Fails whenever the WHERE column differs from the output column and the record variable is dropped. The `NOT IN` failures seen earlier are very likely the same bug (their stack traces were not checked).
- Proposed fix: pass upstream's expression instead of `null`. Open point: check that the dynamic-filter branch of `StreamSelectRuntime.nextFrame` also honours the projection list.

Not scheduled: 6.1 (stats requested for all columns when a variant filter is present), 6.3 (log level of the per-reader summary), malformed JSON in the query service response (missing comma after `requestID`), `NOT IN` failure on the merged build.

## Checks run during the review (Baleen off vs on, `lineitem_by_suppkey`)

| Query | Result |
|---|---|
| `MAX` and `MIN` in one block | Same result; Baleen does not fire |
| `MAX` and `COUNT(*)` in one block | Same result; Baleen does not fire |
| Two subqueries, `MAX` and `COUNT` | Same result |
| Two subqueries, `MAX` and `MIN` | Same result; 35.4 s off, 4.8 s on |
| `MAX` over `ORDER BY l_orderkey LIMIT 1000` subquery | Same result (99939), but the scan filter was attached below the ORDER/LIMIT; see item 1 |
| `orders LEFT OUTER JOIN lineitem_by_suppkey`, `MAX(l_suppkey)` | Same result; no skipping |
| `MAX(l_suppkey) ... WHERE l_quantity < 3` | Fails with Baleen off and on; item 8 |
