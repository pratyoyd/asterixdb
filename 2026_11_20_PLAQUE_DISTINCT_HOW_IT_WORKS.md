# PLAQUE DISTINCT filter: how it works, on one page (2026-11-20)

A reference for the terms used in the other DISTINCT notes. No new results.

## The idea

For `SELECT DISTINCT x`, a row whose `x` is already in the answer cannot change the answer. The filter
remembers which values of `x` are already in the answer and stops such rows as early as it can.

## The two parts

- **Seen-set**: the list of values of `x` already in the answer. One per node. Nodes send their new values
  to each other through the cluster controller.
- **Places that use the seen-set**: the level says how many of them are switched on.

These are two separate settings. The seen-set is always there, at every level.

## Setting 1: which seen-set (`compiler.plaque.distinct.set`, default `roaring`)

| Kind | What it is | Limit |
|---|---|---|
| `hash` | Hash set of the values | 1,000,000 values; can skip a file only if the file holds a single value |
| `flat` | One bit per possible BIGINT value | Needs the value range up front; range at most 16,000,000 |
| `roaring` | Compressed bitmap of BIGINT values | None that we hit |

Strings (for example `l_shipmode`) are kept in a hash set by all three kinds.

## Setting 2: the level (`compiler.plaque.distinct.level`, default 3)

Each level keeps everything of the levels below it and adds one place.

| Level | Place added | Question it asks the seen-set | Effect |
|---|---|---|---|
| 0 | Observer, above the join | "Is this row's value new?" | Records new values; drops duplicate rows before the sort |
| 1 | Tuple filter, above the scan | "Is this row's value seen?" | Drops the row before the join |
| 2 | File skip, before a file is opened | "Are all values between this file's lowest and highest seen?" | The file is not read. BIGINT columns only |
| 3 | List of seen values given to the Parquet reader | (the reader compares the list with each row group's dictionary) | A row group holding only seen values is not read. Needs a Parquet dictionary; list at most 10,000 values |
| 4 | Drop by dictionary entry inside a row group | "Is this dictionary entry seen?" (once per entry, asked again every 1,024 rows while not seen) | Rows with a seen value are dropped inside the Parquet reader, before they are decoded. String columns only. See `2026_11_20_PLAQUE_DISTINCT_LEVEL4.md` |

With the defaults (roaring, level 3) the observer, the tuple filter, the file skip and the list for the
Parquet reader are all on, and all of them read the same roaring seen-set.

## Other settings

| Setting | Default | Meaning |
|---|---|---|
| `compiler.plaque.distinct.flush` | `fj` | Early sending by the tuple filter (`f`) and the join (`j`), so new values reach the observer without waiting for full frames. See `2026_11_19_PLAQUE_DISTINCT_EARLY_FLUSH.md` |
| `compiler.plaque.propagation` | on | Nodes send their new values to the other nodes |
| `compiler.plaque.propagation.interval` | 5000 | Rows between two sends |
| `compiler.plaque.distinct.notinmax` | 10000 | Longest list of values given to the Parquet reader |

## What each query exercises (defaults, 2026-11-18, median of 6 runs)

| Query | Distinct values | What does the work | Baleen off | Baleen on | Speedup |
|---|---|---|---|---|---|
| Q1 `l_shipmode`, join | 7 | Level 3: 26 of 30 files not read | 203.8 s | 12.3 s | 16.56x |
| Q2 `l_suppkey`, join | 100,000 | Level 2: 21 to 26 of 30 files skipped | 164.6 s | 16.0 s | 10.31x |
| Q3 `l_partkey`, join | 1,999,998 | Level 1: tuple filter only, no file can be skipped | 143.1 s | 47.6 s | 3.01x |
| Q4 `l_shipmode`, no join | 7 | Level 3: 26 of 30 files not read | 402.5 s | 3.0 s | 134.04x |
