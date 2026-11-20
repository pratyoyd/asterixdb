# PLAQUE DISTINCT filter: level 4, rows dropped inside the Parquet reader (2026-11-20)

Scope: DISTINCT with the observer and filter on the probe side only.

## What level 4 is

Level 3 can skip a row group only when every entry of its dictionary is already in the answer. A row group with
even one unseen entry is read in full: every row is decoded, turned into an AsterixDB row and then dropped by the
tuple filter (or, without a join, by the observer).

Level 4 keeps everything of level 3 and adds: inside a row group that has to be read, a row whose value is
already in the answer is dropped by its dictionary entry, inside the Parquet reader. Its string is not decoded
and it never becomes an AsterixDB row.

## Result in short

All 72 measured runs and the 2 warm-up runs return the Baleen-off result (md5 `86ca9c0b89`, 7 rows, recorded
2026-11-18). Median of 6 runs, seconds:

| Query | hash L3 | hash L4 | flat L3 | flat L4 | roaring L3 | roaring L4 |
|---|---|---|---|---|---|---|
| Q1 `l_shipmode`, join | 12.9 | 9.7 | 12.7 | 9.6 | 13.1 | 9.4 |
| Q4 `l_shipmode`, no join | 2.8 | 0.4 | 2.7 | 0.4 | 2.9 | 0.4 |

Speedup over Baleen off, using the Baleen-off medians of 2026-11-18 (Q1 203.8 s, Q4 402.5 s; not rerun today):

| Query | hash L3 | hash L4 | flat L3 | flat L4 | roaring L3 | roaring L4 |
|---|---|---|---|---|---|---|
| Q1 | 15.85x | 21.06x | 16.08x | 21.19x | 15.61x | 21.65x |
| Q4 | 143.78x | 922.26x | 146.90x | 902.65x | 137.63x | 919.42x |

Rows, roaring, median run:

| Query | Level | Rows dropped in reader | Rows leaving the scan |
|---|---|---|---|
| Q1 | 3 | 0 | 8,000,000 |
| Q1 | 4 | 7,995,886 | 4,114 |
| Q4 | 3 | 0 | 8,000,000 |
| Q4 | 4 | 7,987,653 | 12,347 |

## Findings

- Level 4 takes Q1 from 13.1 s to 9.4 s and Q4 from 2.9 s to 0.4 s (roaring). Against Baleen off of 2026-11-18
  that is 21.65x on Q1 and 919x on Q4.
- The 8,000,000 rows that level 3 still read (the first file of each of the 4 partitions, opened while the
  seen-set was empty) are now almost all dropped in the reader: 4,114 leave the scan on Q1, 12,347 on Q4.
- The three seen-sets give the same times, as expected for a string column: all three keep strings in a hash set.
- Files skipped and files read with the row-group filter are the same at both levels (0 / 30 and 26).
- The estimate given before building it, that the gain on these two queries would be small, was wrong.

## How it works

- `PlaqueDistinctDictionaryReader` (new, `asterix-external-data`, package `...input.record.reader.iceberg`) reads
  the DISTINCT column itself and lets Iceberg's reader read the other columns.
- Parquet hands out the same `Binary` object for every row that points at the same dictionary entry. The reader
  keeps, per row group, one record per such object: the decoded string and whether it is seen. This stands in for
  the numeric dictionary code, which Iceberg's row reader does not expose.
- The seen-set is asked once per dictionary entry. While the answer is "not seen" it is asked again after 1,024
  more rows, so a value that enters the answer while the row group is being read starts being dropped soon after.
- A dropped row's other columns are still read, because Iceberg's column readers are streams and cannot skip a
  value. On Q1 that is `l_orderkey`.
- Switched on with `compiler.plaque.distinct.level` = 4. The default is still 3. No rewrite rule changed.
- `IcebergFileRecordReader.openDistinctRead` is the standard read with this reader plugged in; where the reader
  declines it is the standard read.

## Setup

- Server: AsterixHyracksIntegrationUtil, 4 NCs, on sensoria; log `build_logs/asterix_distinct_level4.log`.
- Dataverse `baleen_ice`, table `lineitem` (Iceberg, 30 files), `orders`.
- Script `build_logs/bench_distinct_level4.py`; outputs `build_logs/bench_result_distinct_level4.md` and
  `build_logs/bench_result_distinct_level4_per_run.md`.
- Configurations per query: {hash, flat, roaring} x level {3, 4}; flush (`fj`) and propagation (on) at their
  defaults. No Baleen off, as instructed.
- 6 runs per configuration, round-robin within a query; one discarded warm-up per query (roaring, level 3).
  2 queries x 6 configurations x 6 runs = 72 runs.
- Counters in the summary table are those of the upper-median run of the configuration.

Queries:

```sql
-- Q1
SELECT DISTINCT l.l_shipmode FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
-- Q4
SELECT DISTINCT l.l_shipmode FROM lineitem l;
```

Settings sent before each query:

```sql
SET `compiler.plaque.enabled` "true"; SET `compiler.plaque.distinct.set` "<hash|flat|roaring>"; SET `compiler.plaque.distinct.level` "<3|4>";
```

## Built-in limits

- Level 4 acts on top-level string columns only. BIGINT columns (Q2, Q3) are read as at level 3.
- A file with delete files, and a file read through the variant-pruned path, is read as at level 3.
- A row group with more than 4,096 distinct value objects, or whose values are not dictionary entries, is read
  as usual from that point on.
- Re-check interval for an unseen entry: 1,024 rows.
- Nulls are never dropped.
- Limits of the lower levels are unchanged (list for the Parquet reader at most 10,000 values, and so on).

## Tests

`asterix-external-data/src/test/java/.../iceberg/PlaqueDistinctDictionaryReaderTest.java`, 10 tests, all pass
(run with JUnit directly). They write a Parquet file of 20,000 rows and read it through `openDistinctRead`:
empty seen-set drops nothing; exactly the rows with a seen value are dropped and the others come back unchanged
in every column; all values seen leaves only the null rows; projection order kept; only the DISTINCT column
projected; a value seen while the file is read is dropped within the re-check interval; nothing dropped below
level 4; a required string column is filtered too; a long column is read as usual; a file written without a
dictionary gives the right rows. The 8 scan-filter tests and the 10 MAX/MIN scan-filter tests still pass.

## Bugs

- First build of level 4 dropped nothing on the server: the reader accepted only optional columns and
  `l_shipmode` is required in `lineitem`. Results were correct, level 4 was simply inactive. Fixed by reading the
  column through a copy of the projection in which it is optional; a test for a required column was added.

## Summary table (as written by the script)

| Query | Variant | Runs (s) | Median (s) | Speedup over Baleen off of 2026-11-18 | Result = off | Files skipped / total | Files read with row-group filter | Rows dropped in reader | Rows into tuple filter | Dropped by tuple filter | Rows into observer | Dropped by observer | Deltas sent | Deltas merged | Values merged |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Q1 l_shipmode, join | hash, level 3 | 13.6 12.3 12.6 13.3 12.8 12.9 | 12.9 | 15.85x | yes | 0 / 30 | 26 | 0 | 8000000 | 7997601 | 1146 | 1118 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 4 | 10.0 9.9 9.8 9.6 9.1 9.2 | 9.7 | 21.06x | yes | 0 / 30 | 26 | 7993826 | 6174 | 2573 | 1728 | 1700 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 13.4 12.6 13.2 12.4 12.8 12.0 | 12.7 | 16.08x | yes | 0 / 30 | 26 | 0 | 8000000 | 7998799 | 572 | 544 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 4 | 9.7 9.5 10.2 9.6 9.9 8.6 | 9.6 | 21.19x | yes | 0 / 30 | 26 | 7995882 | 4118 | 2918 | 538 | 510 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 13.3 13.2 12.2 13.0 12.1 13.1 | 13.1 | 15.61x | yes | 0 / 30 | 26 | 0 | 8000000 | 7998669 | 651 | 623 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 10.5 9.4 8.7 9.4 9.5 9.3 | 9.4 | 21.65x | yes | 0 / 30 | 26 | 7995886 | 4114 | 1711 | 1154 | 1126 | 4 | 0 | 0 |
| Q4 l_shipmode, no join | hash, level 3 | 2.8 2.8 2.8 3.1 2.6 2.9 | 2.8 | 143.78x | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 0.5 0.4 0.4 0.4 0.4 0.4 | 0.4 | 922.26x | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 2 | 14 |
| Q4 l_shipmode, no join | flat, level 3 | 2.9 2.3 2.6 3.0 2.9 2.6 | 2.7 | 146.90x | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 0.5 0.5 0.4 0.4 0.4 0.5 | 0.4 | 902.65x | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 2 | 14 |
| Q4 l_shipmode, no join | roaring, level 3 | 2.6 3.0 3.0 2.9 2.6 3.2 | 2.9 | 137.63x | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 0.5 0.4 0.4 0.4 0.4 0.4 | 0.4 | 919.42x | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 3 | 21 |

## Every run

| Query | Variant | Run | Time (s) | Result = off | Files skipped / total | Files read with row-group filter | Rows dropped in reader | Rows into tuple filter | Dropped by tuple filter | Rows into observer | Dropped by observer | Deltas sent | Deltas merged | Values merged |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Q1 l_shipmode, join | warm-up (roaring, level 3), discarded | 0 | 13.5 | | 0 / 30 | 26 | 0 | 8000000 | 7997597 | 1154 | 1126 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 3 | 1 | 13.6 | yes | 0 / 30 | 26 | 0 | 8000000 | 7998798 | 582 | 554 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 4 | 1 | 10.0 | yes | 0 / 30 | 26 | 7991764 | 8236 | 3435 | 2266 | 2238 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 1 | 13.4 | yes | 0 / 30 | 26 | 0 | 8000000 | 7995199 | 2266 | 2238 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 4 | 1 | 9.7 | yes | 0 / 30 | 26 | 7995882 | 4118 | 2918 | 538 | 510 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 1 | 13.3 | yes | 0 / 30 | 26 | 0 | 8000000 | 7997601 | 1146 | 1118 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 1 | 10.5 | yes | 0 / 30 | 26 | 7991764 | 8236 | 3435 | 2266 | 2238 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 3 | 2 | 12.3 | yes | 0 / 30 | 26 | 0 | 8000000 | 7996400 | 1694 | 1666 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 4 | 2 | 9.9 | yes | 0 / 30 | 26 | 7991764 | 8236 | 3435 | 2266 | 2238 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 2 | 12.6 | yes | 0 / 30 | 26 | 0 | 8000000 | 7998798 | 582 | 554 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 4 | 2 | 9.5 | yes | 0 / 30 | 26 | 7993829 | 6171 | 3768 | 1154 | 1126 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 2 | 13.2 | yes | 0 / 30 | 26 | 0 | 8000000 | 7998799 | 572 | 544 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 2 | 9.4 | yes | 0 / 30 | 26 | 7994856 | 5144 | 3942 | 582 | 554 | 4 | 2 | 14 |
| Q1 l_shipmode, join | hash, level 3 | 3 | 12.6 | yes | 0 / 30 | 26 | 0 | 8000000 | 7998798 | 582 | 554 | 4 | 1 | 7 |
| Q1 l_shipmode, join | hash, level 4 | 3 | 9.8 | yes | 0 / 30 | 26 | 7993826 | 6174 | 2573 | 1728 | 1700 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 3 | 13.2 | yes | 0 / 30 | 26 | 0 | 8000000 | 7997599 | 1110 | 1082 | 4 | 2 | 14 |
| Q1 l_shipmode, join | flat, level 4 | 3 | 10.2 | yes | 0 / 30 | 26 | 7996915 | 3085 | 1883 | 582 | 554 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 3 | 12.2 | yes | 0 / 30 | 26 | 0 | 8000000 | 7997067 | 1370 | 1342 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 3 | 8.7 | yes | 0 / 30 | 26 | 7995882 | 4118 | 2918 | 538 | 510 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 3 | 4 | 13.3 | yes | 0 / 30 | 26 | 0 | 8000000 | 7997601 | 1146 | 1118 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 4 | 4 | 9.6 | yes | 0 / 30 | 26 | 7990735 | 9265 | 5837 | 1617 | 1589 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 4 | 12.4 | yes | 0 / 30 | 26 | 0 | 8000000 | 7997600 | 1156 | 1128 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 4 | 4 | 9.6 | yes | 0 / 30 | 26 | 7994853 | 5147 | 2745 | 1120 | 1092 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 4 | 13.0 | yes | 0 / 30 | 26 | 0 | 8000000 | 7995199 | 2266 | 2238 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 4 | 9.4 | yes | 0 / 30 | 26 | 7995886 | 4114 | 1711 | 1154 | 1126 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 3 | 5 | 12.8 | yes | 0 / 30 | 26 | 0 | 8000000 | 7998800 | 538 | 510 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 4 | 5 | 9.1 | yes | 0 / 30 | 26 | 7991764 | 8236 | 4636 | 1694 | 1666 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 5 | 12.8 | yes | 0 / 30 | 26 | 0 | 8000000 | 7998799 | 572 | 544 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 4 | 5 | 9.9 | yes | 0 / 30 | 26 | 7992793 | 7207 | 3607 | 1694 | 1666 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 5 | 12.1 | yes | 0 / 30 | 26 | 0 | 8000000 | 7998800 | 538 | 510 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 5 | 9.5 | yes | 0 / 30 | 26 | 7992794 | 7206 | 4807 | 1146 | 1118 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 3 | 6 | 12.9 | yes | 0 / 30 | 26 | 0 | 8000000 | 7997601 | 1146 | 1118 | 4 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 4 | 6 | 9.2 | yes | 0 / 30 | 26 | 7992791 | 7209 | 3609 | 1694 | 1666 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 3 | 6 | 12.0 | yes | 0 / 30 | 26 | 0 | 8000000 | 7997598 | 1120 | 1092 | 4 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 4 | 6 | 8.6 | yes | 0 / 30 | 26 | 7995882 | 4118 | 2918 | 538 | 510 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 3 | 6 | 13.1 | yes | 0 / 30 | 26 | 0 | 8000000 | 7998669 | 651 | 623 | 4 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 4 | 6 | 9.3 | yes | 0 / 30 | 26 | 7997940 | 2060 | 862 | 574 | 546 | 4 | 0 | 0 |
| Q4 l_shipmode, no join | warm-up (roaring, level 3), discarded | 0 | 3.0 | | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 3 | 1 | 2.8 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 1 | 0.5 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 3 | 1 | 2.9 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 1 | 0.5 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 3 | 1 | 2.6 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 1 | 0.5 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 3 | 2 | 2.8 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 2 | 0.4 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 2 | 14 |
| Q4 l_shipmode, no join | flat, level 3 | 2 | 2.3 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 2 | 0.5 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 2 | 14 |
| Q4 l_shipmode, no join | roaring, level 3 | 2 | 3.0 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 2 | 0.4 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 2 | 14 |
| Q4 l_shipmode, no join | hash, level 3 | 3 | 2.8 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 3 | 0.4 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 2 | 14 |
| Q4 l_shipmode, no join | flat, level 3 | 3 | 2.6 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 3 | 0.4 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 3 | 3 | 3.0 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 3 | 0.4 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 3 | 4 | 3.1 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 4 | 0.4 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 2 | 14 |
| Q4 l_shipmode, no join | flat, level 3 | 4 | 3.0 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 4 | 0.4 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 3 | 4 | 2.9 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 4 | 0.4 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 2 | 14 |
| Q4 l_shipmode, no join | hash, level 3 | 5 | 2.6 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 5 | 0.4 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 2 | 14 |
| Q4 l_shipmode, no join | flat, level 3 | 5 | 2.9 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 5 | 0.4 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 2 | 14 |
| Q4 l_shipmode, no join | roaring, level 3 | 5 | 2.6 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 5 | 0.4 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 3 | 6 | 2.9 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | hash, level 4 | 6 | 0.4 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 3 | 6 | 2.6 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | flat, level 4 | 6 | 0.5 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 3 | 6 | 3.2 | yes | 0 / 30 | 26 | 0 | 0 | 0 | 8000000 | 7999972 | 4 | 3 | 21 |
| Q4 l_shipmode, no join | roaring, level 4 | 6 | 0.4 | yes | 0 / 30 | 26 | 7987653 | 0 | 0 | 12347 | 12319 | 4 | 3 | 21 |

## Decisions made without asking

- The identity of Parquet's dictionary entry object is used in place of the numeric code.
- Re-check interval 1,024 rows and cut-off 4,096 entries.
- Speedups over Baleen off use the medians of 2026-11-18.
- The server was restarted twice to load the build.

## Stated but not verified

- Level 4 was not run on Q2 and Q3; that they are unaffected follows from the code (string columns only).
- The paths with delete files and with the variant-pruned read were not exercised with level 4.
- That `l_shipmode` being a required column was the cause of the first build's inactivity: the fix made it work,
  the table schema itself was not inspected.
- Which part of the saving comes from not decoding and which from not building AsterixDB rows was not measured.
- Not checked that the new tests fail on deliberately broken code.
