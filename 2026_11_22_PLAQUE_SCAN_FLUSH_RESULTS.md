# Scan flush: results (2026-11-22, afternoon)

Design: `2026_11_21_PLAQUE_R1A_ROW_TEST_LLD.md` section 10 (amendment 2026-11-22). Morning results:
`2026_11_22_PLAQUE_R1A_RESULTS.md`. Code uncommitted on `baleen`; server restarted on the scan-flush build,
storage kept. Data: TPC-H SF10, native `*_column_10` and Iceberg `baleen_ice.*`, one machine, 4 partitions.

**Scan flush** (`compiler.plaque.scanflush`, default true): a scan whose row filter drops rows sends the rows it
has kept so far before it decides on the next leaf (native) or the next file (Iceberg). Before this, the scan
sent a frame only when it was full, so with 399 of 400 rows dropped a new best value waited in the scan, and page
skip, file skip and the row test all worked with an old value.

Per the user's instruction ("Only run the delta stuff ... check that with two random runs"), only the new
configuration was run on the 12 morning queries; the morning's other configurations were checked by two
randomly drawn single runs (seed 20261122).

## Raw data

| Folder (under `build_logs/bench_scanflush_2026_11_22/`) | What | Rows |
|---|---|---|
| `check_single_run/` | correctness check, 12 queries x flush off / on, 1 run each | 24 |
| `recheck_1/` | random cell 1, I5 "row test on", **ran with flush on by mistake** (new default) | 1 |
| `recheck_1b/` | random cell 1 again, flush explicitly off as in the morning | 1 |
| `recheck_2/` | random cell 2, N6 Baleen off | 1 |
| `old_queries/` | 12 morning queries x "Row test on, scan flush on", warm-up + 6 | 84 |
| `harm/` | H1-H5 x 4 configurations, one warm-up per query (flush on) + 6 per configuration | 125 |

Every run is `ok` and returns the same result as Baleen off (old queries: md5 and row count compared with the
morning's Baleen off; H queries: Baleen off in the same plan). `bench_summary.py`: no missing, failed or
differing runs in any folder.

### Settings

| Configuration | Settings sent |
|---|---|
| Baleen off | ``SET `compiler.plaque.enabled` "false";`` |
| Baleen on, row test off | ``enabled "true"; rowfilter "false"; distinct.level "3"`` |
| Row test on, scan flush off | ``enabled "true"; rowfilter "true"; distinct.level "4"; scanflush "false"`` |
| Row test on, scan flush on | ``enabled "true"; rowfilter "true"; distinct.level "4"; scanflush "true"`` |

(The morning's "Baleen on, row test on" is the same as "Row test on, scan flush off": the flush did not exist.)

## 1. The two random re-checks

| Cell | Morning runs (s) | Now (s) | Inside the morning's range? |
|---|---|---|---|
| I5_max_join, row test on, flush off | 10.9 12.5 9.9 11.5 14.6 14.5 | 13.1 | yes |
| N6_distinct_where, Baleen off | 248.8 251.4 237.0 245.9 245.0 244.6 | 264.7 | **no**, 5 % above the slowest |
| (I5, row test on, flush **on**: the mistaken first draw) | - | 9.9 | not a re-check |

The I5 re-check also reproduces the morning's counters for that cell: 0 files skipped, 53.9 M rows tested.
N6 Baleen off came out 13 s above the morning's slowest run. One run cannot say whether the server got slower
or this is spread; it is a 5 % shift on a baseline, against 10x-scale effects below. Not explained.

## 2. Scan flush on, the 12 morning queries

Median [min-max] of 6 runs, seconds. Morning columns from `bench_r1a_2026_11_22/run/runs.csv`.

| Query | Baleen off | Row test off | Row test on, flush off | **Row test on, flush on** | Early sends per run |
|---|---|---|---|---|---|
| N1_max_nowhere | 23.50 [23.08-25.10] | 0.11 [0.10-0.18] | 0.61 [0.27-1.26] | **0.10 [0.09-0.10]** | 15-20 |
| N2_max_where | 15.79 [14.42-16.15] | 0.17 [0.15-0.34] | 2.82 [1.16-5.22] | **0.12 [0.12-0.13]** | 14-17 |
| N3_min_string_where | 31.68 [30.81-32.58] | 35.12 [33.49-37.63] | 12.26 [11.96-13.26] | **11.90 [11.03-12.88]** | 7-8 |
| N4_max_where_selective | 7.20 [6.97-7.63] | 7.97 [7.68-8.46] | 7.33 [7.13-7.49] | **7.20 [6.89-8.39]** | 9-10 |
| N5_max_join | 33.49 [33.10-34.77] | 6.15 [5.89-6.75] | 6.09 [5.97-6.28] | **6.36 [6.15-6.93]** | 15-22 |
| N6_distinct_where | 245.45 [237.01-251.37] | 31.70 [30.94-31.95] | 11.28 [10.46-11.71] | **10.39 [10.28-12.24]** | 4 |
| I1_max_nowhere | 19.14 [18.61-19.70] | 2.77 [2.55-3.05] | 3.08 [1.10-5.27] | **0.59 [0.58-0.70]** | 4 |
| I2_max_where | 30.30 [29.01-32.05] | 4.12 [3.69-4.35] | 5.05 [2.94-5.64] | **1.08 [1.01-1.55]** | 4 |
| I3_min_string_where | 32.03 [31.31-32.48] | 31.43 [30.73-33.58] | 31.37 [30.67-31.95] | **30.08 [29.46-31.95]** | 0 |
| I4_max_sorted | 17.60 [17.07-18.29] | 2.56 [2.29-2.68] | 2.81 [2.59-2.90] | **2.94 [2.61-3.29]** | 3-4 |
| I5_max_join | 38.75 [37.07-39.96] | 13.53 [12.77-14.22] | 12.01 [9.89-14.60] | **9.90 [9.78-10.52]** | 6 |
| I6_distinct_where | 336.24 [328.73-360.60] | 4.75 [4.29-5.32] | 0.70 [0.61-0.73] | **0.69 [0.63-0.74]** | 4 |

Speedup of flush on over Baleen off, and over plain page skip (morning "row test off"):

| Query | over Baleen off | over row test off |
|---|---|---|
| N1 | 235x | 1.1x |
| N2 | 132x | 1.4x |
| N3 | 2.7x | 3.0x |
| N4 | 1.0x | 1.1x |
| N5 | 5.3x | 0.97x |
| N6 | 23.6x | 3.1x |
| I1 | 32.4x | 4.7x |
| I2 | 28.1x | 3.8x |
| I3 | 1.06x | 1.04x |
| I4 | 6.0x | 0.87x |
| I5 | 3.9x | 1.37x |
| I6 | 487x | 6.9x |

Counters (per run, flush on): I1 26 of 30 files skipped in every run (morning flush off: as low as 3 in the
correctness check); I2 24-25; I5 22-24 (flush off: 0). N1 rows tested 27 K-34 K (flush off, correctness
check: 8.6 M).

## 3. Queries chosen to show harm

Full text:

- H1_distinct_partkey_where: `SELECT DISTINCT l.l_partkey FROM Default.Lineitem_column_10 l WHERE l.l_quantity > 10;`
- H2_distinct_partkey_join: `SELECT DISTINCT l.l_partkey FROM Default.Lineitem_column_10 l, Default.Orders_column_10 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = "F";`
- H3_min_string_join: `SELECT MIN(l.l_shipdate) AS m FROM Default.Lineitem_column_10 l, Default.Orders_column_10 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = "F";`
- H4_ice_distinct_partkey_where: `SELECT DISTINCT l.l_partkey FROM baleen_ice.lineitem l WHERE l.l_quantity > 10;`
- H5_ice_distinct_partkey_join: `SELECT DISTINCT l.l_partkey FROM baleen_ice.lineitem l, baleen_ice.orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = "F";`

Why these: `l_partkey` has about 2 M distinct values, so a DISTINCT row drop keeps many rows and a send per
leaf ships many small frames; H2, H3, H5 put a hash exchange between scan and join, where each early send
becomes a separate small frame on the network side.

Median [min-max] of 6 runs, seconds:

| Query | Baleen off | Baleen on, row test off | Row test on, flush off | Row test on, flush on | Early sends (flush on) |
|---|---|---|---|---|---|
| H1 | 182.4 [177.3-183.8] | 37.9 [36.9-39.2] | 19.2 [18.6-19.6] | 19.0 [18.2-19.2] | 15.2 K-16.6 K |
| H2 | 113.6 [111.6-116.7] | 42.8 [42.1-45.6] | 24.1 [22.6-24.7] | **26.2 [25.5-27.1]** | 27.2 K-30.9 K |
| H3 | 38.7 [37.6-40.2] | 41.0 [38.1-41.3] | 16.7 [16.1-23.0] | 17.3 [15.6-19.9] | 7-11 |
| H4 | 225.9 [218.7-240.8] | 37.2 [34.7-39.6] | 36.1 [34.5-38.6] | 37.6 [36.0-40.5] | 26 |
| H5 | 153.6 [152.6-164.3] | 46.5 [45.1-49.0] | 45.7 [44.7-48.2] | 46.3 [45.1-48.8] | 26 |

Rows dropped in the scan by DISTINCT (native): H1 43.8 M-45.5 M with either flush setting; H2 55.67 M (off) vs
55.71 M (on).

## Observations

| # | Observation | Status |
|---|---|---|
| F1 | Expected, and the main result: the flush removes the morning's slowdown of the row test. N1 0.61 → 0.10 s, N2 2.82 → 0.12, I1 3.08 → 0.59, I2 5.05 → 1.08, I5 12.0 → 9.9. Each is now at or below plain page skip; I1, I2 and I5 are 1.4x-4.7x faster than page skip. | Explained: file-skip and rows-tested counters move as the stale-value cause predicts |
| F2 | The run-to-run spread is gone where it was large: N2 [1.16-5.22] → [0.12-0.13]; I1 [1.10-5.27] → [0.58-0.70]; I5 [9.9-14.6] → [9.8-10.5]. | Explained: the spread came from how late a good value reached the scan |
| F3 | No change where the flush has nothing to fix: N3, N4, N6, I3, I6 inside or just below the morning's range. I3 has 0 early sends (string column on Iceberg: no row test, as designed). | Expected |
| F4 | I4 2.94 [2.61-3.29] vs 2.81 [2.59-2.90] flush off; N5 6.36 [6.15-6.93] vs 6.09 [5.97-6.28]. Ranges overlap; both are 0.1-0.3 s slower in median. I4 and N5 remain slower than page skip alone (0.87x, 0.97x), as in the morning. | Not explained; within the spread of 6 runs |
| F5 | **Measured harm: H2, 24.1 → 26.2 s (+9 %), ranges do not overlap.** 28 K early sends per run; DISTINCT drops the same rows either way (55.67 M vs 55.71 M), so the flush buys nothing and costs the sends. Still 4.3x faster than Baleen off. | Explained in part: no benefit is shown by the counter; that the cost is the small frames through the hash exchange is a **guess** |
| F6 | H1: 16 K early sends, no measurable cost (19.0 vs 19.2 s). Same query without the join. | Supports the exchange being where the cost is (F5), not checked |
| F7 | H3: 7-11 early sends, no measurable difference; spread of the flush-off runs (16.1-23.0) is larger. | Expected: a MIN converges fast, few sends |
| F8 | H4, H5 (Iceberg DISTINCT level 4): the reader dropped **0 rows by dictionary entry** in every run of both "row test on" configurations, so level 4 did nothing; the flush still sends before each of 26 file tasks. No measurable cost (+1.5 s and +0.6 s in median, ranges overlap). | Explained: `l_partkey` has about 2 M distinct values, far more than fits in a Parquet dictionary page (writer default 1 MB), so the writer falls back to plain encoding and level 4, which drops rows by dictionary entry, has nothing to work with. The flush fires anyway because its compile-time check is only "level >= 4" |
| F9 | N6 Baleen off re-check 264.7 s, 5 % above the morning's slowest run. | Not explained |
| F10 | The mistaken first draw (I5, flush on, 9.9 s) matches the later 6 flush-on runs (9.8-10.5). | Consistent |

## What follows

- **No HLD change.** The cause found in the morning (rows waiting in the scan's frame) is confirmed, and the
  fix closes the gap.
- **LLD, proposed (not done):** the flush is decided once per scan at compile time ("does this filter drop
  rows"). F5 and F8 show it also fires when the drop keeps most rows (H2) or drops nothing (H4, H5). Two ways
  to bound it, for the user to choose:
  1. send early only if the scan kept fewer rows since the last send than some fraction of a frame (cheap,
     adds a threshold);
  2. leave as is: the measured worst case is +9 % on one query, against 4x-8x gains on the MAX/MIN queries.
  My recommendation: 2 for now, and revisit if a second query shows the H2 pattern.
- **F8 is about level 4, not the flush:** on Iceberg, level 4 cannot drop rows from a column that is not
  dictionary-encoded, which is the normal case for a high-cardinality column. The flush cost there is not
  measurable (26 sends per run), so no change is proposed for it.

## Bugs and problems on the way

- The first random re-check ran with the new default (flush on) instead of the morning's setting. Kept in
  `recheck_1/`, reported above as a flush-on run; re-run as `recheck_1b/` with ``scanflush "false"``.
- Earlier note claimed a 32 KB frame size from memory; the code default is 1 KB and the setup overrides it.
  Corrected in the LLD amendment.

## Decisions made without asking

- One warm-up per H query (flush on) instead of one per configuration.
- H queries chosen by me, from the reasoning in section 3.
- The morning's Baleen off and row-test-off cells were not re-run (user's instruction); only two random cells.
- Separate setting `compiler.plaque.scanflush`, not a letter in `compiler.plaque.distinct.flush`; Iceberg flushes
  per file task, not per row group (both recorded in the LLD amendment).

## Stated but not verified

- That H2's extra 2 s are the small frames through the hash exchange (F5).
- Why N6 Baleen off was 5 % slower than in the morning (F9).
- The worst case named in the morning (a nullable column, where the row test keeps nulls) was not run: it
  needs a data load.
