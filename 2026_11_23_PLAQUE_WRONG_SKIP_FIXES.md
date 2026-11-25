# Older wrong-skip bugs in the cross-table and GROUP BY filters (2026-11-23, evening)

Follow-up to `2026_11_23_PLAQUE_DOUBLE_PAGE_FILTER_FIX.md`. The user asked to fix the four older bugs the code
review had listed. Nothing is committed. Server: rebuilt and restarted on the same storage, log
`/scratch/asterixdb_eightynode/build_logs/asterix_wrongskip3.log`; `Lineitem_column_10` has 59,986,052 rows.

## What was wrong and what changed

a = column of the probe table, b = column of the build table, t = best value found so far.

| # | Bug | Example | Change |
|---|---|---|---|
| 1 | `MAX(a + b)` kept rows with `a > t - min(b)`; the safe bound is `a > t - max(b)`. `MIN(a + b)` had the mirror error. Same code in the Top-K path. | t = 10, b in {1, 9}: the row a = 5, b = 9 gives 14 but 5 > 10 - 1 is false, so it was dropped | `PlaqueRewriteRule.crossExprBound` (new, used by both paths): `MAX(a+b)`: `a > t - max(b)`; `MIN(a+b)`: `a < t - min(b)` |
| 2 | `MAX(b - a)` computed `t - max(b)`; the bound is `a < max(b) - t`. `MIN(b - a)` also used max(b) instead of min(b). | t = 10, max(b) = 30: rows with a < 20 can beat t; the code kept only a < -20 | New combine operation `REVERSE_SUBTRACT` (build value minus t) in the exchange filter and the page filter; `MAX(b-a)`: `a < max(b) - t`; `MIN(b-a)`: `a > min(b) - t` |
| 3 | GROUP BY page filter treated "leaf min = leaf max of the group column" as one group. Strings are stored as the first 4 characters with the last bit dropped, so `Brand#11` to `Brand#55` look equal, and a leaf of a group not seen yet could be skipped on another group's best value. | first leaves hold `Brand#11` with best 5,000,000; a leaf of `Brand#22` with max 4,400,000 was skipped | `PlaqueGroupThresholdState.storedFormIdentifiesGroup`: a string group key longer than 3 characters is never looked up by the page filter (its leaves are kept; the row filter still applies) |
| 4 | Rows with a null or missing group key are not in the leaf min/max, so a leaf that looks like group `A` can also hold rows of the null group and was skipped on A's best value. | leaf: 999 rows of `A` (max below A's best) and one null-key row that is the best of the null group | `PlaquePageFilterRule.neverNullOrMissing`: the GROUP BY page filter is attached only when the dataset type declares the group field without `?` |

Two more defects surfaced while testing bug 4, both needed for it to work:

- **Row filter dropped every row with a null or missing group key** (`PlaqueGroupFilterRuntimeFactory`, comment
  "they can't contribute to any group"). In SQL++ these are groups. With Baleen on,
  `GROUP BY g.n` returned 3 groups instead of 5. Now such rows are tested like any other key.
- **Ghost rows after a skipped last leaf** (`AbstractColumnTupleReference.newPage`, hyracks storage module).
  When the last leaf of a partition is skipped, `reset()` is not called, `isConsumed()` compares with the end
  index of the previous leaf, and the cursor emits (previous leaf size - last leaf size) empty rows. Seen as
  712, 3,086, 2,238 and 2,017 rows per partition with an unknown group key on a column that has none. The old
  row filter hid them by dropping all null-key rows. Fix: `endIndex = 0` when a leaf is skipped. This cursor is
  also used by scalar MAX/MIN, where the ghost rows carried a missing value and MAX/MIN ignored them.

## Unit tests

| Test | Module | Result |
|---|---|---|
| `PlaqueRuleBoundsTest` (new, 2): all 6 shapes x 41 values of t x 6 values of a: no row that can improve t is dropped, and every row that cannot is dropped; declared-type check | asterix-algebra | OK (run with `ConsolidateSelectsPlaqueTest`, 4 tests) |
| `PlaqueGroupStringKeyTest` (new, 3): `Brand#11`/`Brand#55` and `MAIL`/`MAIM` not looked up, `AIR` and `R` looked up | asterix-runtime | OK (run with `PlaqueDistinctStateTest`, 48 tests) |
| `PlaquePageFilterDoubleOrderTest`, `PlaqueRowFilterTest`, `PlaqueDistinctColumnFilterTest` | asterix-column | OK (27 tests) |

The row-filter change and the cursor change have no unit test; the evidence is the end-to-end check below
(attempt 1 and 2 failing, attempt 3 passing).

## End-to-end check (correctness, one run per cell, times are not a benchmark)

Tables in dataverse `doublefix` (columnar, loaded with LOAD DATASET from `/scratch/tpch_data/doublefix/`):

- `Probe` (3,000,000 rows: `id` key, `k` bigint 0..999, `a` double rising with id from -1000 to 1000, noise 20),
  `Build` (1,000 rows: `k` key, `b` double -500..500).
- `Grp` (3,000,000 rows, closed type `{id: bigint, s: string, f: string, n: string?, v: double}`): `s` =
  `Brand#11`..`Brand#55` in id order, `f` = `A`/`N`/`R` in id order, `n` = `f` except null in 3,000 rows and
  missing in 3,000 rows, `v` = 5,000,000 - id (smaller values in the null/missing rows).

Settings: Baleen off = `SET \`compiler.plaque.enabled\` "false";` Page skip only = enabled true,
`compiler.plaque.rowfilter` false. Page skip + row test = both true.

Final attempt (server with all fixes), 42 runs, all equal to the baseline. Raw rows:
`/scratch/asterixdb_eightynode/build_logs/wrongskip_e2e_2026_11_23_run3/runs.csv`.

| Query | Baleen off (s) | Page skip only (s) | Page skip + row test (s) | All three results equal |
|---|---|---|---|---|
| `SELECT MAX(p.a + b.b) AS m FROM doublefix.Probe p, doublefix.Build b WHERE p.k = b.k;` | 2.416 | 1.616 | 1.557 | yes |
| `SELECT MIN(p.a + b.b) AS m FROM doublefix.Probe p, doublefix.Build b WHERE p.k = b.k;` | 1.632 | 0.859 | 0.742 | yes |
| `SELECT MAX(b.b - p.a) AS m FROM doublefix.Probe p, doublefix.Build b WHERE p.k = b.k;` | 1.354 | 0.439 | 0.461 | yes |
| `SELECT MIN(b.b - p.a) AS m FROM doublefix.Probe p, doublefix.Build b WHERE p.k = b.k;` | 1.521 | 1.775 | 1.751 | yes |
| `SELECT MAX(p.a - b.b) AS m FROM doublefix.Probe p, doublefix.Build b WHERE p.k = b.k;` | 1.612 | 1.495 | 1.432 | yes |
| `SELECT MIN(p.a - b.b) AS m FROM doublefix.Probe p, doublefix.Build b WHERE p.k = b.k;` | 1.477 | 0.731 | 1.027 | yes |
| `SELECT g.s AS k, MAX(g.v) AS m FROM doublefix.Grp g GROUP BY g.s ORDER BY k;` | 3.619 | 2.468 | 2.291 | yes |
| `SELECT g.n AS k, MAX(g.v) AS m FROM doublefix.Grp g GROUP BY g.n ORDER BY k;` | 3.076 | 2.595 | 2.844 | yes |
| `SELECT g.f AS k, MAX(g.v) AS m FROM doublefix.Grp g GROUP BY g.f ORDER BY k;` | 2.933 | 0.681 | 0.823 | yes |
| `SELECT g.f AS k, MIN(g.v) AS m FROM doublefix.Grp g GROUP BY g.f ORDER BY k;` | 3.106 | 0.737 | 0.842 | yes |
| `SELECT l.l_returnflag AS k, MAX(l.l_extendedprice) AS m FROM Default.Lineitem_column_10 l GROUP BY l.l_returnflag ORDER BY k;` | 54.729 | 35.000 | 35.296 | yes |
| `SELECT MAX(l.l_extendedprice) AS m FROM Default.Lineitem_column_10 l;` | 23.681 | 0.352 | 0.347 | yes |
| `SELECT MAX(t.x) AS m FROM doublefix.Neg t;` | 1.268 | 0.215 | 0.237 | yes |
| `SELECT MIN(t.x) AS m FROM doublefix.Neg t;` | 1.248 | 0.222 | 0.222 | yes |

Filter activity in these runs (server log slice in the same folder):

- Cross-table: the filter was attached in all 12 Baleen runs. Rows dropped by the exchange filter (sum of the
  logged partitions): `MAX(a+b)` 1,478,659 / 1,945,946; `MIN(a+b)` 32,907 / 31,660; `MAX(b-a)` 7,567 / 11,998
  (plus 2 to 3 of 5 leaves skipped per partition); `MIN(b-a)` 0 / 0; `MAX(a-b)` 1,713,697 / 1,527,684;
  `MIN(a-b)` 42,289 / 39,474.
- GROUP BY: page filter attached for `f` (8 runs), not attached for `n` (logged "may be null or missing"),
  attached but never used for `s`. Rows reaching the row filter: 3,000,000 for `s` and `n`; 446,483 to
  501,886 for `f`.

Earlier attempts, kept:

| Folder | Server state | Outcome |
|---|---|---|
| `wrongskip_e2e_2026_11_23/` | fixes 1 to 4 | 30 runs; `G2_nullable_key` differs from baseline in both Baleen settings (3 rows instead of 5: row filter dropped null/missing keys) |
| `wrongskip_e2e_2026_11_23_run2/` | + row filter keeps null keys | 30 runs; `G3`, `G4` fail in both Baleen settings with `IllegalArgumentException` (ghost rows reach the sort) |
| `wrongskip_e2e_2026_11_23_run3/` | + cursor fix | 42 runs, all OK and equal |

## Found, not fixed

- **MAX/MIN next to another aggregate is filtered (confirmed wrong result).**
  `SET \`compiler.plaque.enabled\` "true"; SELECT g.f AS k, MAX(g.v) AS m, COUNT(*) AS c FROM doublefix.Grp g GROUP BY g.f ORDER BY k;`
  returned counts 152,772 / 147,312 / 147,312 for A / N / R on the final server; with Baleen off the counts are
  1,000,000 each (MAX values equal). The GROUP BY rewrite does not check that MAX/MIN is the only aggregate
  (review item D4).
- **Cross-table MIN can lose its filtering.** In `MIN(b - a)` the best value stayed at 1,469.27 (true answer
  -1,515.27) and no row was dropped. `PlaqueCrossExprExchangeFilterFactory.createFilter` creates the shared
  best-value state with `isMax = true`; if it runs before the aggregate creates it, a MIN query tracks a
  maximum. Results stay correct (the bound only gets looser). Cause read from the code, not proven by a test.
- Still open from the review: rounding of the cross-table bound in the last digit, a bound possibly taken over
  only some build partitions, Long extremes.

## Problems met on the way

- Bug 4 was first fixed only in the page filter; the end-to-end check showed the row filter and then the
  storage cursor (three rebuild and restart rounds).
- The query service leaves out a comma after `requestID`; hand-written JSON parsing failed twice.

## Decisions I made without asking

- Bug 3: short string keys (1 to 3 characters) keep page skipping. A key of at most 3 characters can still
  collide with a longer key that continues with the character U+0000 or U+0001; I accepted that. The strict
  alternative is no GROUP BY page skipping on any string key.
- Bug 3 narrows the filter: TPC-H `l_shipmode` keeps page skipping only for `AIR` and `FOB`; `p_brand` loses
  it. Bug 4 narrows it for optional and undeclared group fields. All TPC-H tables on this server declare
  every field without `?`, so they are not affected by bug 4.
- A general fix for bug 4 would need a per-leaf null count in the storage format; not done.
- Fixed the row filter and the storage cursor (hyracks module), which were not in the list of four.
- Created `doublefix.Probe`, `doublefix.Build`, `doublefix.Grp` and their data files on the benchmark server.
- Ran the end-to-end check with one run per cell without presenting a plan first.
- Did not run the code-review agent on this change.

## Not verified

- The wrong results of bugs 1 to 3 were not reproduced end to end on the old code (the first server start
  already had the fixes); bug 4's row-filter part was (attempt 1).
- Top-K path of bugs 1 and 2: unit test of the shared bound only, no query.
- Leaf skipping in the cross-table check happened only for `MAX(b - a)`; the other shapes exercised the
  exchange (row) filter.
- No 6-run benchmark; the TPC-H GROUP BY and scalar numbers above are single runs.
