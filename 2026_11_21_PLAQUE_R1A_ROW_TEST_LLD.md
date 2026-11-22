# R1a: row test inside the scan for MAX / MIN, and DISTINCT with a WHERE (LLD, 2026-11-21)

Status: written after the user accepted the HLD ("yes do D8 and LLD and everything", 2026-11-21); the user asked
for all stages to go ahead, so this LLD is not waited on separately. Nothing built yet.
HLD: `2026_11_21_PLAQUE_R1A_ROW_TEST_HLD.md`. Code tree `/scratch/asterixdb_eightynode/asterixdb_v3`, branch `baleen`.

## 1. What changes, in one table

| Module | File | Change |
|---|---|---|
| hyracks algebricks-core | `AlgebricksConfig`, `PhysicalOptimizationConfig` | New boolean `PLAQUE_ROW_FILTER`, default true |
| asterix-common | `CompilerProperties`, `OptimizationConfUtil` | New setting `compiler.plaque.rowfilter`, read into the physical config |
| asterix-algebra | `SqlppCompilationProvider` | Accept the new setting in `SET` |
| asterix-algebra | `PlaquePageFilterRule` | `handleAggregate` (scalar MAX/MIN, not cross-table): also attaches the row test when the setting is on |
| asterix-runtime | `ColumnDatasetProjectionFiltrationInfo` | New fields: row-test handle, direction, column path; copied, printed in the plan |
| asterix-runtime | `ExternalDatasetProjectionFiltrationInfo` | New fields: row-test handle, direction, field name; copied, compared, printed |
| asterix-runtime | `PlaqueThresholdState` | Two counters: rows tested and rows dropped in the scan |
| asterix-runtime | `PlaqueFilterRuntimeFactory` | Prints those counters in its existing `PLAQUE_FILTER_STATS` line |
| asterix-column | `PlaqueRowFilterEvaluatorFactory` (new) | The combined row filter: the query's WHERE, then each Baleen row test, all must pass |
| asterix-column | `IPlaqueRowTest` (new), `PlaqueMinMaxRowTest` (new), `PlaqueDistinctRowTest` (replaces `PlaqueDistinctIterableFilterEvaluatorFactory`) | The Baleen row tests that plug into it |
| asterix-column | `ColumnIterableFilterEvaluatorFactory` | One getter for its scalar evaluator factory, so the WHERE can be reused inside the combined filter |
| asterix-metadata | `IndexUtil.createTupleProjectorFactory` | Builds the combined filter whenever there is at least one Baleen row test |
| asterix-external-data | `ExternalDataConstants`, `ExternalDataUtils` | Three new config keys for the Iceberg row test, copied from the projection info |
| asterix-external-data | `PlaqueIcebergRowTest` (new) | The Iceberg row check |
| asterix-external-data | `IcebergParquetRecordReaderFactory`, `IcebergFileRecordReader` | Create the check per reader; wrap every file's rows with it; count and log |

## 2. Setting

`SET \`compiler.plaque.rowfilter\` "true" | "false";` default true. Read like `compiler.plaque.push.through.join`:
`CompilerProperties` option → `OptimizationConfUtil` → `PhysicalOptimizationConfig.getPlaqueRowFilter()`.
It switches only the MAX/MIN row test, on both formats. DISTINCT keeps `compiler.plaque.distinct.level`
(level 4 and up = row drop).

## 3. Compile time: `PlaquePageFilterRule.handleAggregate`

Today: for a scalar MAX/MIN aggregate it calls `attachPageFilter` (file skip on Iceberg, leaf skip on native).
New, after that call and independent of whether it succeeded:

```
if (rowFilter setting on) attachRowTest(aggOp, handle, isMax, filteredVar)
```

`attachRowTest` walks down from the aggregate's input to the ASSIGN that defines `filteredVar`, then from that
ASSIGN down to the scan. Allowed on the way:

| Operator | Allowed? | Why dropping a row with `x` that cannot beat the best value is still safe |
|---|---|---|
| ASSIGN, SELECT, PROJECT, EXCHANGE | Yes | Row by row; they keep or drop rows, never combine them |
| Inner join, either input | Yes | A join output row carries the same `x`; it cannot beat the best value either |
| Left outer join, input 0 (outer side) | Yes | Removing an outer row removes only output rows with that same `x` |
| Left outer join, input 1 (inner side) | Yes | Removing an inner row can only turn an outer row into one with `x` missing, which MAX/MIN ignore, or remove output rows with that same `x` |
| Anything else (GROUP BY, ORDER, LIMIT, UNNEST, REPLICATE, window, union, ...) | No: not attached | Other operators may need the row |

The ASSIGN expression must be a field access on the scan's record variable:
- `FIELD_ACCESS_BY_INDEX` (declared field): the name is taken from the dataset's record type;
- `FIELD_ACCESS_BY_NAME` (undeclared field, and every Iceberg field).
Nested access is not attached (D2: later).

The walk is new code, kept separate from `findDefiningAssign`, which DISTINCT also uses and where crossing the
inner side of a left outer join would be wrong (a null-extended row would add a null to the DISTINCT answer).
(Amended 2026-11-21 while implementing: the first version crossed only the outer side of a left outer join;
the implement skill's check showed the inner side is equally safe for MAX/MIN, so both are crossed.)

Where it lands:
- native scan (`ColumnDatasetProjectionFiltrationInfo`): `setPlaqueRowTest(handle, isMax, path)`;
- Iceberg scan (`ExternalDatasetProjectionFiltrationInfo`, Iceberg table): `setPlaqueRowTest(handle, isMax, field)`.

These fields are separate from the page / file skip fields, so the leaf skip and the file skip are unchanged and
the row test can attach where they do not (undeclared fields, strings, int and float on Iceberg).

Plan output: the scan line shows `plaque-row-test: MAX(<field>), <handle>`. Absence means not attached.

## 4. Native runtime

### 4.1 `IndexUtil.createTupleProjectorFactory`

```
where  = ColumnFilterBuilder.build()                     // as today: NoOp or ColumnIterableFilterEvaluatorFactory
tests  = []
if info has row test            -> tests += PlaqueMinMaxRowTest(handle, isMax, path)
if info has DISTINCT column and (level >= 4 or leafSkip)
                                -> tests += PlaqueDistinctRowTest(handle, set, path, dropSeen = level >= 4)
if tests not empty              -> rowFilter = PlaqueRowFilterEvaluatorFactory(where's scalar factory or null, tests)
else                            -> rowFilter = where                                   // untouched upstream path
leaf skip for DISTINCT          -> attached when rowFilter is not NoOp (as today)
```

What changes for DISTINCT: today `PlaqueDistinctIterableFilterEvaluatorFactory` was used only when `where` was
NoOp. Now its test is added to the combined filter in both cases. With a WHERE and level 2 or 3 the "keep all"
DISTINCT test is still added; it costs one call per row and keeps the code path the same (could be left out;
not worth a special case).

### 4.2 `PlaqueRowFilterEvaluatorFactory.create(context)`

1. If a WHERE factory is present, create its scalar evaluator from `context` (this registers its column readers
   in the provider, as `ColumnIterableFilterEvaluatorFactory` does).
2. For each test, `test.create(provider, taskContext)` returns a scalar evaluator that writes true (keep) or false
   (drop); it registers its own column readers.
3. If no readers were registered at all (this disk component has none of the columns) → `TrueColumnFilterEvaluator`.
4. `provider.initializeFilterReaders()`.
5. Evaluator per row: WHERE first; if not true (false, null or missing, same rule as `BinaryBooleanInspector`),
   the row is dropped and no test is asked. Otherwise each test in order; the first false drops the row.
6. If any reader is repeated → `ColumnarRepeatedIterableFilterEvaluator`, else `ColumnIterableFilterEvaluator`
   (same choice as upstream). With a repeated WHERE column the evaluator keeps a row if some value combination
   passes; because a Baleen test reads a non-repeated column it gives the same answer for every value, so the
   result is `(WHERE for some value) AND tests`.

### 4.3 `PlaqueMinMaxRowTest.create`

- Value: `provider.createColumnAccessEvaluator(path)`; readers registered for it are remembered.
- If any of its readers is repeated (the field holds arrays in this component), or the task context is null
  (datasets with a meta part), the test keeps every row and counts one decline on the state.
- State: `PlaqueThresholdRegistry.getOrCreate(jobId, handle, isMax)`, the same object the tuple filter and leaf
  skip use.
- Per row: null or missing → keep. Else `state.passes(value, comparator, strict = true)`, with its own
  `AGenericAscBinaryComparator`: exactly the tuple filter's test. False → drop and count.

### 4.4 `PlaqueDistinctRowTest`

The body of `PlaqueDistinctIterableFilterEvaluatorFactory` moved into the test interface unchanged (same
`notSeen` logic, same "component has no column → keep" rule, same counter `scanRowsDropped` on the seen-set).
The old class is removed; its unit test moves to the new class.

## 5. Iceberg runtime

- `ExternalDataUtils`: if the projection info has a row test, put `plaqueRowTestHandle`, `plaqueRowTestIsMax`,
  `plaqueRowTestField` in the scan configuration.
- `IcebergParquetRecordReaderFactory`: per reader, `PlaqueIcebergRowTest.create(config, schemaAtSnapshot, ctx)`;
  null when there is no row test or the column type is not supported; passed with `reader.setPlaqueRowTest`.
- `PlaqueIcebergRowTest.create`: column must be a top-level field of type `INTEGER`, `LONG`, `FLOAT` or
  `DOUBLE`. Otherwise returns null and logs once per reader:
  `plaque iceberg row test off: column <name> has type <type>`.
- `keep(Record r)`: value `r.getField(name)`; null → keep. Else the value is written into a reused buffer as
  the AsterixDB value the parser would produce (`IcebergParquetDataParser`: int and long → BIGINT, float →
  FLOAT, double → DOUBLE; tag byte then big-endian bits), and `state.passes(buffer, comparator, strict = true)`
  decides. So the test, the comparator and the treatment of NaN and -0.0 are the tuple filter's.
- `IcebergFileRecordReader.setNextRecordsIterator`: after the iterable of a file is chosen, on every path
  (variant-pruned read, DISTINCT level 4 read, standard read with or without deletes):
  `iterable = rowTest == null ? iterable : CloseableIterable.filter(iterable, rowTest::keep)`.
  Closing the filter closes the file read.
- `close()`: the existing `plaque iceberg scan:` line gets `, {} rows dropped by row test of {} tested`.

## 6. Fallbacks and how each is seen

| Case | Where decided | What happens | How it is seen |
|---|---|---|---|
| Setting off | Rule | No row test | Plan has no `plaque-row-test` |
| Path to the scan crosses a non-row-wise operator | Rule | Not attached; tuple filter still works | Plan has no `plaque-row-test` |
| Not a top-level field access on the scan record | Rule | Not attached | Same |
| Native: component has none of the filter's columns | `PlaqueRowFilterEvaluatorFactory` | Every row kept | Rows tested counter does not grow |
| Native: field holds arrays, or no task context | `PlaqueMinMaxRowTest` | Keeps every row | Decline counter in `PLAQUE_FILTER_STATS` |
| Iceberg: column type not int, long, float or double | `PlaqueIcebergRowTest.create` | No row test | Log line `plaque iceberg row test off` |
| No best value yet | `state.passes` | Row kept | — |

## 7. Counters

`PlaqueThresholdState` gets `LongAdder scanRowsTested, scanRowsDropped, scanRowTestDeclines` (native).
`PLAQUE_FILTER_STATS` (tuple filter, one line per partition at close) adds them. The state is shared by every
scan of the job in the process (here: one process for all four nodes), so each line reports and resets what has
built up since the previous line (`sumThenReset`); the lines of a job add up to its total. (Amended 2026-11-22
while preparing the benchmark: the first version printed running totals, which a sum over lines would
overcount.) Iceberg counts per reader and logs them in `close()`.

## 8. Tests (JUnit, run directly with `JUnitCore`; no runtimets / optimizerts harness)

| Test class | Module | Checks |
|---|---|---|
| `PlaqueRowFilterTest` (new) | asterix-column | Combining: WHERE false → dropped and no test asked; WHERE null/missing → dropped; WHERE true + test false → dropped; all true → kept; no WHERE → tests only. MIN/MAX test: no best value → keep; null and missing → keep; equal value → drop; better → keep; worse → drop, for BIGINT, DOUBLE, STRING; counters equal the drops |
| `PlaqueDistinctColumnFilterTest` (existing) | asterix-column | Adapted to `PlaqueDistinctRowTest`; same four checks |
| `PlaqueIcebergRowTestTest` (new) | asterix-external-data | Unsupported type → null; int, long, float, double: worse dropped, equal dropped, better kept, null kept; the buffer equals the bytes of AsterixDB's own serializer for the same value; a value that becomes worse after the best value improves mid-file is dropped (live value) |
| `PlaqueIcebergRowTestReadTest` (new) | asterix-external-data | A written Parquet file read through the reader's standard path with the wrapper: exactly the rows with a better value come back, all columns intact |

The rule and the reader wiring cannot be unit tested without the harness; they are checked on the server.

## 9. How each claim will be verified

| Claim | How |
|---|---|
| Row test attaches with and without a WHERE, native and Iceberg | `EXPLAIN` on the server shows `plaque-row-test` on the scan |
| Not attached through GROUP BY or similar | `EXPLAIN` of a query with a GROUP BY between aggregate and scan (no row test expected) |
| Same answers | Every Baleen-on run's result equals Baleen off (benchmark checks) |
| Rows are dropped in the scan | Counters: rows dropped in the scan > 0; rows reaching the tuple filter fall |
| DISTINCT with a WHERE now drops rows in the reader | `scanRowsDropped` in `PLAQUE_DISTINCT_OBSERVER_STATS` > 0 for a DISTINCT query with a WHERE on the same table |
| Overhead when little is dropped (D8) | Worst-case query, row test on versus off |
| Unit-level logic | Tests of section 8 |

## 10. Amendment 2026-11-22: the scan sends what it kept early (agreed with the user 2026-11-22)

**Why.** Benchmark of 2026-11-22 (`2026_11_22_PLAQUE_R1A_RESULTS.md`): with the row test on, page and file
skipping got much worse (N1: rows past page skipping 40,000 to 2-9M; I1: files skipped 26 to 1-22), and the row
test was slower than page skip alone on N1, N2, I1, I2. The scan sends its output frame only when it is full or
the scan ends; with the row test keeping about 1 row in 400, the rows that would raise the best value wait in
that frame, and every skip decision uses an old value. The tuple filter's early send does not help: the rows
have not reached it. Same problem as the DISTINCT early flush of 2026-11-17 (filter `f`, join `j`), one operator
lower: before R1a nothing dropped rows inside the scan.

**What.** When the scan's own row filter has a test that drops rows (MAX/MIN row test, DISTINCT level 4), the
scan hands on its output frame, if it holds any row, right before each skip decision:

| Format | When | Where the decision is made |
|---|---|---|
| Native | At the start of every leaf, before the page filter is asked whether to skip it (skipped leaves included) | `QueryColumnTupleReference.startNewPage` |
| Iceberg | Before each file task after the first is opened (file skip and row-group filter are decided at open) | `IcebergFileRecordReader.setNextRecordsIterator` |

At most one extra frame per leaf or file, and only when it holds a row. Iceberg does not send inside a file:
nothing inside a file reads the best value except the row test itself, which already reads it live per row.

**Plumbing.**

- Native: `ITupleProjector` (hyracks storage-common) gets `default void setEarlyFlush(HyracksThrowingAction)`,
  no-op. `IndexSearchOperatorNodePushable` passes "hand on the frame if it has a row, and count it" after
  creating its projector. `QueryColumnTupleProjector` keeps it only when its row filter is a
  `PlaqueRowFilterEvaluatorFactory` that asks for it, and hands it to each component's `QueryColumnMetadata`;
  `QueryColumnTupleReference.startNewPage` calls it first. The call happens inside the cursor's `hasNext`, when
  the scan is not in the middle of writing a row.
- Iceberg: the reader gets `setEarlyFlush(HyracksThrowingAction)`; `RecordDataFlowController` passes the tuple
  forwarder's flush, guarded by "frame not empty".
- Setting `compiler.plaque.scanflush` (default true). The rule stores it on the scan info where it attaches the
  row test or DISTINCT level 4 (native and Iceberg); `IndexUtil` passes it to `PlaqueRowFilterEvaluatorFactory`;
  `ExternalDataUtils` copies it to the reader's properties. Off: behaviour as before this amendment.
- Not for the keep-all test (row test off) or DISTINCT levels 2-3: they drop nothing in the scan, frames fill as
  before.

**Seen.** Each scan that sent early logs one line at close, `plaque scan early sends: <n> (...)`, native and
Iceberg alike; the benchmark sums them. A scan that sends nothing logs nothing. (The Iceberg reader is told by
a `Callable<Boolean>` whether rows were handed on, so it counts sends, not calls.)

**Tests.** The projector keeps the callback only for a row filter that asks (not for keep-all, not with the
setting off); the Iceberg reader calls it before every task after the first. The leaf call in
`QueryColumnTupleReference` needs a real leaf and is checked on the server through the log counter.

**Expected.** N1, N2, I1, I2, I5: rows past page skipping and files skipped back near the "row test off" level,
time at or below it. N3, N4, N6, I3, I6: no change. I4 (sorted): frames fill anyway, little change.

## Decisions made without asking

- Amendment 10: a separate setting `compiler.plaque.scanflush` instead of a letter in
  `compiler.plaque.distinct.flush` (which I had mentioned): the MAX/MIN filter does not read the DISTINCT setting.
- Amendment 10: Iceberg sends per file task, not per row group (reason in section 10).
- Separate row-test fields on the scan info, instead of reusing the page / file skip fields.
- Left outer join crossed on both sides for MAX/MIN.
- With a WHERE at DISTINCT levels 2 and 3 the always-true DISTINCT test is still added.
- Counters as job totals on the shared state, printed by the tuple filter.
- File and class names.

## Stated but not verified

- That the native value accessor hands out tagged AsterixDB values for every column type (DISTINCT relies on it
  for BIGINT and string only).
- That `GenericRecord.getField(name)` is cheap enough per row (one map lookup).
- That no later optimizer rule copies the scan info without the new fields (`createCopy` is covered by the copy
  constructor; other paths were not searched).
