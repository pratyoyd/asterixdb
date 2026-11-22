# R1a row test: implementation and review (2026-11-22)

Status: built, unit-tested, running on the benchmark server (restarted 2026-11-22 after the user said "you do
what needs to be done"; storage kept). Nothing committed on `baleen`.
Design: `2026_11_21_PLAQUE_R1A_ROW_TEST_HLD.md`, `2026_11_21_PLAQUE_R1A_ROW_TEST_LLD.md`.

## What was built

- **Native columnar.** The scan's one row-filter slot now holds a combined filter: the query's own pushed-down
  WHERE first, then each Baleen row test; a row is kept only if all pass. MAX/MIN test: the tuple filter's own
  test (same best value, same comparator, an equal value dropped), on any top-level field, declared or not, any
  type. DISTINCT's row drop moved into the same filter, so it now works when the query has a WHERE on the same
  table.
- **Iceberg.** After Iceberg reads a row, before AsterixDB converts it, rows that cannot beat the best value are
  dropped. Columns of type int, long, float, double; on every read path (standard, deletes, variant-pruned,
  DISTINCT level 4).
- **Setting** `compiler.plaque.rowfilter` (default true) switches the MAX/MIN row test off.
- **Plan output** `plaque-row-test: MAX(<field>), <handle>` on the scan line. Counters in `PLAQUE_FILTER_STATS`
  (`scanRowsTested`, `scanRowsDropped`, `scanRowTestDeclines`) and an Iceberg log line
  `plaque iceberg row test: ... rows dropped of ... tested`.

## Where it does not apply

| Case | Kind | Example query |
|---|---|---|
| Nested field | User: later (2026-11-21) | `SELECT MAX(o.addr.zip) FROM Orders_column o` |
| Iceberg column not int, long, float, double | User: basic types first (2026-11-21) | `SELECT MAX(l.l_shipdate) FROM baleen_ice.lineitem l` (string) |
| Field holding arrays in a component | Real: no single value per row | `MAX(x)` where `x` is an array in some rows |
| Path from aggregate to scan crosses GROUP BY, ORDER, LIMIT, UNNEST, SUBPLAN, window, union, replicate, index nested-loop join | Real for most (rows may be needed elsewhere); index nested-loop join not looked into | `SELECT MAX(x) FROM (SELECT ... GROUP BY ...)` |
| Field access placed above a join | Not looked into (reviewer N2) | `SELECT MAX(r.x) FROM l JOIN r ...` if the access is not pushed below the join |
| Primary-key field | Real: no field access to attach to (reviewer N3, not run) | `SELECT MAX(o.o_orderkey) FROM Orders_column_10 o` |
| Field of a dataset's meta part | Real: the scan's row filter reads the record only | `SELECT MAX(m.x)` over the meta variable |

## Deviations from the design

- Left outer joins are crossed on both sides (LLD amended 2026-11-21). The HLD said "only row-by-row steps in
  between"; this widening has not been confirmed by the user.
- Counters report and reset per line (LLD amended 2026-11-22).
- The Iceberg counts are on their own log line, not on the existing `plaque iceberg scan:` line.
- One Iceberg test class instead of the two the LLD listed.

## Tests (JUnitCore; no harness)

| Class | Module | Tests | Result |
|---|---|---|---|
| `PlaqueRowFilterTest` (new) | asterix-column | 9 | pass |
| `PlaqueDistinctColumnFilterTest` (adapted) | asterix-column | 4 | pass |
| `IterableFilterEvaluatorInterruptTest` (existing) | asterix-column | 2 | pass |
| `PlaqueIcebergRowTestTest` (new) | asterix-external-data | 6 | pass |
| `PlaqueIcebergScanFilterTest`, `PlaqueDistinctDictionaryReaderTest`, `PlaqueIcebergDistinctScanFilterTest` (existing) | asterix-external-data | 28 | pass |

Not covered by any test (reviewer): the rule's plan walk, the `IndexUtil` composition, the Iceberg reader wiring,
the real columnar reader path with deletes.

## Review (code-reviewer agent, 2026-11-22)

Verdict: safe to benchmark on the planned queries. Findings:

| Id | Finding | Severity | Done |
|---|---|---|---|
| B1 | Row test could attach to a field of the meta part and filter by the record's field of the same name: wrong result | Medium | **Fixed**: the field must be on the scan's record variable |
| B5 | Best value and its length were two separate fields; a scan reading while the aggregate updates could compare with a mismatched length (string MIN/MAX) | Low, wrong result possible | **Fixed**: `passes` uses the array's own length |
| B2 | The first partition's tuple filter to close removes the job's state; a partition opening a component later gets an empty state and keeps all rows (lost benefit, small leak); Iceberg counts may miss the stats line | Low, no wrong result | **Fixed** (user go-ahead 2026-11-22): the job's state is dropped when its last tuple filter on the process closes |
| B3 | With an array column in the WHERE, counters count per array element, not per row | Low, counters only | Documented here, not changed |
| B4 | A declined test on an array column still forces the repeated-column evaluator onto a query that had no row filter | Low | **Fixed**: a test that cannot be used returns nothing, and with no WHERE and no test left the scan gets no row filter |
| B6 | Primary-key column readers in the row filter, only if a key field ever reached the test | Very low, not confirmed | None |
| D-b | With the row test off, the native page skip again hands out empty records for skipped leaves, so "row test off vs on" mixes two effects | Measurement | **Fixed**: with the row test off a keep-all row filter goes with the native MAX/MIN page skip |
| S1 | `compiler.plaque.rowfilter` works per query only, not from cc.conf (like the other PLAQUE settings) | Note | None |
| Opt | Per-row `LongAdder` increments on a shared state; Iceberg `getField(name)` per row | Overhead | Waiting for user |

The same meta-part check is missing in the existing DISTINCT and Iceberg attach code (from before this change); not
changed.

## Found while running it: DISTINCT with a WHERE never compiled

`SELECT DISTINCT l.l_shipmode FROM Lineitem_column_10 l WHERE l.l_quantity > 10` hung in the compiler (one thread
at 100 % for 20 minutes, thread dump in `ConsolidateSelectsRule.rewritePre`). Cause: an earlier PLAQUE edit to
`ConsolidateSelectsRule` (hyracks algebricks-rewriter) stops at a PLAQUE select without moving on, and the outer
loop then meets the same select again, forever. It happens whenever the query's WHERE select sits above a PLAQUE
select; the DISTINCT tuple filter is placed below the WHERE, the MAX/MIN tuple filter above it, so only DISTINCT
hit it. So the "DISTINCT gets no row drop with a WHERE" limit of 2026-11-20 was never reached on the server: such a
query did not compile at all.

Fixed: the rule now treats a PLAQUE select as the end of the run of selects to merge. Regression test
`ConsolidateSelectsPlaqueTest` (asterix-algebra, 2 tests): passes on the fixed rule, times out on the old one.

Also found: the setting was first named `compiler.plaque.row.filter` by the enum naming rule; renamed to
`compiler.plaque.rowfilter` as agreed in the HLD.

The Iceberg services (S3Mock, Nessie) were not running; started with `iceberg/start.sh`.

## Decisions made without asking

- B1 and B5 fixed without asking (wrong-result class, as announced); B2, B4, D-b fixed under the user's general
  go-ahead ("you do what needs to be done"), as proposed.
- The `ConsolidateSelectsRule` loop fixed (blocks the DISTINCT part of the task).
- Counters reset per line.
- The R1a diff for the reviewer was made against copies of each touched file saved or rebuilt during the session,
  because the tree holds older uncommitted work.

## Stated but not verified

- Nothing has run on the server: plans, results and counters are unchecked.
- Reviewer N1 to N3 (index join, access above a join, key fields) are read from the code, not run.
