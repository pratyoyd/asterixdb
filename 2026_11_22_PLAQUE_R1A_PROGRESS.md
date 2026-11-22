# R1a row test and scan flush: progress and day summary (2026-11-22)

Status (end of 2026-11-22): built, unit-tested, benchmarked, with the scan flush added the same day.
**Committed on `baleen` as `e933aabef6`** (user: "just commit everything"), not pushed. The scan-flush code
was **not** reviewed by the code-reviewer agent.
Design: `2026_11_21_PLAQUE_R1A_ROW_TEST_HLD.md` (corrected 2026-11-22, section 5),
`2026_11_21_PLAQUE_R1A_ROW_TEST_LLD.md` (amendment section 10).

## The day in order (2026-11-22)

| Step | Outcome | Where |
|---|---|---|
| Implementation and review of R1a | Built; reviewer findings B1-B6, D-b; fixes listed below | this note |
| DISTINCT with a WHERE | Found never to compile (`ConsolidateSelectsRule` loop); fixed | this note |
| Correctness check, 24 runs | All equal to Baleen off | this note |
| Benchmark, 228 runs | Row test a clear win on N3, N6, I6; **slower than page skip on N1, N2, I1, I2** | `2026_11_22_PLAQUE_R1A_RESULTS.md` |
| Interpretation | Cause: the scan's own frame held kept rows, so the best value was stale | same, O4/O5 |
| Scan flush (agreed with the user) | Built, unit-tested, correctness check 24 runs | LLD section 10 |
| Delta benchmark, 212 runs | Row test now matches or beats page skip on every MAX/MIN query; worst cost +9 % (H2) | `2026_11_22_PLAQUE_SCAN_FLUSH_RESULTS.md` |
| Review of the skills and agent, LLD skill format | Given in chat, recorded | `2026_11_22_PLAQUE_SKILLS_REVIEW.md` |
| Commit | `e933aabef6` on `baleen` (64 files) | - |

## Headline numbers (median of 6 runs, seconds)

| Query | Baleen off | Page skip only | Row test, no flush | Row test + flush |
|---|---|---|---|---|
| N1 MAX, no WHERE | 23.5 | 0.11 | 0.61 | 0.10 |
| N2 MAX, WHERE | 15.8 | 0.17 | 2.82 | 0.12 |
| N3 MIN string, WHERE | 31.7 | 35.1 | 12.3 | 11.9 |
| N6 DISTINCT, WHERE | 245.4 | 31.7 | 11.3 | 10.4 |
| I1 MAX, no WHERE | 19.1 | 2.77 | 3.08 | 0.59 |
| I2 MAX, WHERE | 30.3 | 4.12 | 5.05 | 1.08 |
| I5 MAX over a join | 38.7 | 13.5 | 12.0 | 9.9 |
| I6 DISTINCT, WHERE | 336.2 | 4.75 | 0.70 | 0.69 |

All 12 queries and H1-H5 are in the two results notes.

## Scan flush (added 2026-11-22)

- **What:** a scan whose row filter drops rows (MAX/MIN row test, or DISTINCT level 4) sends the rows it kept
  so far before deciding on the next leaf (native: `QueryColumnTupleReference.startNewPage`, via
  `ITupleProjector.setEarlyFlush` from `IndexSearchOperatorNodePushable`) or file task (Iceberg:
  `IcebergFileRecordReader`, via `IRecordReader.setEarlyFlush` from `RecordDataFlowController`).
- **Setting:** `compiler.plaque.scanflush`, default true.
- **Visible:** log line `plaque scan early sends: <n>` per scan that sent early.
- **Tests:** `PlaqueScanFlushProjectorTest` (asterix-column, 3), `PlaqueIcebergScanFlushTest`
  (asterix-external-data, 4); pass.
- **Where it fires without gain:** DISTINCT on a high-cardinality column (H1, H2: 16 K-31 K sends, same rows
  dropped; H2 +9 %); Iceberg DISTINCT level 4 on a column that is not dictionary-encoded (H4, H5: level 4 drops
  nothing, so the flush has nothing to send early that matters; no measurable cost).

## Open items

| Item | Waiting for |
|---|---|
| H2 cost: leave the flush as is, or send early only when few rows were kept | User decision (my recommendation: leave) |
| Code-reviewer on the scan-flush code | Not run; user chose to commit |
| Nullable-column worst case | Needs a data load |
| Left outer joins crossed on both sides | User confirmation |
| Per-row `LongAdder` / Iceberg `getField(name)` overhead (reviewer "Opt") | User decision |
| N6 Baleen off re-check 5 % above the morning's range | Not explained |
| Iceberg DISTINCT level 4 does nothing on high-cardinality columns | Known consequence of dictionary-only drop; not in scope |
| Skill edits proposed | User decision; no skill edited |

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

- (Superseded 2026-11-22: everything has since run on the server; see the results notes.)
- The scan-flush change has not been reviewed by the code-reviewer agent.
- Reviewer N1 to N3 (index join, access above a join, key fields) are read from the code, not run.
