# Baleen on Iceberg: tuple filters and runtime data skipping

**Date:** 2026-11-15
**Tree:** `/scratch/asterixdb_eightynode/asterixdb_v3`, branch `baleen`, base commit `046a78a586` plus uncommitted changes described below
**Data:** TPC-H SF10 in Iceberg (Nessie catalog, S3Mock object store), 4 NCs in one JVM
**Background and setup:** `2026_11_15_PLAQUE_ICEBERG_DEMO_PLAN.md` (research, environment, data load)

## Outcome

Baleen now speeds up queries on Iceberg tables in two ways, and every query returned identical results with Baleen on and off.

1. **Tuple filters** worked on Iceberg with no code change. They give 1.7x on GROUP BY MAX and 1.1x to 1.3x on joins, and nothing on a plain single-table MAX.
2. **Runtime data skipping** is new code written today. A scan skips whole Iceberg data files, and Parquet row groups inside the files it does read, using the threshold the query learns while it runs. Where the data is ordered on the aggregated column it gives **6x to 7x** on single-table MAX/MIN and **4.1x** on a join.

| Query | Table layout | Baleen off (s) | Tuple filters only (s) | With data skipping (s) | Speedup |
|---|---|---|---|---|---|
| MAX(l_suppkey) | sorted on l_suppkey | 17.0 | 18.2 | 2.7 | **6.26x** |
| MIN(l_suppkey) | sorted on l_suppkey | 17.5 | not run | 2.4 | **7.18x** |
| MAX(l_orderkey) | load order (ascending l_orderkey) | 18.8 | not run | 2.6 | **7.24x** |
| Join MAX(l_suppkey), lineitem x orders | sorted on l_suppkey | 44.9 | not run | 11.0 | **4.09x** |
| MAX(l_suppkey) | load order (l_suppkey scattered) | 18.5 | 18.0 | 19.1 | 0.97x |
| Join MAX(l_suppkey), lineitem x orders | load order | 35.1 | 33.4 | 33.0 | 1.06x |
| GROUP BY MAX(l_discount) BY l_shipmode | load order | 55.0 | 33.8 | 32.2 | **1.71x** |
| Cross-table MAX(l_extendedprice - ps_supplycost) | load order | 57.3 | 45.1 | 45.4 | 1.26x |

Medians of three alternating off/on runs after one warm-up. "Tuple filters only" is the run on the unchanged build earlier the same day (its own baseline was within 2s of this one, except GROUP BY at 59.4s). "Speedup" is Baleen off divided by the data-skipping build.

## Part 1: tuple filters, no code change

`PlaqueRewriteRule` inserts its filter above any scan, so the plans on Iceberg collections already contain `PLAQUE_FILTER` / `PLAQUE_AGGREGATE` and, for GROUP BY, `PLAQUE_GROUP_FILTER` / `PLAQUE_GROUP_AGGREGATE`.

| Query | Baleen off (s) | Baleen on (s) | Speedup on Iceberg | Speedup on internal columnar (2026-11-13) |
|---|---|---|---|---|
| MAX(l_suppkey), `lineitem` | 18.9 | 18.0 | 1.05x | 11.52x |
| MAX(l_suppkey), `lineitem_by_suppkey` | 17.5 | 18.2 | 0.96x | 11.52x |
| Join MAX(l_suppkey), `lineitem` x `orders` | 36.8 | 33.4 | 1.10x | 4.12x |
| GROUP BY MAX(l_discount) BY l_shipmode | 59.4 | 33.8 | 1.76x | 1.44x |
| Cross-table MAX(l_extendedprice - ps_supplycost) | 57.3 | 45.1 | 1.27x | 6.84x |

- **Single-table MAX: no gain.** The scan is the whole cost and the tuple filter sits above it, so every file is still read and decoded.
- **Join and cross-table MAX: small gains** from fewer tuples reaching the join.
- The internal-table speedups in the last column include page skipping. I did not run a tuple-filter-only configuration on internal tables, so how much of each number is page skipping is my inference, not a measurement.

### The GROUP BY result

This is the strongest result of the no-code-change run, and it is worth reading closely.

To see where the time goes, I measured a query that only reads the same two columns and does no grouping: `SELECT COUNT(l_discount), COUNT(l_shipmode) FROM lineitem` takes **27.5s**. That is the floor for any plan that reads every row of those two columns.

| | Time (s) | Of which scan floor | Work above the scan |
|---|---|---|---|
| GROUP BY, Baleen off | 55.0 | 27.5 | 27.5 |
| GROUP BY, Baleen on | 32.2 | 27.5 | 4.7 |

- **Baleen removes 83% of the work above the scan** (27.5s down to 4.7s). With Baleen on, the query costs 1.17 times a bare scan of its two columns; without it, 2.0 times.
- **It reaches most of what is possible without skipping data.** The best any filter above the scan could do is 55.0 / 27.5 = 2.0x. Baleen gets 1.71x.
- **It needs no ordering in the data.** `l_discount` has 11 distinct values spread evenly through every file, so no file or row-group statistic can ever exclude anything for this query. Static pruning and data skipping are both useless here; the per-group thresholds still work because they drop tuples before the hash aggregation and the exchange.
- **It needed no Iceberg-specific code.** The same operators that run on internal tables ran unchanged on an external open-format table.
- **Baleen on Iceberg is faster than the internal columnar table without Baleen.** 32.2s on Iceberg with Baleen, against 54.5s (off) and 37.8s (on) for the internal columnar table measured on 2026-11-13. Those are runs from a different day, so treat the comparison as indicative.

### Why: profiler breakdown

The query was run with `profile=timings`, Baleen off and on (saved as `/scratch/asterixdb_eightynode/build_logs/profiles/groupby_ice_off.json` and `groupby_ice_on.json`). Per partition, four in parallel:

| Operator | Baleen off | Baleen on |
|---|---|---|
| Iceberg scan | 15-18s | 15-18s |
| Assign (extract `l_discount`, `l_shipmode`) | 5.5-6.3s | 5.5-6.5s |
| `PLAQUE_GROUP_FILTER` | not in plan | 2.9-3.5s |
| Sort (run generation) inside `SORT_GROUP_BY` | about 26-28s | about 2.5s |
| Query total | 53.1s | 30.9s |

- The group-by is sort-based. Without Baleen each partition sorts its 14 to 16 million tuples by `l_shipmode` to produce 7 groups, and that sort is half the query.
- The filter passes 5,600,488 of 59,986,052 tuples (9.3%). 5,452,061 rows have `l_discount = 0.10`, so nearly everything that passes is a tie with the group maximum.
- The sort then handles a tenth of the input and drops from about 27s to 2.5s per partition. The filter itself costs about 3s. Scan and assign are unchanged.

Consequences:

- The size of the gain depends on the baseline using a sort-based group-by. With a hash group-by the baseline would likely be cheaper and the gain smaller. I tried to measure that with the `/*+ hash */` hint, but the plan stayed `SORT_GROUP_BY` (53.6s off, 31.0s on), so this is untested.
- Ties with the maximum still pass the filter and cannot change the answer. Dropping them (a strict comparison once a group holds its maximum) would remove most of the remaining sort. Not tried.
- The bare scan of these two columns takes 33-34s on the internal columnar table against 27.5s on Iceberg, which is why Iceberg with Baleen (32s) beats internal columnar with Baleen (38s). Why the internal scan is slower is not investigated.

## Part 2: runtime data skipping (new code)

### How it works

1. **Compile time.** `PlaquePageFilterRule` finds the scan that produces the aggregated column. If that scan is an external collection and the column is a top-level field (`scanVar.getField("name")`), it records the threshold handle, the MAX/MIN direction and the field name on the scan's projection info. These travel to the reader as three configuration entries (`plaqueScanHandle`, `plaqueScanIsMax`, `plaqueScanField`).
2. **Scan planning on the CC.** `IcebergParquetRecordReaderFactory.configure` asks Iceberg to keep manifest statistics for that one column (`scan.includeColumnStats`), so each file task carries its lower and upper bound to the node that reads it.
3. **Reader start.** Each partition orders its tasks so the most promising file comes first: highest upper bound for MAX, lowest lower bound for MIN.
4. **Before each file.** The reader takes the current threshold from `PlaqueThresholdRegistry`:
   - **File skip.** If the file's bound cannot reach the threshold, the file is never opened.
   - **Row-group skip.** Otherwise `column >= threshold` (or `<=` for MIN) is ANDed into the Parquet filter the reader already passes, and Iceberg's Parquet reader drops row groups by their footer statistics.

Rows equal to the threshold are kept, as the tuple filter above the scan keeps them.

### Why task ordering is required, not optional

With files in ascending order, each file a partition reads raises the threshold only to that file's own maximum, which never excludes the next, higher file. Nothing would be skipped. Reading the highest file first sets the threshold to the global maximum at once, and every later file is skipped.

### What the counters show

Each reader logs one line at close (`plaque iceberg scan: N tasks, S skipped by file bound, F read with a row-group filter`). Over the benchmark:

| Case | Per partition | Whole scan |
|---|---|---|
| Ordered on the column (`lineitem_by_suppkey`, or `l_orderkey` on `lineitem`) | 1 file read, 6 or 7 skipped | 4 of 30 files read, 26 skipped |
| Not ordered (`l_suppkey` on `lineitem`) | 0 skipped, 6 or 7 read with a row-group filter | 30 of 30 files read |

Each of the four partitions has to read one file before it can skip, because all four start at the same time. That is why the time drops by about 7x and not 30x.

On the unordered layout every file and every row group contains the maximum `l_suppkey`, so neither level can exclude anything.

> **Update 2026-11-16 (ties dropped).** The paragraph above and the "Not ordered" row describe the first version, which kept ties (`>=`). The filters now use a strict comparison (`>` for MAX, `<` for MIN), so a file whose bound only equals the threshold is skipped. On unordered `lineitem` every file's upper bound for `l_suppkey` is 100000; once one reader has seen 100000, every remaining file ties and is skipped. Measured: `MAX(l_suppkey)` on unordered `lineitem` 20.4 s off, 2.7 s on, **7.68x** (was 1.05x); the join with `orders` 34.7 s off, 11.2 s on, **3.10x** (was 1.10x). Counters: 3 or 4 of each reader's 7 or 8 files skipped by file bound, the next 3 read with a row-group filter. This works on unordered data when the extreme value appears in many files (here a low-cardinality key); an unordered column whose maximum sits in one file with other files' bounds just below it would still read most files. Full tables: `2026_11_16_PLAQUE_ICEBERG_REVIEW_TODO.md`. The row-group filter costs nothing measurable there (19.1s against 18.5s, inside the run-to-run spread).

`MAX(l_orderkey)` on the plain `lineitem` table shows the feature needs no special layout: dbgen writes lineitem in `l_orderkey` order, and data that arrives in time or key order is common in practice.

### Code changes

Seven files changed and one added, about 330 lines. Nothing is committed yet.

| File | Change |
|---|---|
| `asterix-algebra/.../rules/PlaquePageFilterRule.java` | New `attachExternalScanFilter` and `findDefiningAssign`; tried first in `attachPageFilter`, falls through to the existing columnar code |
| `asterix-runtime/.../projection/ExternalDatasetProjectionFiltrationInfo.java` | Three fields for handle, direction, field name; setter, getters, copied on copy |
| `asterix-runtime/.../projection/ParquetExternalDatasetProjectionFiltrationInfo.java` | Copy the three fields in its copy constructor |
| `asterix-external-data/.../util/ExternalDataConstants.java` | Three configuration keys under `IcebergOptions` |
| `asterix-external-data/.../util/ExternalDataUtils.java` | `setExternalDataProjectionInfo` writes the three keys when a handle is set |
| `asterix-external-data/.../iceberg/PlaqueIcebergScanFilter.java` (new) | Threshold decoding, file bound lookup, skip decision, row-group filter expression, task ordering |
| `asterix-external-data/.../iceberg/IcebergParquetRecordReaderFactory.java` | Request column statistics at planning; create the filter and order tasks per reader |
| `asterix-external-data/.../iceberg/IcebergFileRecordReader.java` | Skip or filter before opening each task; counters; `openStandardRead` takes an extra filter |

Build: `mvn -o install -pl asterix-runtime,asterix-external-data,asterix-algebra` with the usual skip flags (1.5 minutes). The server was restarted on the same storage; internal tables and Iceberg collections were intact.

### Safety rules in the code

- A file is skipped only when its bound proves no row can reach the threshold. A missing bound means the file is read normally.
- Only top-level `long` and `double` columns are handled. The threshold must arrive as the matching AsterixDB type (BIGINT or DOUBLE); otherwise nothing is skipped.
- For `double`, a file is eligible only if its manifest says it holds zero NaN values, because Iceberg bounds do not cover NaN.
- Delete files only remove rows, so a file's bound stays valid; the row-group filter is applied on the delete-aware read path as well.
- With Baleen off, or for a query without a scalar MAX/MIN threshold, none of this code runs.

## Regression check on internal tables

The planner rule that was extended also serves internal columnar tables, so the 2026-11-13 benchmark was re-run on the new build. Results match in every query and the speedups are where they were.

| Query | Baleen off (s) | Baleen on (s) | Speedup now | Speedup 2026-11-13 |
|---|---|---|---|---|
| Single-table MAX (column) | 25.1 | 2.1 | 12.05x | 11.52x |
| Single-table MAX(l_shipdate) (row) | 14.2 | 13.6 | 1.05x | 1.02x |
| Join MAX + page filter (column) | 34.1 | 9.0 | 3.79x | 4.12x |
| GROUP BY MAX(l_discount) BY l_shipmode (row) | 35.9 | 21.0 | 1.71x | 1.79x |
| GROUP BY MAX(l_discount) BY l_shipmode (column) | 52.4 | 38.1 | 1.38x | 1.44x |
| Cross-table MAX(l_extendedprice - ps_supplycost) (column) | 50.6 | 7.5 | 6.72x | 6.84x |

The join query is 1s slower with Baleen on than on 2026-11-13 (9.0s against 8.0s). I take that as run-to-run variation, since the change does not touch the columnar path, but I did not investigate it. Output: `bench_result_internal_dataskip.md`.

## Limits and what is not done

- **Cross-table skipping is possible, just not wired up for Iceberg** (note added 2026-11-16). `MAX(a - b)` across a join gives a bound on the single probe column (`a` must beat threshold + min(b)); the internal columnar path already skips pages with it (`attachCrossExprPageFilter`, 7.43x). That method only accepts an internal columnar scan, so on Iceberg the same bound is never handed to the file reader.
- **Scalar MAX/MIN only.** GROUP BY (per-group thresholds), the cross-table expression and Top-K do not skip data on Iceberg yet. The cross-table query is the largest remaining gap: 1.26x on Iceberg against 6.84x on internal columnar.
- **Internal tables are unchanged in this respect:** on Iceberg the skip is attached to whichever scan produces the aggregated column, on either side of a join. The existing columnar code follows the first join input only; I left it as it is.
- **Granularity.** The threshold is checked once per task, which here is one file of 2,000,000 rows. A threshold that improves while a file is being read takes effect at the next file.
- **Single JVM.** All four NCs run in one process, so a threshold learned by one partition is visible to the others immediately. On a real cluster it travels through the CC broadcast, and other nodes will read more before they can skip. Not measured.
- **S3Mock is local.** A skipped file saves a local read here. On a real object store each skipped file also saves network requests, so the gain should be larger; that is an expectation, not a measurement.
- **No unit test** was added for `PlaqueIcebergScanFilter`. Verification is the on/off result comparison on nine queries and the skip counters.
- **Integer (`int`) and string columns** are not handled.
- **The `NOT IN` bug** noted in the plan document is still open and unrelated.

## Files

- Benchmark script: `/scratch/asterixdb_eightynode/build_logs/bench_plaque_iceberg.py`
- Tuple filters only: `bench_result_iceberg_step3.md`, `.runs`
- With data skipping: `bench_result_iceberg_dataskip.md`, `.runs`
- Server log of the data-skipping run (0 ERROR lines): `asterix_dataskip.log`
- Build log: `dataskip_build.log`; server start command: `server_cmdline.txt`
