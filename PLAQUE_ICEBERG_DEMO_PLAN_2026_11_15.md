# Baleen filters on Iceberg tables: what it takes to demonstrate them

**Date:** 2026-11-15
**Tree:** `/scratch/asterixdb_eightynode/asterixdb_v3`, branch `baleen` at `046a78a586`
**Status:** research only. No code was changed.

## Short answer

- **Tuple-level Baleen filters already work on external scans with no code change.** Verified today on a `localfs` external dataset: the plan contains `PLAQUE_FILTER` / `PLAQUE_AGGREGATE` (scalar MAX) and `PLAQUE_GROUP_FILTER` / `PLAQUE_GROUP_AGGREGATE` (GROUP BY), and results match with Baleen on and off. An Iceberg table is the same kind of scan (`DATASOURCE_SCAN` over an external dataset), so the same should hold there. That is not yet verified on an actual Iceberg table.
- **Data skipping does not carry over.** The Baleen page filter only attaches to internal columnar datasets. On Iceberg the scan would read every file and every row group, and the filter would only drop tuples after they have been decoded. On the row store that pattern gave 1.02x for single-table MAX, so a convincing Iceberg demo needs a new, small piece: skipping Iceberg data files and Parquet row groups with the learned threshold.
- **There is no Iceberg environment on sensoria yet**, and Docker is not usable from this account, so the upstream Iceberg test harness cannot run as-is.

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
| `PlaqueRewriteRule` (tuple filters: scalar MIN/MAX, GROUP BY, theta join, cross-table expression, Top-K) | A `SELECT` above any `DATASOURCESCAN` | Works. Verified on an external `localfs` scan; not yet on Iceberg. |
| `PlaquePageFilterRule` (zone-map page filter, physical page skip) | Only scans whose projection info is `ColumnDatasetProjectionFiltrationInfo` | Silently does nothing. External scans carry `ExternalDatasetProjectionFiltrationInfo`. |
| Threshold state (`PlaqueThresholdRegistry`, keyed by job id and handle, with cross-NC broadcast) | Any operator with a task context | Reusable. The Iceberg reader is created with `IExternalDataRuntimeContext.getTaskContext()`, so it can look up the same state. |

## Steps

### 1. Stand up an Iceberg environment on sensoria

Upstream's `IcebergTest` uses Docker containers (Adobe S3Mock and Nessie `0.107.5`). `docker ps` fails with permission denied for this account, so either:

- **Without Docker (recommended):** run an S3-compatible store as a plain binary (for example MinIO) and Nessie as its standalone Java runner, both as user processes. Then use `SOURCE NESSIE` with an `s3://` warehouse, `serviceEndpoint` pointing at the local store and `pathStyleAddressing=true`, exactly as the test DDL does.
- **With Docker:** ask the sensoria admins for Docker access and reuse the test's two containers.

Neither has been tried yet. This is the first thing to verify, because nothing else can be measured without it.

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

- The Iceberg read path has never been run on the merged build; it only compiles.
- Running Nessie and an S3 store without Docker on sensoria is untested.
- Parquet decode into ADM records probably dominates Iceberg scan time. If so, tuple filters alone will show little, and the demo depends on step 4.
- On an unsorted table with large files, file-level bounds will be too wide to skip anything; row-group skipping and the clustered layout are what make the result visible. This should be stated in the demo, as the columnar results already do.

## Evidence gathered today

- External-scan check on the running server: `SELECT MAX(e.v) FROM ext e` and `SELECT MAX(e.v), e.g FROM ext e GROUP BY e.g` over a 200,000-row `localfs` JSON dataset. Baleen operators present in both plans; results identical with Baleen on and off. The test dataverse was dropped afterwards.
- Code read: `IcebergParquetRecordReaderFactory`, `IcebergFileRecordReader`, `IcebergTableFilterPushdownProcessor`, `IcebergTableFilterBuilder`, `IcebergUtils`, `CatalogConfig`, `IcebergTest`, `PlaqueRewriteRule`, `PlaquePageFilterRule`, `PlaqueColumnFilterEvaluatorFactory`, `PlaqueThresholdRegistry`.
