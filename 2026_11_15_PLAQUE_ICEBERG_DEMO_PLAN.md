# Baleen filters on Iceberg tables: what it takes to demonstrate them

**Date:** 2026-11-15
**Tree:** `/scratch/asterixdb_eightynode/asterixdb_v3`, branch `baleen` at `046a78a586`
**Status:** research, step 1 (environment), step 2 (TPC-H SF10 load), step 3 (tuple filters, no code change) and step 4 for scalar MAX/MIN (runtime data skipping, new code) all done the same day. Results and the code description for steps 3 and 4 are in `2026_11_15_PLAQUE_ICEBERG_DATA_SKIPPING.md`.

## Short answer

- **Tuple-level Baleen filters already work on external scans with no code change.** Verified today on a `localfs` external dataset: the plan contains `PLAQUE_FILTER` / `PLAQUE_AGGREGATE` (scalar MAX) and `PLAQUE_GROUP_FILTER` / `PLAQUE_GROUP_AGGREGATE` (GROUP BY), and results match with Baleen on and off. The same now holds on a real Iceberg table (see "Step 1 result" below).
- **Data skipping does not carry over.** The Baleen page filter only attaches to internal columnar datasets. On Iceberg the scan would read every file and every row group, and the filter would only drop tuples after they have been decoded. On the row store that pattern gave 1.02x for single-table MAX, so a convincing Iceberg demo needs a new, small piece: skipping Iceberg data files and Parquet row groups with the learned threshold.
- **An Iceberg environment now runs on sensoria without Docker** (S3Mock and Nessie as plain Java processes). Docker is not usable from this account, so the upstream test harness itself cannot run as-is.

So the demo has three parts: stand up an Iceberg environment, show the filters that work today, then add runtime file and row-group skipping and measure it.

## How upstream reads an Iceberg table

| Stage | Where | What happens |
|---|---|---|
| DDL | `CREATE CATALOG ... TYPE Iceberg SOURCE ...`, then `CREATE EXTERNAL COLLECTION ... USING S3 (("table-format"="iceberg"), ...)` | Catalog sources: `REST`, `NESSIE`, `NESSIE_REST`, `AWS_GLUE`, `AWS_GLUE_REST`, `S3_TABLES`, `BIGLAKE_METASTORE`. Storage adapters: S3, Azure Blob/Data Lake, GCS. There is no local-file or Hadoop catalog. |
| Filter pushdown (compile) | `IcebergTableFilterPushdownProcessor`, `IcebergTableFilterBuilder` | Static `WHERE` comparisons become an Iceberg `Expression`. |
| Scan planning (compile, on the CC) | `IcebergParquetRecordReaderFactory.configure` | `scan.filter(expr).planFiles()` prunes data files by manifest min/max, splits them, and assigns `FileScanTask`s to partitions. Column stats are dropped from the tasks unless requested. |
| Read (runtime, on NCs) | `IcebergFileRecordReader.setNextRecordsIterator` | Per task: `Parquet.read(file).project(...).filter(task.residual()).split(...)`. Iceberg's Parquet reader skips row groups using that filter. |

The key point: **all file pruning is decided at compile time**, before any tuple is read. Baleen thresholds only exist at run time. Nothing connects the two today.

## Where Baleen attaches today

| Baleen piece | Attaches to | On Iceberg |
|---|---|---|
| `PlaqueRewriteRule` (tuple filters: scalar MIN/MAX, GROUP BY, theta join, cross-table expression, Top-K) | A `SELECT` above any `DATASOURCESCAN` | Works. Verified on an external `localfs` scan and on an Iceberg table. |
| `PlaquePageFilterRule` (zone-map page filter, physical page skip) | Only scans whose projection info is `ColumnDatasetProjectionFiltrationInfo` | Silently does nothing. External scans carry `ExternalDatasetProjectionFiltrationInfo`. |
| Threshold state (`PlaqueThresholdRegistry`, keyed by job id and handle, with cross-NC broadcast) | Any operator with a task context | Reusable. The Iceberg reader is created with `IExternalDataRuntimeContext.getTaskContext()`, so it can look up the same state. |

## Steps

### 1. Stand up an Iceberg environment on sensoria

Upstream's `IcebergTest` uses Docker containers (Adobe S3Mock and Nessie `0.107.5`). `docker ps` fails with permission denied for this account, so either:

- **Without Docker (recommended):** run an S3-compatible store as a plain binary (for example MinIO) and Nessie as its standalone Java runner, both as user processes. Then use `SOURCE NESSIE` with an `s3://` warehouse, `serviceEndpoint` pointing at the local store and `pathStyleAddressing=true`, exactly as the test DDL does.
- **With Docker:** ask the sensoria admins for Docker access and reuse the test's two containers.

Done without Docker; see "Step 1 result" below.

### 2. Load TPC-H into Iceberg tables

- sensoria has no Spark, `pyiceberg` or `pyarrow`. The simplest writer is a small Java loader using the Iceberg API that is already on the AsterixDB classpath (Iceberg `1.12.0`), the same way `IcebergTest` writes its fixtures. A Python venv with `pyiceberg` is the alternative.
- Tables: `lineitem`, `orders`, `partsupp` at SF10, to match the existing benchmark.
- **Layout is an experiment variable, not a detail.** Skipping can only use per-file and per-row-group min/max, so decide and record: data file size, Parquet row-group size, and sort order. Load at least two layouts, as in `PLAQUE_CLUSTERED_LAYOUT_TABLE.md`: unsorted (worst case) and clustered on the aggregated column (best case).
- Types: TPC-H decimals and dates need the collection options `decimal-to-double` and, if wanted, `date-to-int`, so that Baleen's comparators see the same types as on the internal tables.

### 3. Baseline demo with no code change

1. `CREATE CATALOG` and `CREATE EXTERNAL COLLECTION` for each table.
2. Run the six benchmark queries with `compiler.plaque.enabled` off and on (`bench_plaque.py` works unchanged apart from dataset names).
3. Check: Baleen operators appear in the plans, results match, and record the speedups.

Expected: correct results, small gains on single-table MAX (the scan still decodes everything), larger gains where the filter saves downstream work (GROUP BY, joins, cross-table expression). This is the honest "before" number.

### 4. Add runtime data skipping for Iceberg (new code)

Scalar MIN/MAX first. Three touch points:

1. **Compile, `PlaquePageFilterRule`:** when the scan is an Iceberg table, record the threshold handle, MIN/MAX direction and column name on the external scan's projection info, as is done for columnar datasets.
2. **Planning, `IcebergParquetRecordReaderFactory.configure`:** call `scan.includeColumnStats(<that column>)` so each `FileScanTask` keeps its lower and upper bound. Upstream already does this for variant sub-fields.
3. **Runtime, `IcebergFileRecordReader.setNextRecordsIterator`:** before opening a task, fetch the `PlaqueThresholdState` from `PlaqueThresholdRegistry` by job id and handle, then:
   - **File skip:** for MAX, skip the file if its upper bound is below the threshold (lower bound above, for MIN). This is the analogue of the columnar page filter.
   - **Row-group skip:** AND `col > threshold` into the filter passed to `Parquet.read(...).filter(...)`. Iceberg's reader then prunes row groups from their footer statistics.

Things to get right:

- **Type conversion.** The threshold is ADM bytes; Iceberg bounds are Iceberg literals. With `decimal-to-double`, timezone or `date-to-int` options the engine's value is not the stored value. Upstream wrote `NumericLiteralPredicateRewriter` and `TimestampZonePredicateRewriter` for exactly this class of bug. Start with integer and double columns.
- **Safety direction.** A skip is only allowed when the bound proves no row can beat the threshold. Missing bounds, NaNs or truncated string bounds mean "read the file".
- **Deletes.** Delete files only remove rows, so a file's upper bound stays a valid upper bound and skipping remains safe.
- **Counters.** Log files and row groups skipped per query; that is the evidence for the demo.

Later, in the same pattern: GROUP BY (per-group thresholds), cross-table expression, Top-K.

Optional, Iceberg-specific: order each partition's tasks by descending upper bound for MAX, so the threshold tightens after the first file. Manifest stats make this free at planning time; internal tables have no equivalent.

### 5. Measure and write up

Same queries, SF10, medians of three runs, results compared on/off:

| Configuration | Purpose |
|---|---|
| Internal columnar, Baleen off/on | Reference (already measured: 11.5x, 4.1x, 1.44x) |
| Iceberg, Baleen off | Baseline |
| Iceberg, Baleen tuple filters only (step 3) | What exists today |
| Iceberg, Baleen with file and row-group skip (step 4) | The demo result |

Each Iceberg configuration on both layouts (unsorted, clustered).

## Risks and unknowns

- The Iceberg read path has only been run on a 200,000-row table so far; SF10 is untested.
- S3Mock is a test double, not a real object store. It is fine for correctness and for relative speedups, but absolute scan times will not reflect real S3 latency.
- Parquet decode into ADM records probably dominates Iceberg scan time. If so, tuple filters alone will show little, and the demo depends on step 4.
- On an unsorted table with large files, file-level bounds will be too wide to skip anything; row-group skipping and the clustered layout are what make the result visible. This should be stated in the demo, as the columnar results already do.

## Step 1 result (2026-11-15)

Everything lives in `/scratch/asterixdb_eightynode/iceberg/`. Start (or restart) both services with `start.sh`; it is safe to re-run.

| Piece | What | Where |
|---|---|---|
| Object store | Adobe S3Mock `4.12.4` standalone jar (the version upstream's test uses) | `http://127.0.0.1:9090`, bucket `iceberg`, data in `s3-data/` |
| Catalog | Nessie `0.107.5` runner jar, RocksDB store | `http://127.0.0.1:19120/api/v2`, data in `nessie-data/` |
| Loader | `loader/SmokeLoader.java`, run with the AsterixDB classpath saved in `loader/classpath.txt` | writes `baleen.smoke` |
| Logs | `logs/s3mock.log`, `logs/nessie.log` | |

MinIO was the first choice but its binary downloads now return HTTP 410, so S3Mock is used instead.

DDL that works:

```sql
CREATE CATALOG baleenCatalog TYPE Iceberg SOURCE NESSIE
WITH { "uri": "http://127.0.0.1:19120/api/v2", "warehouse": "s3://iceberg/warehouse" };

CREATE TYPE t AS open {};
CREATE EXTERNAL COLLECTION smoke(t) USING S3 (
  ("table-format"="iceberg"), ("namespace"="baleen"), ("tableName"="smoke"),
  ("region"="us-west-2"), ("serviceEndpoint"="http://127.0.0.1:9090"), ("pathStyleAddressing"="true"),
  ("accessKeyId"="dummyAccessKey"), ("secretAccessKey"="dummySecretKey"),
  ("catalogName"="baleenCatalog"), ("format"="parquet"));
```

Checks on `baleen_ice.smoke` (4 Parquet files of 50,000 rows; file k holds `v` in [k*250000, (k+1)*250000)):

| Check | Result |
|---|---|
| `COUNT(*)`, `MIN(v)`, `MAX(v)` | 200000, 1, 999997: the Iceberg read path works on the merged build |
| `MAX(v)` with Baleen on | `PLAQUE_FILTER` and `PLAQUE_AGGREGATE` above the Iceberg scan; same result as off |
| `MAX(v) GROUP BY g` with Baleen on | `PLAQUE_GROUP_FILTER` and `PLAQUE_GROUP_AGGREGATE` in the plan; same 7 rows as off |
| Upstream static pruning, `WHERE v >= 900000` | 1 of 4 data files planned (server log), count 20144 |
| Restart of S3Mock and Nessie | table still readable |

The last-but-one row is the behaviour step 4 wants at run time: for `MAX(v)`, once the first file with the largest values is read, the other three files could be skipped by their upper bounds.

Caveat: S3Mock's HTTP port 9090 listens on all interfaces and has no authentication (its HTTPS port and Nessie are bound to localhost). It holds only benchmark data.

## Step 2 result (2026-11-15)

TPC-H SF10 from `/scratch/tpch_data/sf10/*.tbl`, loaded with `loader/TpchLoader.java` (usage in its header comment). Column names and types (int64, double, string) match the internal `*_10` tables, so no decimal or date conversion is involved. Data files hold 2,000,000 rows each; Parquet row groups are 16MB. Total size in the object store: 3.6GB.

| Collection (`baleen_ice`) | Layout | Rows | Data files | Row groups per file | Load time |
|---|---|---|---|---|---|
| `lineitem` | dbgen order (sorted on `l_orderkey`) | 59,986,052 | 30 | 4 | 685s |
| `lineitem_by_suppkey` | sorted on `l_suppkey` | 59,986,052 | 30 | 4 | 597s |
| `orders` | dbgen order | 15,000,000 | 8 | 4 (last file 2) | 172s |
| `partsupp` | dbgen order | 8,000,000 | 4 | 5 | 105s |

Verification against the internal columnar tables: `COUNT(*)`, `MAX(l_suppkey)`, `SUM(l_orderkey)`, `MAX(l_extendedprice)`, `MIN`/`MAX(l_shipdate)` are identical for both lineitem collections; count, key sum and one aggregate are identical for `orders` and `partsupp`.

What the two layouts give, using upstream's static pruning as a probe (`WHERE l_suppkey >= 99000`, 601,192 rows):

| Collection | Data files planned | Time |
|---|---|---|
| `lineitem` | 30 of 30 | 17.8s |
| `lineitem_by_suppkey` | 1 of 30 | 1.3s |

So on the sorted table the file bounds are tight enough to skip 29 of 30 files once a threshold is known; on the unsorted table file-level skipping can do nothing for `l_suppkey`, and only tuple filters help.

First timing impression, one run each and not a benchmark: a full-scan aggregate over lineitem took 49.8s on the internal columnar table, 65.9s on `lineitem` and 60.5s on `lineitem_by_suppkey`.

Leftover: a 200,000-row trial table `baleen.ps_sample` exists in the catalog (no collection points at it).

## Step 3 result (2026-11-15): Baleen on/off on Iceberg, no code change

Tuple filters work on Iceberg scans and give correct results, but the gain is much smaller than on internal columnar tables, because every file and row group is still read and decoded.

TPC-H SF10, 4 NCs, one warm-up then three alternating off/on runs, median seconds:

| Query | Baleen off | Baleen on | Speedup on Iceberg | Speedup on internal columnar (2026-11-13) | Results match |
|---|---|---|---|---|---|
| MAX(l_suppkey), `lineitem` | 18.9 | 18.0 | 1.05x | 11.52x | yes |
| MAX(l_suppkey), `lineitem_by_suppkey` | 17.5 | 18.2 | 0.96x | 11.52x | yes |
| Join MAX(l_suppkey), `lineitem` x `orders` | 36.8 | 33.4 | 1.10x | 4.12x | yes |
| GROUP BY MAX(l_discount) BY l_shipmode, `lineitem` | 59.4 | 33.8 | 1.76x | 1.44x | yes |
| Cross-table MAX(l_extendedprice - ps_supplycost), `lineitem` x `partsupp` | 57.3 | 45.1 | 1.27x | 6.84x | yes |

Reading it:

- Single-table MAX: no gain (within run-to-run noise). The scan is the whole cost and the tuple filter sits above it. On internal columnar tables the 11.5x came from page skipping, which does not exist for Iceberg yet. The sorted layout makes no difference either, for the same reason.
- Join and cross-table MAX: small gains (1.10x, 1.27x) from fewer tuples reaching the join. The internal-table numbers (4.1x, 6.8x) again relied on page skipping.
- GROUP BY MAX: 1.76x, the strongest result of this run. A bare scan of the same two columns takes 27.5s, so Baleen cuts the work above the scan from about 27.5s to under 5s, with no ordering in the data and no Iceberg-specific code. Full reading in `2026_11_15_PLAQUE_ICEBERG_DATA_SKIPPING.md`.
- This gap is the case for step 4: the queries that lose most are exactly those where skipping files and row groups by the learned threshold would apply.

Script: `/scratch/asterixdb_eightynode/build_logs/bench_plaque_iceberg.py`; output `bench_result_iceberg.md`, per-run times `bench_result_iceberg.runs`. Server log had 0 ERROR lines after the run.

## Unrelated bug seen on the merged build

`WHERE x NOT IN ["..."]` returns the right number of rows but empty or garbled string values (seen on `Metadata.Dataset`, reproduced on `Lineitem_10`). The same filter written with `!=` is correct. The plan uses a `SUBPLAN` operator. Not investigated: unknown whether the merge caused it or it predates it. The Baleen benchmark queries do not use `SUBPLAN`.

## Evidence gathered today

- External-scan check on the running server: `SELECT MAX(e.v) FROM ext e` and `SELECT MAX(e.v), e.g FROM ext e GROUP BY e.g` over a 200,000-row `localfs` JSON dataset. Baleen operators present in both plans; results identical with Baleen on and off. The test dataverse was dropped afterwards.
- Code read: `IcebergParquetRecordReaderFactory`, `IcebergFileRecordReader`, `IcebergTableFilterPushdownProcessor`, `IcebergTableFilterBuilder`, `IcebergUtils`, `CatalogConfig`, `IcebergTest`, `PlaqueRewriteRule`, `PlaquePageFilterRule`, `PlaqueColumnFilterEvaluatorFactory`, `PlaqueThresholdRegistry`.
