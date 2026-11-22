# PLAQUE DISTINCT filter: level 4 architecture (2026-11-20)

How level 4 is built, written from the code as it stands in the working tree of
`/scratch/asterixdb_eightynode/asterixdb_v3` (branch `baleen`, uncommitted). Line numbers are those of
2026-11-20. Results are in `2026_11_20_PLAQUE_DISTINCT_LEVEL4.md`; the levels in general are in
`2026_11_20_PLAQUE_DISTINCT_HOW_IT_WORKS.md`.

Scope: DISTINCT with the observer and filter on the probe side, Iceberg (Parquet) scans.

## 1. What level 4 does, in one paragraph

For `SELECT DISTINCT x`, a row whose `x` is already in the answer cannot change the answer. Levels 2 and 3 use
that to skip whole files and whole row groups. Level 4 handles the row group that cannot be skipped because it
holds at least one value not yet in the answer: while that row group is read, each row whose `x` is already in
the answer is dropped inside the Parquet reader, by its dictionary entry. The value is not decoded for that
row and the row is never handed to AsterixDB.

## 2. Where it sits

All paths below are under
`asterixdb/asterix-external-data/src/main/java/org/apache/asterix/external/input/record/reader/iceberg/`.

| File | Role for level 4 |
|---|---|
| `PlaqueDistinctDictionaryReader.java` (new, 186 lines) | The level 4 reader: decides per dictionary entry, drops rows |
| `PlaqueIcebergDistinctScanFilter.java` | Holds the seen-set, the column and the level for one scan task; hands out the level 4 reader |
| `IcebergFileRecordReader.java` | Opens each file; plugs the level 4 reader into Iceberg's Parquet read and removes the dropped rows |

Nothing else changed for level 4. In particular the rewrite rules (`PlaqueRewriteRule`,
`PlaquePageFilterRule`) are untouched: they already pass the level as a number down to the scan, and every
check on it is of the form `level >= N`.

Call chain for one data file:

```
IcebergFileRecordReader.setNextRecordsIterator()            one call per file
  ├─ plaqueDistinctFilter.allSeen(task)                     level 2: skip the file
  ├─ plaqueDistinctFilter.rowGroupFilter()                  level 3: NOT IN list for row-group skipping
  └─ openDistinctRead(inFile, task, projectedSchema, plaqueDistinctFilter, plaqueFilter)
       └─ Parquet.read(...).createReaderFunc(fs ->
              plaqueDistinctFilter.dictionaryReader(projectedSchema, fs)     level 4
                └─ PlaqueDistinctDictionaryReader.create(...)                or null: Iceberg's own reader
          )
       └─ CloseableIterable.filter(rows, r -> r != DROPPED)
```

## 3. How the level reaches the reader

1. `compiler.plaque.distinct.level` (default `AlgebricksConfig.PLAQUE_DISTINCT_LEVEL_DEFAULT`, now 4) is read
   into the physical optimization config.
2. `PlaqueRewriteRule.handleDistinct` puts it on the observer as the annotation `plaque-distinct-level`.
3. `PlaquePageFilterRule.handleDistinctObserver` finds the Iceberg scan below the observer and calls
   `info.setPlaqueDistinctFilter(handle, fieldName, set, level, notinmax)` on the scan's projection info.
4. `ExternalDataUtils` (lines 1196 to 1204) copies these into the scan's configuration map as
   `PLAQUE_DISTINCT_HANDLE`, `_FIELD`, `_SET`, `_LEVEL`, `_NOTINMAX`.
5. On each node, `IcebergParquetRecordReaderFactory` creates one `PlaqueIcebergDistinctScanFilter` per reader
   from that map (`PlaqueIcebergDistinctScanFilter.create`). `create` looks up this node's seen-set in
   `PlaqueThresholdRegistry` by job, handle and node id, so the scan and the observer on the same node share
   one seen-set.

## 4. Opening a file: `IcebergFileRecordReader`

`setNextRecordsIterator()` (around lines 331 to 368) does, for each file, in this order:

1. Level 2: `plaqueDistinctFilter.allSeen(task)`. If true the file is counted as skipped and not opened.
2. Level 3: `plaqueDistinctFilter.rowGroupFilter()` gives `x NOT IN (seen values)`, which is ANDed into the
   row-group filter.
3. The variant-pruned read is tried first, as before. If it takes the file, level 4 is not used for it.
4. Otherwise:

```java
boolean hasDeletes = task.deletes() != null && !task.deletes().isEmpty();
iterable = plaqueDistinctFilter != null && !hasDeletes
        ? openDistinctRead(inFile, task, projectedSchema, plaqueDistinctFilter, plaqueFilter)
        : openStandardRead(readFileIo, inFile, task, schemaAtSnapshot, projectedSchema, deleteCache,
                plaqueFilter);
```

`openDistinctRead` (lines 422 to 430) is Iceberg's standard read with one change, the reader function:

```java
CloseableIterable<Record> rows = Parquet.read(inFile).project(projectedSchema)
        .filter(rowGroupFilter(task, extraFilter)).split(task.start(), task.length()).createReaderFunc(fs -> {
            ParquetValueReader<Record> r = distinctFilter.dictionaryReader(projectedSchema, fs);
            return r != null ? r : GenericParquetReaders.buildReader(projectedSchema, fs);
        }).build();
return CloseableIterable.filter(rows, r -> r != PlaqueDistinctDictionaryReader.DROPPED);
```

Three things to note:

- Iceberg calls the reader function once per file, with the file's (projected) Parquet schema `fs`. That is
  where the decision "level 4 for this file or not" is taken.
- If `dictionaryReader` returns `null`, the function returns Iceberg's own reader, so the read is exactly the
  standard one. `dictionaryReader` returns `null` below level 4
  (`PlaqueIcebergDistinctScanFilter.dictionaryReader`, line 111) and whenever the reader declines (section 5).
- The level 4 reader does not skip a row; it returns the marker object `DROPPED` for it. The wrapper
  `CloseableIterable.filter` removes the markers. Iceberg's file iterator counts one `read()` per row of the
  row group, so the reader has to return something for every row.

Row-group skipping (levels 3) still happens in the same `Parquet.read(...).filter(...)` call, before any row
group reaches the level 4 reader.

## 5. Building the reader: `PlaqueDistinctDictionaryReader.create`

Iceberg's row reader is a tree of column readers, one per projected column, built by
`GenericParquetReaders.buildReader`. It offers no way to replace one column's reader, and it does not expose
dictionary codes. `create` (lines 86 to 113) gets around both with public API only:

1. **Checks.** The column must be in the projection and be a string
   (`f.type().typeId() == Type.TypeID.STRING`), and in the file it must be a top-level primitive `BINARY`
   column found by its Iceberg field id. Otherwise `create` returns `null` and the file is read as usual.
2. **Split the file schema.** The file's columns are split into the DISTINCT column and `others`.
3. **Let Iceberg read the others.**

```java
List<Types.NestedField> fields = new ArrayList<>(projected.columns());
int pos = fields.indexOf(f);
fields.set(pos, Types.NestedField.optional(f.fieldId(), f.name(), f.type(), f.doc()));
ParquetValueReader<Record> rest =
        GenericParquetReaders.buildReader(new Schema(fields), new MessageType(fileSchema.getName(), others));
```

   `rest` is Iceberg's normal reader, built for the full projection but against a file schema from which the
   DISTINCT column has been removed. Iceberg treats a column missing from the file as null, so `rest` produces
   complete records with a null in position `pos` and never touches the DISTINCT column's pages. Iceberg allows
   a missing column only when it is optional, so the copy of the projection given to `rest` marks the column
   optional. (`l_shipmode` is required in `lineitem`; without this step level 4 was inactive on it.)
4. **Read the DISTINCT column itself.** The class extends `ParquetValueReaders.PrimitiveReader<Record>` and
   is constructed on the DISTINCT column's `ColumnDescriptor`. That base class is public and gives the reader
   its own `column` iterator over that column's pages. (Implementing `ParquetValueReader` directly is not
   possible outside Iceberg's package: the interface mentions the package-private `TripleIterator`.)

So the reader is two readers side by side: `column` for the DISTINCT column, `rest` for everything else.
`setPageSource` (lines 115 to 122), called by Iceberg at the start of each row group, points both at the new
row group and clears the per-row-group state.

## 6. Reading a row: `read`

```java
public Record read(Record reuse) {
    String v = null;
    if (column.currentDefinitionLevel() < maxDefinitionLevel) {
        // nulls are never recorded, so never dropped
        column.nextNull();
    } else {
        Entry e = entry(column.nextBinary());
        if (e.seen) {
            scratch = rest.read(scratch);
            dropped[0]++;
            return DROPPED;
        }
        v = e.value;
    }
    Record r = rest.read(null);
    r.set(pos, v);
    return r;
}
```

Per row:

1. Look at the DISTINCT column first. A null is kept: nulls are never in the seen-set.
2. Otherwise take the value as Parquet's `Binary` (not yet a string) and ask `entry(...)` about it.
3. **Seen:** the other columns are read into a reused scratch record, only to move their streams forward by
   one row; the drop counter is incremented; the marker `DROPPED` is returned. No string was made for this
   row, no new record was allocated.
4. **Not seen:** `rest` builds the record as usual and the string is put into position `pos`. The string is
   the one cached for the dictionary entry, so it is decoded once per entry, not once per row.

Why the other columns are still read for a dropped row: Iceberg's column readers are streams with no "skip
one value" call. If they were not advanced, every later row would be assembled from the wrong values.

## 7. Deciding per dictionary entry: `entry`

```java
private Entry entry(Binary b) {
    if (off) {
        return new Entry(b.toStringUsingUTF8(), false);
    }
    rows++;
    Entry e = entries.get(b);
    if (e == null) {
        if (b.isBackingBytesReused() || entries.size() >= MAX_ENTRIES) {
            off = true;
            entries.clear();
            return new Entry(b.toStringUsingUTF8(), false);
        }
        e = new Entry(b.toStringUsingUTF8(), true);
        entries.put(b, e);
    } else if (e.seen || rows - e.checkedAt < RECHECK_ROWS) {
        return e;
    }
    e.seen = state.contains(e.key, 0, e.key.length);
    e.checkedAt = rows;
    return e;
}
```

**The dictionary entry is recognised by object identity.** In a dictionary-encoded page each row stores a
small number, and Parquet turns it into a value by looking it up in the dictionary. That lookup returns the
same `Binary` object every time for the same entry. `entries` is an `IdentityHashMap<Binary, Entry>`: it is
keyed on the object, not on its bytes, so finding a row's entry costs one pointer lookup and no comparison of
string contents. This object identity stands in for the numeric code.

**One `Entry` per dictionary entry** holds:

| Field | Meaning |
|---|---|
| `value` | The decoded string, made once |
| `key` | The value in the form the seen-set stores: the AsterixDB string type tag followed by the length-prefixed UTF-8 bytes, built once |
| `seen` | Whether the value is in the answer |
| `checkedAt` | Row count at the last question to the seen-set |

**When the seen-set is asked** (`state.contains`):

- the first time an entry is met;
- again when the entry is met and was "not seen" at least `RECHECK_ROWS` = 1,024 rows ago;
- never again once it is "seen". A value never leaves the answer, so "seen" is final.

The re-check is what makes level 4 work on a file that was opened while the seen-set was empty. All entries
start as "not seen"; the first rows travel to the observer, which records their values; within about a
thousand rows the reader asks again, gets "seen", and from then on drops that entry's rows. On Q4 this leaves
12,347 of 8,000,000 rows.

Between two questions an entry may be "not seen" in the reader although the value has just entered the
answer. Such rows are passed on and dropped above, by the tuple filter or the observer. The result is the
same; only a few rows travel further than needed.

**When identity cannot be trusted, the reader turns itself off for the rest of the row group** (`off`):

- `b.isBackingBytesReused()`: Parquet says this object's bytes may be overwritten for the next value, so the
  same object could later mean a different string.
- More than `MAX_ENTRIES` = 4,096 distinct objects in one row group: the values are not coming from a
  dictionary (a plainly encoded page makes a new object for every row), or the dictionary is too large for
  this to pay.

Once off, every row is decoded and passed on as the standard reader would, and nothing is dropped in the
reader. `setPageSource` turns it back on for the next row group, which has its own dictionary.

## 8. Why dropping here is correct

- The seen-set consulted is this node's seen-set for this query (`PlaqueThresholdRegistry`, keyed by job,
  handle and node). It contains only values this node's observer has passed, or values another node's
  observer has passed and sent through the cluster controller. In both cases some observer has already let a
  row with that value through to the output.
- Dropping a row with such a value is what the tuple filter (level 1) already does one operator above the
  scan, with the same seen-set. Level 4 makes the same decision earlier.
- Nulls are never dropped, and a value is dropped only on a "seen" answer from the seen-set itself; the cache
  can only be out of date in the safe direction ("not seen" when it is seen).

## 9. Counters

`PlaqueIcebergDistinctScanFilter` holds `long[] rowsDropped` (one element, so the reader can increment it) and
`IcebergFileRecordReader.close()` logs, once per scan task:

```
plaque iceberg scan: {} tasks, {} skipped by file bound, {} read with a row-group filter, {} rows dropped by dictionary entry
```

## 10. When level 4 is not used

| Case | Where decided | What happens |
|---|---|---|
| Level below 4 | `PlaqueIcebergDistinctScanFilter.dictionaryReader` | Iceberg's own reader |
| DISTINCT column is not a string (for example BIGINT: Q2, Q3) | `create` | Iceberg's own reader |
| Column not found in the file by field id, or not a top-level `BINARY` column | `create` | Iceberg's own reader |
| File has delete files | `setNextRecordsIterator` | `openStandardRead`, as before |
| File taken by the variant-pruned read | `setNextRecordsIterator` | That read, as before |
| Values not dictionary entries, or more than 4,096 in a row group | `entry` | Decoded and passed on for the rest of the row group |

## 11. Tests

`asterixdb/asterix-external-data/src/test/java/org/apache/asterix/external/input/record/reader/iceberg/PlaqueDistinctDictionaryReaderTest.java`
writes a 20,000-row Parquet file and reads it through `IcebergFileRecordReader.openDistinctRead`, the same
call the server makes. 10 tests:

| Test | Checks |
|---|---|
| `anEmptySeenSetDropsNothing` | All rows returned, counter 0 |
| `rowsWithASeenValueAreDroppedAndAllOthersAreReturnedUnchanged` | Exactly the rows with a seen value are missing; every other column of every returned row is intact; counter equals rows dropped |
| `everyValueSeenLeavesOnlyTheNullRows` | Nulls are kept |
| `theColumnOrderOfTheProjectionIsKept` | Value lands in the right position when the projection reorders columns |
| `onlyTheDistinctColumnProjected` | Works when there are no other columns (Q4's shape) |
| `aValueSeenWhileTheFileIsReadIsDroppedSoonAfter` | Re-check: no row of that value is returned later than about two re-check intervals after it was seen |
| `belowLevelFourNothingIsDroppedInTheReader` | Level 3 reads everything |
| `aRequiredStringColumnIsFilteredToo` | The optional-copy step of section 5 |
| `aLongColumnIsReadAsUsual` | Non-string column declines |
| `aColumnWrittenWithoutADictionaryStillGivesTheRightRows` | No unseen row is lost and no row is wrong when values are not dictionary entries |

## Decisions made without asking

- Object identity of Parquet's dictionary entry is used in place of the numeric code.
- `RECHECK_ROWS` = 1,024 and `MAX_ENTRIES` = 4,096 were chosen, not tuned.
- This note was written in the `plaque/` folder and not committed.

## Stated but not verified

- That Parquet returns the same `Binary` object for the same dictionary entry is inferred from behaviour
  (the drop counter equals the expected count in the tests and on the server); Parquet's source was not read.
- The paths with delete files and with the variant-pruned read were not exercised with level 4.
- The last test does not confirm that the file it writes really has no dictionary; it only checks the rows.
- Steps 1 to 4 of section 3 are described from the code read on 2026-11-18 and 2026-11-20; the config copy in
  `ExternalDataUtils` was located by line number, not re-read in full.
