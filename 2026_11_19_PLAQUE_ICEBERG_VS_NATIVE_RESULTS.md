# Baleen on Iceberg against native columnar, unordered data (2026-11-19)

TPC-H SF10, 4 node controllers in one JVM, 8 GB buffer cache per node controller, 64 GB heap. Each cell: one
discarded warm-up, then 6 runs with Baleen off and 6 with Baleen on, alternating. Every Baleen-on result
matched the Baleen-off result. Speedup is median off divided by median on.

## Results for the slide

| Filter type | Speedup on Iceberg | Speedup on native columnar | Files skipped on Iceberg (of 30) |
|---|---|---|---|
| MAX, single table | 7.76x (19.1 s to 2.5 s) | 11.60x (23.8 s to 2.1 s) | 26 in 6 of 6 runs |
| MAX with join | 3.27x (36.1 s to 11.1 s) | 4.36x (34.3 s to 7.9 s) | 26 in 6 of 6 runs |
| GROUP BY MAX | 1.90x (56.9 s to 30.0 s) | 1.54x (53.2 s to 34.7 s) | 0, tuple filter only |
| Theta join | 5.89x (22.4 s to 3.8 s) | 28.90x (25.7 s to 0.9 s) | 26 in 6 of 6 runs |

Iceberg numbers and both theta numbers were measured after the early-send fix (below). The native MAX, join
and GROUP BY numbers were measured before it, by the user's decision not to rerun them.

Tables: Iceberg `baleen_ice.lineitem` and `baleen_ice.orders`; native `Default.Lineitem_column_10` (primary key
`l_orderkey, l_linenumber`) and `Default.Orders_column_10`. Both lineitem tables are laid out by `l_orderkey`
and unordered on `l_suppkey`. The unit that can be skipped is a 2,000,000-row file (row groups of about
556,000 rows) on Iceberg and a page of about 15,000 rows on native.

## Queries

Baleen off is the query as written. Baleen on puts ``SET `compiler.plaque.enabled` "true";`` after the `USE`.

Iceberg:

```sql
USE baleen_ice; SELECT MAX(l.l_suppkey) AS m FROM lineitem l;
USE baleen_ice; SELECT MAX(l.l_suppkey) AS m FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey;
USE baleen_ice; SELECT MAX(l.l_discount) AS m, l.l_shipmode FROM lineitem l GROUP BY l.l_shipmode;
USE baleen_ice; SELECT COUNT(*) AS c FROM lineitem l, orders o WHERE l.l_suppkey > o.o_custkey AND o.o_orderkey = 3271;
```

Native:

```sql
USE Default; SELECT MAX(l.l_suppkey) AS m FROM Lineitem_column_10 l;
USE Default; SELECT MAX(l.l_suppkey) AS m FROM Lineitem_column_10 l, Orders_column_10 o WHERE l.l_orderkey = o.o_orderkey;
USE Default; SELECT MAX(l.l_discount) AS m, l.l_shipmode FROM Lineitem_column_10 l GROUP BY l.l_shipmode;
USE Default; SELECT COUNT(*) AS c FROM Lineitem_column_10 l, Orders_column_10 o WHERE l.l_suppkey > o.o_custkey AND o.o_orderkey = 3271;
```

Results: MAX 100000; GROUP BY 0.1 for each of the 7 ship modes; theta join 0.

## All runs behind the slide table (seconds)

| Cell | Off, runs 1-6 | On, runs 1-6 | Files skipped, runs 1-6 |
|---|---|---|---|
| Iceberg, MAX | 20.3 19.5 19.1 19.1 18.0 19.1 | 2.2 2.8 2.5 2.4 2.9 2.5 | 26 26 26 26 26 26 |
| Iceberg, MAX with join | 35.1 34.1 34.8 37.8 37.1 40.1 | 11.2 11.2 10.7 10.9 10.5 11.7 | 26 26 26 26 26 26 |
| Iceberg, GROUP BY MAX | 57.1 56.4 57.3 57.1 56.1 56.7 | 29.9 29.7 29.6 30.8 30.1 30.7 | 0 0 0 0 0 0 |
| Iceberg, theta join | 21.3 22.4 22.7 21.0 22.4 22.8 | 3.8 4.1 3.4 3.6 4.0 3.8 | 26 26 26 26 26 26 |
| Native, MAX (before fix) | 23.5 23.3 23.5 24.4 24.4 24.1 | 2.1 2.0 2.1 2.1 2.0 2.0 | n/a |
| Native, MAX with join (before fix) | 35.5 34.2 33.5 32.0 34.4 34.4 | 7.5 9.1 7.4 8.2 6.9 10.3 | n/a |
| Native, GROUP BY MAX (before fix) | 56.5 53.1 52.6 54.5 53.3 52.7 | 33.4 35.3 35.5 32.8 35.5 34.0 | n/a |
| Native, theta join | 25.8 25.4 25.7 25.6 26.6 27.4 | 0.9 0.9 0.9 0.9 0.9 0.7 | n/a |

## Finding: the advancing bound stalled just short of the maximum

First matrix of the day, before the fix (Iceberg unordered):

| Query | Off, runs 1-6 | On, runs 1-6 | Speedup | Files skipped, runs 1-6 |
|---|---|---|---|---|
| MAX | 21.9 19.9 18.8 18.0 18.2 17.7 | 2.3 16.0 2.6 2.5 2.7 4.8 | 6.88x | 26 2 26 26 26 22 |
| MIN | 17.9 18.1 17.8 17.7 17.3 18.5 | 16.5 17.0 15.9 16.4 15.8 9.1 | 1.10x | 2 1 2 2 2 14 |
| MAX with join | 35.1 34.1 35.7 33.7 34.6 38.3 | 29.2 34.2 30.9 11.3 33.4 33.1 | 1.09x | 2 0 2 26 0 1 |

- A new log line per file open (`plaque iceberg task i of n: advancing bound, file bound, decision`) showed the
  bound fixed at 99985 for MAX and 10 for MIN from the second file to the last.
- On unordered data every file's bound is 100000 (or 1), so a file is skipped only when the advancing bound has
  reached exactly that value. At 99985 nothing is skipped.
- Cause: the MAX/MIN tuple filter passed its output on only when a frame was full. Once the bound is near the
  maximum, the few rows that still pass never fill a frame, so the aggregate never sees them.
- Fix: the filter sends what it kept after every input frame, and the joins between the filter and the
  aggregate do the same for their probe output (the mechanism built for DISTINCT on 2026-11-16).
- After the fix: 26 of 30 files skipped in 18 of 18 runs across MAX, MIN and MAX with join; MIN went from 1.10x
  to 7.31x (18.7 s to 2.6 s; off 19.5 18.6 19.4 18.8 17.7 18.0; on 2.8 2.4 3.0 2.5 2.6 2.4).
- The 3.10x for MAX with join in the 11-16 results was a median of 3 in which 2 runs happened to skip.

Code changed (uncommitted, branch `baleen`):

- `PlaqueFilterRuntimeFactory.nextFrame`: send the kept rows after each input frame.
- `PlaqueRewriteRule.findInsertionPoints`: mark each join crossed on the probe side with `FLUSH_PROBE_OUTPUT`.
- `IcebergFileRecordReader.setNextRecordsIterator`: the per-file log line.

## Sorted tables (measured, then set aside by the user's decision)

Before the fix, 6 runs each:

| Table | Query | Off median | On median | Speedup | Files skipped |
|---|---|---|---|---|---|
| Iceberg sorted on `l_suppkey` | MAX | 17.7 | 2.4 | 7.52x | 26 in 6 of 6 |
| Iceberg sorted on `l_suppkey` | MIN | 18.1 | 2.4 | 7.53x | 26 in 6 of 6 |
| Iceberg sorted on `l_suppkey` | MAX with join | 46.1 | 11.2 | 4.12x | 26 in 6 of 6 |
| Iceberg sorted on `l_suppkey` | GROUP BY MAX | 56.1 | 28.3 | 1.98x | 0 |
| Native sorted on `l_suppkey` | MAX | 19.3 | 19.9 | 0.97x | n/a |
| Native sorted on `l_suppkey` | MIN | 19.0 | 18.1 | 1.05x | n/a |
| Native sorted on `l_suppkey` | MAX with join | 40.3 | 41.3 | 0.98x | n/a |
| Native sorted on `l_suppkey` | GROUP BY MAX | 54.3 | 34.5 | 1.57x | n/a |
| Native unordered | MIN | 24.1 | 4.6 | 5.27x | n/a |

- Native sorted on `l_suppkey` is the new table `Default.Lineitem_column_by_suppkey_10`, primary key
  `(l_suppkey, l_orderkey, l_linenumber)`, 59,986,052 rows, bulk loaded.
- It gets no page skipping: `l_suppkey` is then a key field that the scan produces directly, the plan has no
  ASSIGN for it, and the scan projects no column, so `PlaquePageFilterRule` attaches nothing. Only the tuple
  filter runs. The 0.97x is not a statement about sort order.
- After the fix, Iceberg sorted MAX was 7.33x and MIN 7.10x (6 runs each); the sorted join and the rest of the
  rerun were stopped when sorted results were dropped.

## Problems met on the server

- `INSERT INTO ... SELECT` halts this build's server: `NullPointerException` in
  `EntityLevelTransactionContext.notifyEntityCommitted` (the entry for the partition is missing); the file is
  identical to upstream. The server was restarted with storage kept and the existing tables checked.
  Native tables must be made with `LOAD DATASET`, as on 11-13.
- After that crash, `LOAD DATASET` into the same empty table failed with "Cannot load an index that is not
  empty"; dropping and recreating the table solved it.
- Server restarts today: 3 (crash, log line, early-send fix). Current server log:
  `build_logs/asterix_matrix_1119_earlysend.log`.

## Files

In `/scratch/asterixdb_eightynode/build_logs/`:

- `bench_matrix_1119.py`: the script.
- `bench_matrix_1119.md`, `bench_matrix_1119_per_run.md`: first matrix, before the fix, 16 cells, with the
  per-file log lines of every Iceberg run.
- `bench_matrix_1119_earlysend_part1.md`, `bench_matrix_1119_earlysend_per_run.md`: after the fix (6 complete
  cells, then stopped).
- `bench_matrix_1119_theta.md`, `bench_matrix_1119_theta_per_run.md`: theta join on both formats.
- `sorted_native/load.sh`, `load3.out`: creation of the sorted native table.

## Decisions I made without asking

- Ran the matrix proposed on 11-18 on the strength of "do the things on the server"; it included MIN and the
  sorted tables, which the user had not asked for.
- Created `Lineitem_column_by_suppkey_10` (it still exists; about the size of `Lineitem_column_10`).
- Made the early send unconditional for the MAX/MIN tuple filter instead of putting it behind a setting.
- The join annotation is set while searching for the filter position, so a join can be marked even when the
  rewrite is later abandoned.
- Stopped the rerun partway when sorted results were dropped.
- Theta query taken from the July native experiments (`PLAQUE_THETA_JOIN_EXPERIMENTS.md`).

## Stated but not verified

- That the native MAX, join and GROUP BY numbers would be the same after the fix.
- That the uneven native join times before the fix (6.9 to 10.3 s) have the same cause as the Iceberg stall.
- The cost of the early send on queries where most rows pass the filter (no cell here measures it; GROUP BY
  uses a different filter).
- Whether zone maps exist for primary-key columns, which decides how hard page skipping on a key column is.
- The unit tests were not rerun after the code changes.
