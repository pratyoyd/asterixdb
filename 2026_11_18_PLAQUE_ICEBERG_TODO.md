# Baleen on Iceberg: to-do list from the progress-notes review (2026-11-18)

Written while preparing the 19 November progress notes. Nothing was run on AsterixDB today (other experiments
were using the server); everything below comes from saved logs, the notes of 11-15 to 11-17 and the code.

## Results slide as it stands

TPC-H SF10, ties dropped, medians of 3 runs (runs of 2026-11-16, `build_logs/bench_result_iceberg_strict.md`,
`bench_result_internal_strict.md`).

| Filter type | Speedup on Iceberg | Speedup on native columnar | Files skipped on Iceberg (of 30) |
|---|---|---|---|
| MAX, single table, unordered data | 7.68x | 11.26x | 26 |
| MAX, single table, sorted on the MAX column | 6.38x | missing | 26 |
| MAX with join (unordered) | 3.10x | 4.89x | 26, 14, 26 across the 3 runs (slide says 20) |
| GROUP BY MAX | 1.74x | 1.47x | 0, tuple filter only |

Two cells are not trustworthy yet: the native number for the sorted table (never measured) and the join row
(file skipping differs between runs).

## To do

| # | Item | Why | Status |
|---|---|---|---|
| 1 | Get a stable reading for MAX with join on unordered `lineitem` | Files skipped were 26, 14, 26; times 11.2, 20.5, 11.1 s | Cause identified from the log and code, not reproduced. See "Join flakiness" |
| 2 | Log the bound's value each time the Iceberg reader opens a task | Needed to confirm item 1; today only the end-of-scan counters are logged | Not done |
| 3 | Early send for the MAX tuple filter and the joins under it, reusing the DISTINCT early send (`compiler.plaque.distinct.flush`, `fj`) | Expected fix for item 1 | Not done; read the existing DISTINCT code first |
| 4 | Create native columnar `Lineitem_column_by_suppkey_10`, primary key `(l_suppkey, l_orderkey, l_linenumber)` | No native table sorted on `l_suppkey` exists, so the sorted row has no native number | Not done |
| 5 | Run the benchmark matrix below | Fills the missing cell, redoes the join row, brings everything to 6 runs | Waiting for the user's OK and a free server |
| 6 | Theta join on Iceberg, both layouts, 6 runs | Only one run each exists, on the sorted table only (66.1 s off, 17.8 s on) | Not done |
| 7 | Hand the cross-table bound to the Iceberg reader | Native 7.43x, Iceberg 1.25x: `attachCrossExprPageFilter` accepts only a native columnar scan | Not built |
| 8 | Correct `2026_11_15_PLAQUE_ICEBERG_DATA_SKIPPING.md` and `2026_11_16_PLAQUE_ICEBERG_REVIEW_TODO.md` | Both say unordered `MAX(l_suppkey)` skips "3 or 4 of each reader's 7 or 8 files"; the log shows 26 of 30 in all 3 runs | Not done |

## Proposed matrix for item 5 (not approved yet)

| | |
|---|---|
| Tables | Iceberg `lineitem` (unordered), Iceberg `lineitem_by_suppkey` (sorted), native `Lineitem_column_10` (unordered on `l_suppkey`), native `Lineitem_column_by_suppkey_10` (sorted, new) |
| Queries | `MAX(l_suppkey)`; `MIN(l_suppkey)`; `MAX(l_suppkey)` joined with orders on `l_orderkey`; `MAX(l_discount) GROUP BY l_shipmode` |
| Configurations | Baleen off, Baleen on |
| Runs | 6 per configuration, alternating off and on, one discarded warm-up per query and table |
| Total | 4 x 4 x 2 x 6 = 192 measured runs |
| Recorded per run | Time, result compared with Baleen off, files skipped and files read with a row-group filter (Iceberg only, as agreed) |

Limits in the code that shape the numbers: skipping handles top-level long and double columns only; the bound
is checked once per task (one task per file in these tables); ties are dropped; the 4 node controllers share
one JVM, so the MAX/MIN bound is shared without messages.

Prediction to check: a native scan runs in ascending primary-key order, so on the native table sorted on
`l_suppkey`, MAX should gain little and MIN a lot, while Iceberg gets 6-7x on both because it reorders files.

## Files skipped per run, recovered from the log

Source: `build_logs/asterix_strict.log`, one counter line per reader per run, matched to queries by job order
and timing.

| Query | Speedup on Iceberg | Files skipped of 30, runs 1 / 2 / 3 |
|---|---|---|
| MAX(l_suppkey), unordered | 7.68x | 26 / 26 / 26 |
| MAX(l_suppkey), sorted | 6.38x | 26 / 26 / 26 |
| MIN(l_suppkey), sorted | 7.22x | 26 / 26 / 26 |
| MAX(l_orderkey), unordered table | 6.80x | 26 / 26 / 26 |
| Join MAX(l_suppkey), unordered | 3.10x | 26 / 14 / 26 |
| Join MAX(l_suppkey), sorted | 4.33x | 26 / 26 / 26 |
| GROUP BY MAX(l_discount) BY l_shipmode | 1.74x | 0 / 0 / 0 |
| Cross-table MAX(l_extendedprice - ps_supplycost) | 1.25x | 0 / 0 / 0 |
| Scan only, no Baleen operator | 0.98x | 0 / 0 / 0 |

The zeros: the reader logs a counter line only when a scan filter is attached. The log has 72 such lines,
exactly the six scan-filter queries x 3 runs x 4 readers.

## Join flakiness (item 1)

Jobs 0.32, 0.34, 0.36 in the log (Baleen on, join on unordered `lineitem`):

| | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| Time | 11.2 s | 20.5 s | 11.1 s |
| Rows scanned per reader | 2,000,000 | 8,000,000 | 2,000,000 |
| Files per reader: full / with row-group filter / skipped | 1 / 0 / 6-7 | 1 / 3 / 3-4 | 1 / 0 / 6-7 |
| Rows passed by the tuple filter, per reader | 4,843-8,142 | 5,507-8,747 | 3,273-8,887 |

Reading:

- Every file's max for `l_suppkey` is 100000, so a file is skipped only when the bound is exactly 100000.
  A bound of 99999 skips nothing, and the row-group filter cannot help either.
- In run 2 the filter passed about as many rows over 4 files as the other runs did over 1, so the bound was
  already just below the maximum during files 2 to 4.
- The MAX tuple filter (`PlaqueFilterRuntimeFactory`) hands its output on only when a frame is full. Near the
  end only rows equal to 100000 pass, about 20 per file, so they wait in partly filled frames in the filter,
  the exchange and the join, and the aggregate does not see 100000.
- This is the frame stall found for DISTINCT on 2026-11-16. The early send that fixed it there is applied
  only to the DISTINCT filter and the joins beneath it.
- Sorted tables are not affected: the other files' maxes are far below the first file's values.

## Decisions I made without asking

- Wrote this note in `plaque/`; it is not committed.
- Before the user's "don't run anything" message arrived, sent two metadata queries to the server (dataset
  names only).

## Stated but not verified

- The cause of the join flakiness: the bound's value during files 2 to 4 is not logged, and the run was not
  reproduced.
- That single-table unordered MAX is unaffected; it skipped 26 in 3 of 3 runs, which is too few to be sure.
- The matching of log lines to queries (by job order and timing).
- The prediction for the native sorted table.
- That the early send will fix the join case as it did for DISTINCT.
