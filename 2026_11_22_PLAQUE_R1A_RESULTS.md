# R1a row test: benchmark results and interpretation (2026-11-22)

Design: `2026_11_21_PLAQUE_R1A_ROW_TEST_HLD.md`, `..._LLD.md`. Build: `baleen` working tree, uncommitted R1a
code (see `2026_11_22_PLAQUE_R1A_PROGRESS.md`). One server (AsterixHyracksIntegrationUtil, 4 partitions in one
process), restarted before the run, storage kept. Iceberg on S3Mock + Nessie on the same machine.

Raw data: `/scratch/asterixdb_eightynode/build_logs/bench_r1a_2026_11_22/run/` (`runs.csv`, 228 rows;
`summary.md`; `plan.json`). Server log `build_logs/asterix_r1a.log`. Run 01:39:29 to 03:32:39.

## Plan

6 runs per configuration, round-robin per query (off, row test off, row test on, repeated 6 times), one
warm-up (run 0) per query in "row test on", recorded and not counted.

| Config | Settings sent |
|---|---|
| Baleen off (baseline) | `SET compiler.plaque.enabled "false";` |
| Baleen on, row test off | `enabled "true"; rowfilter "false"; distinct.level "3"` |
| Baleen on, row test on | `enabled "true"; rowfilter "true"; distinct.level "4"` |

"Row test off" is page/file skipping plus the tuple filter above the scan (on native also the keep-all row
filter added by the D-b fix, so that skipped leaves are not handed out as empty records). For N6/I6 it is
DISTINCT level 3 versus level 4.

| Id | Query |
|---|---|
| N1 | `SELECT MAX(l.l_extendedprice) AS m FROM Default.Lineitem_column_10 l;` |
| N2 | `SELECT MAX(l.l_extendedprice) AS m FROM Default.Lineitem_column_10 l WHERE l.l_shipmode = "AIR";` |
| N3 | `SELECT MIN(l.l_shipdate) AS m FROM Default.Lineitem_column_10 l WHERE l.l_quantity > 10;` |
| N4 | `SELECT MAX(l.l_extendedprice) AS m FROM Default.Lineitem_column_10 l WHERE l.l_quantity = 1;` |
| N5 | `SELECT MAX(l.l_extendedprice) AS m FROM Default.Lineitem_column_10 l, Default.Orders_column_10 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = "F";` |
| N6 | `SELECT DISTINCT l.l_shipmode FROM Default.Lineitem_column_10 l WHERE l.l_quantity > 10;` |
| I1 | `SELECT MAX(l.l_extendedprice) AS m FROM baleen_ice.lineitem l;` |
| I2 | `SELECT MAX(l.l_extendedprice) AS m FROM baleen_ice.lineitem l WHERE l.l_shipmode = "AIR";` |
| I3 | `SELECT MIN(l.l_shipdate) AS m FROM baleen_ice.lineitem l WHERE l.l_quantity > 10;` |
| I4 | `SELECT MAX(l.l_suppkey) AS m FROM baleen_ice.lineitem_by_suppkey l;` (table sorted on the column) |
| I5 | `SELECT MAX(l.l_extendedprice) AS m FROM baleen_ice.lineitem l, baleen_ice.orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = "F";` |
| I6 | `SELECT DISTINCT l.l_shipmode FROM baleen_ice.lineitem l WHERE l.l_quantity > 10;` |

## Completeness and correctness

228 of 228 runs recorded (12 queries x 3 configs x 6 + 12 warm-ups), `DONE` present, 0 failed, 0 differing
from Baleen off (md5 of the result). `bench_summary.py` exit 0.

## Raw results (seconds, all 6 measured runs)

| Query | Baleen off | Row test off | Row test on |
|---|---|---|---|
| N1 | 23.7 23.6 23.1 25.1 23.4 23.1 | 0.2 0.1 0.1 0.1 0.1 0.1 | 0.3 1.0 0.4 1.3 0.6 0.7 |
| N2 | 16.1 16.1 14.4 15.5 15.2 16.1 | 0.3 0.2 0.2 0.2 0.2 0.2 | 3.8 5.2 3.2 1.2 2.2 2.5 |
| N3 | 32.6 31.9 30.8 31.0 31.4 32.1 | 35.2 33.5 34.3 36.1 37.6 35.0 | 12.1 12.0 13.3 12.5 12.4 12.0 |
| N4 | 7.4 7.6 7.5 7.0 7.0 7.0 | 8.5 8.0 7.9 7.7 7.9 8.0 | 7.1 7.4 7.3 7.5 7.3 7.4 |
| N5 | 33.1 33.3 33.1 34.8 33.7 33.7 | 6.8 6.1 6.0 6.3 6.5 5.9 | 6.1 6.0 6.0 6.2 6.1 6.3 |
| N6 | 248.8 251.4 237.0 245.9 245.0 244.6 | 30.9 31.8 31.4 31.8 31.6 31.9 | 11.5 11.2 11.4 10.6 11.7 10.5 |
| I1 | 19.0 19.6 18.8 19.7 19.3 18.6 | 3.0 2.8 2.8 2.6 2.5 2.8 | 1.1 2.9 2.9 5.3 3.3 3.7 |
| I2 | 29.0 30.6 30.0 30.6 32.1 29.2 | 4.2 3.7 3.8 4.3 4.0 4.2 | 5.2 3.2 2.9 5.6 4.9 5.3 |
| I3 | 32.5 31.6 31.3 32.1 32.2 32.0 | 31.7 32.7 33.6 30.7 30.9 31.1 | 31.9 31.0 31.2 31.7 31.6 30.7 |
| I4 | 17.1 18.0 18.3 17.6 17.6 17.1 | 2.6 2.3 2.5 2.6 2.7 2.5 | 2.9 2.8 2.6 2.8 2.9 2.6 |
| I5 | 39.9 37.1 38.5 39.0 40.0 38.5 | 14.0 14.2 13.0 12.8 13.3 13.7 | 10.9 12.5 9.9 11.5 14.6 14.5 |
| I6 | 334.7 328.7 360.6 350.9 335.5 336.9 | 4.6 4.6 5.3 4.9 4.3 5.0 | 0.7 0.7 0.7 0.7 0.6 0.7 |

## Medians and speedups

| Query | Off (s) | Row test off (s) | Row test on (s) | On vs Baleen off | On vs row test off (page skip only) |
|---|---|---|---|---|---|
| N1 | 23.5 | 0.1 | 0.6 | 38x | **0.18x (slower)** |
| N2 | 15.8 | 0.2 | 2.8 | 5.6x | **0.06x (slower)** |
| N3 | 31.7 | 35.1 | 12.3 | 2.6x | 2.9x |
| N4 | 7.2 | 8.0 | 7.3 | 0.98x | 1.1x |
| N5 | 33.5 | 6.2 | 6.1 | 5.5x | 1.0x |
| N6 | 245.4 | 31.7 | 11.3 | 22x | 2.8x |
| I1 | 19.1 | 2.8 | 3.1 | 6.2x | **0.89x (slower)** |
| I2 | 30.3 | 4.1 | 5.0 | 6.0x | **0.82x (slower)** |
| I3 | 32.0 | 31.4 | 31.4 | 1.02x | 1.0x (row test off for strings, by design) |
| I4 | 17.6 | 2.6 | 2.8 | 6.3x | 0.93x |
| I5 | 38.7 | 13.5 | 12.0 | 3.2x | 1.1x |
| I6 | 336.2 | 4.8 | 0.7 | 479x | 6.9x |

## Counters (medians of 6 runs, from `summary.md`)

| Query | Config | Rows reaching row test (passed page/file skip) | Dropped in scan | Rows into tuple filter | Iceberg files skipped |
|---|---|---|---|---|---|
| N1 | row test off | - | - | ~40,000 | - |
| N1 | row test on | 1.9M to 9.1M | 99.6 % | ~10,000 | - |
| N2 | row test off | - | - | ~35,000 | - |
| N2 | row test on | 0.9M to 4.1M | 99.6 % | ~10,000 | - |
| N3 | row test off | - | - | 47,988,531 (all rows passing the WHERE) | - |
| N3 | row test on | 47,988,531 | 47,978,000 | ~10,000 | - |
| I1 | row test off | - | - | 8,000,000 | 26 every run |
| I1 | row test on | 16M to 56M | 99.98 % | ~10,000 | 22, 2, 3, 2, 2, 1 |
| I2 | row test off | - | - | 1,142,304 | 26 every run |
| I2 | row test on | 32M to 60M | 99.9 % | ~8,000 | 0, 13, 14, 4, 0, 2 |
| I4 | row test off | - | - | 7,986,052 | 26 |
| I4 | row test on | 7,986,052 | 6.1M | 1.87M | 26 |
| I5 | row test off | - | - | 8,886,725 | 24 every run |
| I5 | row test on | 15M to 58M | 99.98 % | ~5,000 | 12, 2, 15, 10, 1, 3 |
| N6 | row test on | - | 47,976,655 (DISTINCT) | - | - |
| I6 | row test on | - | 7,983,520 (DISTINCT, Iceberg) | - | - |

## Observations

| # | Observation | Expected? |
|---|---|---|
| O1 | All 216 measured runs correct. | Expected |
| O2 | Where page skipping does nothing, the row test wins as designed: N3 (string MIN) 35.1 to 12.3 s, 47.98M of 47.99M rows dropped in the scan. | Expected |
| O3 | DISTINCT with a WHERE on the same table now gets the row drop: N6 31.7 to 11.3 s (level 3 to 4), I6 4.8 to 0.7 s. Before the ConsolidateSelects fix these queries did not compile at level 4. | Expected |
| O4 | **With the row test on, page skipping and file skipping get much worse.** N1: about 40,000 rows got past page skipping with the row test off; 1.9M to 9.1M with it on. I1: 26 of 30 files skipped every run with it off; 1 to 22 with it on. Same for N2, I2, I5. On N1, N2, I1, I2 this makes "row test on" slower than page skip alone (N2 0.2 to 2.8 s). | **Unexpected.** The HLD said this result means the design is wrong for these queries |
| O5 | "Row test on" varies a lot between runs on exactly these queries (N2 1.2 to 5.2 s; I1 1.1 to 5.3 s; files skipped 1 to 22); "row test off" is steady (N2 0.2 to 0.3 s). | Unexpected |
| O6 | N3 and N4 with "row test off" are about 10 % slower than Baleen off (31.7 to 35.1 s; 7.2 to 8.0 s). Page skipping skips nothing on N3 (all 47,988,531 rows reach the tuple filter). | Partly expected (cost when nothing is skipped); size not predicted |
| O7 | Worst case N4 (selective WHERE): row test on 7.3 s vs Baleen off 7.2 s: no measurable cost. | Expected |
| O8 | I4 (sorted): row test on 2.8 vs off 2.6 s; 1.87M of 7.99M rows pass the row test and then mostly pass the tuple filter too (new maxima keep coming in sorted order). | Expected |
| O9 | I3: no rows dropped, as designed (strings off on Iceberg), and the log says so. | Expected |
| O10 | N5 and I5 (join): page skip already gives most of the gain; the row test adds 0 to 10 %, while file skipping on I5 drops from 24 to 1-15. | Mixed: net gain, but the O4 effect is present |

## Investigation of O4 and O5

**Candidate causes.** (a) The row test delays the best value reaching the shared state, so page and file
skipping work with an old value for most of the scan. (b) The row test's own cost per row. (c) Something in
the combined filter turns page skipping off for some leaves.

**Checked (code, no new runs).** The scan sends its output frame only when the frame is full or the scan ends
(`IndexSearchOperatorNodePushable`: `FrameUtils.appendToWriter`; flush only in `flush()`/close). The tuple
filter sends rows up after every frame it receives, and the aggregate updates the best value when rows arrive.
So the best value can only improve when the scan's frame is sent.

- Row test off: every row on a leaf that page skipping did not skip goes into the frame. A frame of these
  rows holds about 1,900 tuples (the `thresholdUninitTuples` value, 1927, in the log is one frame), so frames
  fill and are sent quickly, and the best value improves many times during the scan.
- Row test on: only rows that beat the best value go into the frame. About 10,000 rows in total per run,
  over 4 partitions, is about 1.3 frames per partition. So after the first frame, a partition's second frame
  is typically sent only at the end of its scan. The rows that would raise the best value sit in a frame that
  is not yet sent, and page skipping, file skipping and the row test itself go on using the value from the
  first frame.

Concretely for N1: the first frame sets a best value of roughly the best of 1,900 rows. With the row test off,
the next frames bring values near the true maximum within the first few leaves, and page skipping then skips
almost everything (40,000 rows read in total). With the row test on, the scan keeps the rows that beat the
first value but holds them; page skipping keeps comparing against the first value and reads 2M to 9M rows.

Fits the counters: rows past page skip go up 50 to 200x (N1, N2), Iceberg files skipped go down from 26 to as
few as 1 (I1, I2, I5); the effect depends on when the second frame happens to fill, which explains O5; I4 is
not affected (sorted data keeps frames filling, 1.87M rows reach the tuple filter); N3 and N6 are not affected
because page skipping does nothing there anyway.

Cause (b) does not fit: N4 tests 1.2M rows per run at no measurable cost, and N3 tests 48M rows and is
faster. Cause (c) is not ruled out by the code read, but does not explain the run-to-run variance.

**Status: (a) is supported by the code path and the counters; not confirmed by a direct measurement.** I have
not counted frames sent by the scan or recorded when the best value changes.

## Investigations proposed (need your OK; not run)

| # | Check | Separates | Cost |
|---|---|---|---|
| P1 | Rerun N1, N2, I1 with the row test on and a frame a quarter of the server's current size (`SET compiler.framesize ...`; the size this server runs with is not checked yet: the code default is 1 KB, which cannot hold 1,927 tuples, so the integration setup overrides it), 6 runs, plus row test off at the same frame size. If (a) is right, rows past page skip and time drop toward the "row test off" level. | (a) vs (b)/(c), no code change | ~10 min |
| P2 | Add one counter: frames sent by the scan, and the number of times the shared best value changed, on the existing stats line. | Confirms (a) directly | Code change + rebuild + restart, ~30 min |
| P3 | Profile N3 "row test off" vs Baleen off (O6): keep-all row filter vs tuple filter cost. | Cost of D-b fix vs tuple filter | One run each with a profiler, ~15 min |

## Proposed changes (proposals only; nothing edited)

**LLD change (if P1 or P2 confirms (a)).** When the row test is active, the scan sends its frame early: at the
end of every leaf (native) or every row group/file task (Iceberg) when the frame is not empty, instead of only
when full. Few rows are kept, so the extra frames are small and few (at most one per leaf). This restores the
feedback that page and file skipping depend on. Affects the LLD sections on the native reader and the Iceberg
reader; touches the scan operator (`IndexSearchOperatorNodePushable` or the PLAQUE path into it) and the
external scan.

Alternative: the row test raises the shared best value itself when it keeps a row (it already has the value).
Faster feedback, but correct only when every kept row is sure to reach the aggregate: true for N1-N4 (WHERE
checked first), false across a join (N5), where the join may drop the row and the best value would then be
too high, giving a wrong result. That changes who writes the state, so it would be an HLD amendment; I do not
recommend it.

**HLD.** Section 5 ("only the frame buffers between scan and aggregate, flushed per frame") is the claim that
broke: it is true, but the row test makes frames fill far more slowly, which the HLD did not consider. Proposed
dated note there, and D8 (default on) revisited after the fix: with today's code, default on makes N2 14x slower
than page skip alone.

**No change** for DISTINCT (O3) and the no-page-skip case (O2): they work as designed.

## Worth flagging for the paper

A filter inside the scan can starve its own feedback loop: by dropping the rows that would tighten the bound,
it delays the bound, and the coarser skipping (pages, files) that shares the bound gets worse. Numbers: N1
rows past page skipping 40K to 2-9M; I1 files skipped 26 to 1-22. The general lesson: when a filter both
consumes and (indirectly) produces the bound, its output rate sets the feedback latency. Closest known
techniques: sideways information passing / runtime filters with adaptive refresh; looks new to me in this
form, not searched.

## Problems met on the way

- `bench_runner.py` fails with `SameFileError` when `plan.json` sits inside the output directory; plans now
  live one level up.
- Nothing else: no cut-off, no restart during the run.

## Decisions made without asking

- Interpreted "speedup over plain page skip" as "row test on" vs "Baleen on, row test off". On native that
  baseline includes the keep-all row filter (D-b fix), so it is not exactly the code before R1a; on Iceberg it
  is.
- N6/I6 "row test off" is DISTINCT level 3, so their second speedup is level 3 vs level 4.
- Log lines were matched to runs by time window when counting `thresholdUninitTuples`.

## Stated but not verified

- Cause (a) of O4/O5 (see above): supported, not measured directly.
- About 1,900 tuples per frame: inferred from `thresholdUninitTuples=1927`, not read from the frame size.
- That the early-flush fix would recover the page-skip speed: untested.
- One machine, one data size (scale factor of `_10` tables), four partitions in one process (shared state
  needs no network hop here; on a real cluster the delay could be longer).
