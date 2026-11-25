# Double column page filter fix (2026-11-23)

## What the summer notes recorded

Two separate problems with double columns were written down in June/July
(`PLAQUE_M3_PHYSICAL_PAGE_SKIP_PLAN.md` line 75, `PLAQUE_THETA_JOIN_EXPERIMENTS.md` lines 37 to 39 and Step 4):

1. `DoubleColumnFilterWriter.reset()` started with `min = Double.MIN_VALUE, max = Double.MAX_VALUE`, so the
   stored min/max of a double column was almost always the reset values and no page was ever skipped.
2. "A deeper undocumented issue with double normalization beyond the reset bug". Never investigated.

Because of these, every experiment since used `l_suppkey` (bigint) instead of `l_extendedprice` (double).

## State found today

| Problem | State before today | Where |
|---|---|---|
| 1. Writer reset values | Already fixed upstream by ASTERIXDB-3944 (commit `ed0ede2540`, 2026-10-08), which is in `baleen` through the upstream merge. Starts at +INF / -INF, also handles NaN. | `DoubleColumnFilterWriter.java` |
| 2. Normalization | Still present, in the three Baleen page filters. Fixed today. | see below |

## Problem 2 explained

The min/max of a double (and float) column is stored per mega leaf node as `Double.doubleToLongBits(value)`.
The Baleen page filters compared these longs with `>=`, `<=`, `>`, `<`. The bits of two doubles sort like the
doubles only when both are positive. For two negative doubles the order is reversed:

| value | bits as signed long |
|---|---|
| -1.0 | -4616189618054758400 |
| -5.0 | -4609434218613702656 (larger) |

So with `MAX(x)` and a best value of -5.0, a leaf whose max is -1.0 was judged "cannot beat -5.0" and skipped.
That is a wrong result, not only a lost skip. AsterixDB's own range filters do not have this problem: they turn
the bits back into doubles and compare with `ComparatorUtil.compareDoubles`.

Found in the same lines while fixing:

| # | Problem | Filter | Effect |
|---|---|---|---|
| a | Bits compared as longs | scalar MAX/MIN, GROUP BY MAX/MIN, cross-table `a ± b` | Wrong skip when negatives are involved |
| b | Best value of a different type than the column (for example a bigint best value against a double column) was compared anyway | scalar MAX/MIN | Arbitrary skip |
| c | Bound cast with `(long) bound`, which rounds towards zero | cross-table on an integer column | Leaf with max -5 skipped for bound -5.5; leaf with min 5 skipped for bound 5.5 |
| d | NaN bound (for example `inf * 0`) compared anyway | cross-table | Arbitrary skip |
| e | Numeric bound compared with the stored min/max of a non-numeric column | cross-table | Arbitrary skip |

The DISTINCT page filter is not affected: it only attaches to bigint columns.

## Fix

- New `PlaqueZoneMapOrder.compare(columnType, left, right)`: doubles and floats are compared as doubles in SQL++
  order (NaN above +INF, -0.0 equal to 0.0), everything else as longs. Used by all three filters (a).
- Scalar filter keeps the leaf when the best value's type is not the column type (b).
- Cross-table filter: `floor(bound)` for "above", `ceil(bound)` for "below" on integer columns (c); keeps the
  leaf for a NaN bound (d); no filter on non-numeric columns (e).

Files (asterix-column, not committed):

- `filter/range/evaluator/PlaqueZoneMapOrder.java` (new)
- `filter/range/evaluator/PlaqueColumnFilterEvaluatorFactory.java`
- `filter/range/evaluator/PlaqueGroupByColumnFilterEvaluatorFactory.java`
- `filter/range/evaluator/PlaqueCrossExprColumnFilterEvaluatorFactory.java`
- test: `filter/range/evaluator/PlaquePageFilterDoubleOrderTest.java` (new)

## Test

`PlaquePageFilterDoubleOrderTest`, run with JUnitCore (offline classpath).

| Run | Result |
|---|---|
| Before the fix | 7 tests, 7 failures (each of a, b, c reproduced) |
| After the fix | 7 tests, OK |
| `PlaqueDistinctColumnFilterTest`, `PlaqueRowFilterTest` after the fix | 4 and 11 tests, OK |

Cases covered: scalar MAX and MIN with a negative best value (better, tie, worse, mixed sign, -0.0, NaN leaf
max), positive best value unchanged, type mismatch, GROUP BY MAX with a negative best value, cross-table above
and below a negative bound on a double column, fractional bound on a bigint column in both directions.

## Check: page skipping on `l_extendedprice` (server before the fix, old jar)

Raw rows: `/scratch/asterixdb_eightynode/build_logs/double_pageskip_check_2026_11_23/runs.csv`. One run per
configuration, no warm-up. Query: `SELECT MAX(l.l_extendedprice) AS m FROM Default.Lineitem_column_10 l;`

| Config | Settings | Seconds | Matches baseline | Rows into tuple filter |
|---|---|---|---|---|
| Baleen off | `compiler.plaque.enabled=false` | 24.981 | baseline | 0 |
| Page skip only | `compiler.plaque.enabled=true`, `compiler.plaque.rowfilter=false` | 0.259 | yes | 45,276 |

Each of the 4 partitions evaluated at least 9,000 leaves; 8,991 to 8,993 of the first 9,000 were skipped. The
log prints the count only every 1,000 leaves, so final totals are not available. Logged leaf maxima are real
values (100845.0, 102449.0 against best 104949.0). So problem 1 is gone on the current data. The same query
was already benchmarked on 2026-11-22 as N1 (23.5 s vs 0.1 s, six runs each); this check adds nothing new.

## Code review (code-reviewer agent) and what was done with it

| # | Finding | Introduced by | Action |
|---|---|---|---|
| B1 | FLOAT column, MAX: a FLOAT best value became the sentinel `Long.MIN_VALUE`, which the new double comparison read as -0.0, so a leaf with max -1 was skipped against best -5 (scalar and GROUP BY) | **today's first version of the fix** | Fixed: both filters keep the leaf when the best value has no stored form |
| B2 | GROUP BY MIN on a type `normalize` does not know (FLOAT, DATETIME, ...): sentinel `Long.MIN_VALUE` made every single-group leaf skip | before | Fixed, same change: the state turns the page filter off |
| B5 | GROUP BY: best value of another type than the column compared anyway | before | Fixed: type check as in the scalar filter |
| B6 | GROUP BY: exception when the column is absent, nested or of mixed types in a component | before | Fixed: no leaf filter |
| B3 | GROUP BY with string group keys: "min = max" is taken as "one group", but the stored string value is a 4-character prefix (`Brand#11` to `Brand#55` all look equal). A leaf of an unseen group can be skipped on another group's best value | before | **Not fixed, needs a decision** |
| B4 | GROUP BY: rows with a null/missing group key are invisible to the leaf min/max, so a leaf {k=4, k=null} counts as single-group and the null group loses rows | before | **Not fixed, needs a decision** |
| B7 | Cross-table: bound `t + c` and the query's `a - b` round differently; a row that wins in the last digit can be dropped | before | Not fixed |
| B8 | Cross-table: bound taken over the build partitions published so far (reviewer: plausible, not confirmed) | before | Not fixed |
| B9 | Cross-table: saturation at Long extremes, BIGINT best above 2^53 | before | Not fixed |
| R1 | `PlaqueRewriteRule` lines 444 to 447: MAX(a + b) uses `t - min(b)`; the safe bound is `t - max(b)` (t=10, b in {1, 9}, row a=5, b=9 gives 14 but is dropped). Mirror error for MIN, same code for Top-K at 662 to 668. I read the lines and agree | before | **Not fixed, needs a decision** |
| R2 | `PlaqueRewriteRule` lines 515 to 523: MAX(b - a) with a on the probe side should use `max(b) - t`; the code computes `t - max(b)`. I read the lines and agree | before | **Not fixed, needs a decision** |
| D4 | GROUP BY rule picks the first MAX/MIN among the aggregates; no check seen that it is the only aggregate (reviewer: plausible, not checked) | before | Not checked |
| - | Hygiene: `System.out.println` per leaf, a debug line that still prints the old comparison, duplicated normalize functions, no license header on the three older files | before | Not changed |

Files changed by the review fixes: the scalar and GROUP BY evaluator factories, `PlaqueZoneMapOrder`
(`sameStoredForm`), and `asterix-runtime/.../plaque/PlaqueGroupThresholdState.java`
(`getPageFilterAggType`, `normalizableType`).

Unit test after the review fixes: `PlaquePageFilterDoubleOrderTest` 12 tests OK (5 new: FLOAT scalar, GROUP BY
MIN, GROUP BY best value without a stored form, GROUP BY type mismatch, GROUP BY missing column);
`PlaqueDistinctColumnFilterTest` 4 OK, `PlaqueRowFilterTest` 11 OK.

## Rebuild and restart

- Server stopped with SIGTERM (clean shutdown), `mvn -o install -pl asterix-runtime,asterix-column` (log
  `build_logs/doublefix_build.log`, BUILD SUCCESS), started again with the identical command, same storage.
  New log: `build_logs/asterix_doublefix.log`. `Lineitem_column_10` still has 59,986,052 rows.

## End-to-end check on negative doubles (server with the fix)

Table `doublefix.Neg` (new dataverse, columnar, 3,000,000 rows, loaded with LOAD DATASET from
`/scratch/tpch_data/doublefix/neg.tbl`): `id` bigint key, `x` double all negative (-100000.99 to -1.01, random
order), `y` double mixed sign (-49999.97 to 49999.94).

Raw rows: `/scratch/asterixdb_eightynode/build_logs/double_negative_e2e_2026_11_23/runs.csv` (12 runs, one per
cell, no warm-up; a correctness check, the times are not a benchmark).

| Query | Baleen off (s) | Page skip only (s) | Page skip + row test (s) | All three results equal | Rows into tuple filter: page skip only / + row test |
|---|---|---|---|---|---|
| `SELECT MAX(t.x) AS m FROM doublefix.Neg t;` | 1.183 | 0.301 | 0.278 | yes | 71,786 / 13,062 |
| `SELECT MIN(t.x) AS m FROM doublefix.Neg t;` | 1.164 | 0.149 | 0.166 | yes | 52,208 / 10,232 |
| `SELECT MAX(t.y) AS m FROM doublefix.Neg t;` | 0.902 | 0.275 | 0.210 | yes | 75,049 / 13,033 |
| `SELECT MIN(t.y) AS m FROM doublefix.Neg t;` | 1.281 | 0.202 | 0.213 | yes | 45,682 / 11,480 |

Settings: Baleen off = `compiler.plaque.enabled=false`; page skip only = `enabled=true, rowfilter=false`;
page skip + row test = `enabled=true, rowfilter=true`. Every one of the 32 leaf filters (4 partitions x 8
Baleen runs) logged at least 5 skipped leaves, so the filter was active; with page skip only 1.5 % to 2.5 % of
the 3,000,000 rows reached the tuple filter.

## Problems met on the way

- First version of the fix introduced B1 (FLOAT). Caught by the review before the rebuild.
- First start of the benchmark runner failed before sending a query (plan file already in the output folder);
  output kept as `runner_attempt1_no_query_sent.out`.
- I told the user that page skipping on `l_extendedprice` was unverified although the 2026-11-22 R1a benchmark
  had measured it (N1).

## Decisions I made without asking

- Fixed the adjacent wrong-skip cases b to e and review items B2, B5, B6 in addition to the double ordering.
- FLOAT best values: keep every leaf (as before today) instead of adding FLOAT skipping.
- GROUP BY: one best value without a stored form, or best values of two types, turns the page filter off for
  the whole query, not only for that group.
- Created dataverse `doublefix` and the file under `/scratch/tpch_data/doublefix` on the benchmark server.
- Stopped the server with SIGTERM and installed two modules instead of a full build.

## Not done / not verified

- The wrong result was not shown end to end on the old jar (the server was rebuilt before the negative table
  existed); the before/after evidence is the unit test.
- No end-to-end query for GROUP BY or cross-table filters on negative doubles; unit tests only.
- The review fixes (second round) were not reviewed again.
- A FLOAT or INTEGER best value still never skips in the scalar filter (`normalizeThreshold` handles only
  DOUBLE, BIGINT, STRING). Lost skipping, not a wrong result; left as it was.
- Group keys that are doubles: -0.0 and 0.0 are different keys for the page lookup, so such a leaf is kept.
  Safe, left as it was.
