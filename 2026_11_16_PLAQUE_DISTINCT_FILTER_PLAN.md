# Baleen DISTINCT filter (saturation): plan for review

**Date:** 2026-11-16
**Tree:** `/scratch/asterixdb_eightynode/asterixdb_v3`, branch `baleen`
**Status:** plan only. No code is written. Section 9 lists the decisions that need your answer. Updated the same day after commits `c051072179` (WHERE fix) and `118dea67e0` (plan guard, strict ties).
**Source:** BALEEN keynote (slides 61, 71, 77, 82-88, 93, 100-109, 113, 116) and the discussion of 2026-11-16.

## 1. Summary

- We add a Baleen filter for `SELECT DISTINCT` where the DISTINCT column comes from the **probe** table of a hash join. Observer and filter are then in the same pipeline, so no helper structure is needed. The helper structure (DISTINCT column on the build table) is the next step and is out of scope here.
- The reason to drop a row is **saturation**: once a value has reached the answer, every later row with that value cannot change the answer.
- The filter is a **membership filter** (`x NOT IN seen`), not a range comparison. The state is a seen-set that only grows.
- Two things are varied in the experiments: how the seen-set is stored (4 options) and where the test is applied (4 levels).
- Two findings from the code change the design from what the slides show:
  1. DISTINCT in AsterixDB is sort-based, so the observer cannot sit at the DISTINCT operator. It sits below the sort (section 3).
  2. Iceberg's own Parquet reader already skips a row group when its dictionary is a subset of a `NOT IN` list. The dictionary test needs no custom reader (section 6).

## 2. The scenario

```sql
SELECT DISTINCT l.l_suppkey
FROM lineitem l, orders o
WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```

Plan today on the Iceberg tables (from EXPLAIN on the running server, 2026-11-16):

```
distinct        PRE_SORTED_DISTINCT_BY
  order         STABLE_SORT on l_suppkey
    exchange    HASH_PARTITION_EXCHANGE on l_suppkey
      join      HYBRID_HASH_JOIN
        probe (streamed):  scan lineitem
        build:             scan orders, select o_orderstatus
```

- **Corrected 2026-11-16 after running it.** With `FROM lineitem l, orders o` lineitem is the probe: join results appear while it is being scanned. With `FROM orders o, lineitem l` lineitem is the build, and no join result exists until its scan is over. The first version of this plan had the two orders the wrong way round; EXPLAIN alone does not show which input is streamed, the filter counters do.
- There is no local duplicate removal before the exchange. Every joined row is hash-partitioned and fully sorted before the DISTINCT sees it.

Pipelines:

- P1: scan orders, build.
- P2: scan lineitem, probe, exchange, sort.
- P3: sorted output, DISTINCT.

## 3. Where the observer and the filter sit

```
distinct
  order (blocking sort)
    exchange (hash on x)
      PLAQUE_DISTINCT_OBSERVER      <- new: records x, drops local duplicates
        join
          probe:  PLAQUE_DISTINCT_FILTER   <- new: drops rows whose x is seen
                    scan lineitem          <- new: file and row-group skipping
          build:  scan orders
```

- **Observer.** The sort is blocking, so a value reaches the DISTINCT operator only after the whole probe scan has finished. An observer there learns nothing in time. The observer is a new streaming operator directly above the join. Between it and the DISTINCT there are only the exchange and the sort, and neither drops rows, so every value the observer lets through is in the answer.
- **The observer also drops duplicates.** It already tests each value against the seen-set, so a row whose value is present is dropped there and never reaches the exchange and the sort.
- **Filter.** A tuple filter above the probe scan, plus file and row-group skipping inside the Iceberg reader, both reading the same seen-set.
- **Observe after the join, never at the scan.** A lineitem row whose order fails `o_orderstatus = 'F'` does not put its `l_suppkey` in the answer.

## 4. Safety rules

| Rule | Reason |
|---|---|
| A value enters the seen-set only at the observer. | Only there is it certain to reach the answer. |
| Any subset of the true seen-set is a valid filter. | A missing entry lets a duplicate through; the DISTINCT removes it. |
| A stale copy is safe. | The set only grows, so an old copy is a subset. |
| A structure that can report "seen" for an unseen value is forbidden. | It would drop the only row carrying that value. This rules out Bloom filters and hashed bitmaps over the seen-set. |
| A value outside the bitmap's range is treated as unseen. | Min and max only decide coverage, never correctness. |
| NULL and MISSING are never recorded and always pass. | Keeps the DISTINCT's own handling of them untouched. |
| When the size cap is reached, stop inserting and keep filtering. | Still a subset. This differs from `PlaqueGroupThresholdState`, which disables itself at its cap of 25 groups. |

## 5. How the seen-set is stored

| # | Method | Change from the previous one | Works for | Needs min/max |
|---|---|---|---|---|
| A | Exact hash set | Starting point. `ConcurrentHashMap` key set of serialized values, as `seenGroups` in `PlaqueGroupThresholdState` today. | any type | no |
| B | Flat bitmap | One bit per value in `[min, max]`; the test is one bit read. | integers, small range | yes |
| C | Roaring bitmap | Blocks of 65,536 values created on demand; each block stored as a list, bits or a run. | integers, any range | no (used to shift BIGINT into 32 bits) |
| D | Capped cache | Fixed array; slot = hash(x) mod m holds one full value; new values overwrite; no false positives. | any type, high cardinality | no |

Bitmap decisions:

- **Dead form (`NOT IN`), bit = 1 means seen.** It starts as all zeros, so a node that has received nothing is safe. Merging across nodes is OR. A lost concurrent update loses a 1, which is safe.
- **Min and max come from Iceberg.** Each data file's manifest entry has a lower and upper bound per column. `IcebergParquetRecordReaderFactory.configure` already requests them for the Baleen column (`scan.includeColumnStats`). The range of the scan is the min of the lower bounds and the max of the upper bounds.
- **When bounds are missing** (table written without column metrics), B is not used and C or A is.
- **Roaring is not thread-safe.** The state owns a private Roaring bitmap behind a lock and publishes an immutable snapshot to readers after every N inserts.
- **Library.** The server already runs with `org.roaringbitmap:RoaringBitmap:1.6.23` on its classpath (a runtime dependency of `iceberg-core` 1.12.0; the 0.9.22 in the `asterix-server` license list is stale). `asterix-runtime` gets an explicit dependency on 1.6.23.

## 6. Where the test is applied

| # | Level | What it does | How it is done on Iceberg | New reader code |
|---|---|---|---|---|
| 1 | Row | Drop the row if its value is seen. | `PLAQUE_DISTINCT_FILTER` above the scan, same pattern as `PLAQUE_GROUP_FILTER`. | none |
| 2 | File, by range | Skip the file when every value from its lower to its upper bound is seen. | Before opening each task, as `cannotBeat` does today in `IcebergFileRecordReader`. Bitmaps B and C answer this with one range query. A and D answer it only when lower = upper. | small |
| 3 | Row group, by dictionary | Skip the row group when every value in its Parquet dictionary is seen. | Pass `Expressions.notIn(column, snapshot of seen-set)` to `Parquet.read(...).filter(...)`. | none |
| 4 | Row, by dictionary code | Mark seen dictionary entries once per row group, then drop rows by code before decoding. | Needs a reader that drives `ParquetFileReader` directly, as `VariantProjectedParquetReader` does. | large |

Facts checked in `iceberg-parquet-1.12.0` (the version in `asterixdb/pom.xml`):

- `ParquetDictionaryRowGroupFilter.notIn` returns "cannot match" when all four hold: the column chunk has no non-dictionary pages, it has no nulls, the dictionary is not larger than the list, and dictionary minus list is empty.
- `ParquetMetricsRowGroupFilter.notIn` always returns "might match". Min/max statistics never skip anything for `NOT IN`, so level 2 is our own code.
- `Parquet.read().filter()` works per row group. It does not drop individual rows, so level 1 stays in the plan.

Consequences:

- Level 3 works for strings and has no gap problem (a row group holding {10, 15, 20} is skipped once those three are seen, even though 11-14 never occur).
- Level 3 needs the seen-set as a list of values. Above a size limit the list is not sent and level 3 is off for that file.
- The seen-set is read once per file, as the threshold is today. A value seen while a file is being read takes effect at the next file. Our files hold 2,000,000 rows.

## 7. What exists and what is new

Reused as is:

- `PlaqueThresholdRegistry` (job-scoped state looked up by handle).
- The three configuration keys that carry handle and field name to the Iceberg reader.
- The plan guard from the 2026-11-16 review, now committed in `118dea67e0` (only ASSIGN, SELECT, PROJECT, EXCHANGE and inner joins between observer and scan).
- The CC update/broadcast message pattern of the GROUP BY filter.

New:

| Piece | File |
|---|---|
| DISTINCT path in the rewrite rule: match a single-column DISTINCT whose column comes from the probe scan, insert observer and tuple filter, mark the scan | `PlaqueRewriteRule.java`, `PlaquePageFilterRule.java` |
| Seen-set state with implementations A to D behind one interface (`add`, `contains`, `containsRange`, `snapshot`) | new `PlaqueDistinctState.java` and one class per implementation |
| Observer and tuple-filter runtimes | new `PlaqueDistinctObserverRuntimeFactory.java`, `PlaqueDistinctFilterRuntimeFactory.java` and their physical operators |
| File skip by range and the `notIn` row-group filter | new `PlaqueIcebergDistinctScanFilter.java`; call sites in `IcebergFileRecordReader.java` |
| Seen-set deltas from NC to CC and the broadcast back | two new message classes |
| Compiler options: seen-set type, scan skipping on/off, observer duplicate removal on/off, size cap | `CompilerProperties.java`, `AlgebricksConfig.java` |

## 8. Steps and experiments

| Step | Work | Measurement |
|---|---|---|
| 0 | Done. The fix for review item 8 is in commit `c051072179` (`StreamSelectPOperator` passes the pushed projection list). Checked on the running server on 2026-11-16: `MAX(l_suppkey) ... WHERE l_quantity < 3` returns 100000 with Baleen off (27.0 s) and on (4.0 s). | Baselines below. |
| 1 | Rule, observer, tuple filter, seen-set A. | Three runs per query: Baleen off; observer only; observer plus tuple filter. |
| 2 | Seen-sets B and C. | Row-level cost of A, B and C against each other. |
| 3 | File skip by range (level 2) with B and C. | Files skipped per partition; time. |
| 4 | Row-group skip by dictionary (level 3). | Row groups skipped; time; includes the string column. |
| 5 | Propagation through the CC. | Needed for a real cluster. The 4 NCs of the test setup share one JVM and one registry, so steps 1-4 are measured without it. |
| 6 | Seen-set D and level 4. | Only after steps 1-4 show where the remaining cost is. |

The "observer only" run matters: removing duplicates before the sort is an early hash distinct and is not specific to Baleen. The filter's own gain is the difference between "observer only" and the runs with the filter and the skipping.

Test queries (all on `baleen_ice`, SF10, lineitem as probe, orders as build with `o_orderstatus = 'F'`):

| Query | DISTINCT column | Distinct values | Type | What it tests |
|---|---|---|---|---|
| Q1 | `l_shipmode` | 7 | string | Low cardinality; level 3 on a string |
| Q2 | `l_suppkey` | 100,000 | long, dense 1 to 100,000 | Bitmaps; level 2 range skip |
| Q3 | `l_partkey` | 2,000,000 | long | High cardinality; cost of the filter and the cap |
| Q4 | `l_shipmode`, no join | 7 | string | The plain case; saves only exchange and sort |

First baseline, Baleen off, build `118dea67e0`, one run each on `lineitem` (not a median). Q1 and Q2 here were run with `FROM orders o, lineitem l`, that is with lineitem as the build; the baselines with lineitem as the probe are in `2026_11_16_PLAQUE_DISTINCT_FILTER_RESULTS.md`:

| Query | Rows returned | Time (s) |
|---|---|---|
| Q1 `l_shipmode`, join | 7 | 255.7 |
| Q2 `l_suppkey`, join | 100,000 | 186.5 |
| Q4 `l_shipmode`, no join | 7 | 411.7 (run alone on an idle server) |

For comparison, the join with `MAX(l_suppkey)` on the same two tables takes about 35 s with Baleen off. The extra time in the DISTINCT plans is the exchange and the full sort of every joined row, which is the work the observer and the filter remove.

Layouts: `lineitem` (load order, values of `l_suppkey` and `l_shipmode` spread through every file) and `lineitem_by_suppkey` (sorted on `l_suppkey`).

Layout matters in the opposite way to MAX/MIN:

- **Load order is the good case.** Every file holds nearly every value, so the seen-set fills from the first file each partition reads and later files and row groups are skipped.
- **Sorted on the DISTINCT column is the bad case.** Each file holds values no earlier file held, so nothing is ever skipped. The observer's duplicate removal still applies.

Each of the 4 partitions has to read at least one file before it can skip, as in the MAX/MIN experiments.

A value that never survives the join stays unseen for the whole scan. Its rows keep passing, and it blocks the skip of every file and row group that contains it.

## 9. Decisions for review

| # | Question | My proposal |
|---|---|---|
| 1 | Observer placement and duplicate removal at the observer (section 3). | As described. |
| 2 | First target. | Iceberg only. Internal columnar tables have no dataset-level min/max and no dictionary to test against; they get level 1 only, later. |
| 3 | Column types in the first version. | BIGINT for A, B, C and level 2; string for A and level 3. Single top-level column only, as in the GROUP BY path. |
| 4 | Order of work. | Steps 0 to 4 as listed, propagation after the single-JVM numbers. |
| 5 | Size cap for the seen-set. | 1,000,000 values for A; B only when the range is at most 16,000,000 values (2 MB); the `notIn` list sent to the reader at most 10,000 values. |

## 10. Not in this plan

- Helper structure for a DISTINCT column on the build table (slides 100-109, 116).
- Stopping the scan when every possible value is seen. It needs the exact set of values that survive the join, which the probe-side case does not have.
- Ordering the files so that the seen-set fills fastest.
- Multi-column DISTINCT, `COUNT(DISTINCT ...)`, DISTINCT with `ORDER BY ... LIMIT`.
- A per-page value summary for internal columnar tables (the envelope extension).
