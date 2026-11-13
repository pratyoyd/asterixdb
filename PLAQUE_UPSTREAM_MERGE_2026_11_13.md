# PLAQUE + SmartRabbit on upstream AsterixDB (Iceberg): the merge that worked

**Date:** 2026-11-13
**Branch:** `interactive-processing-v3` (pushed to `pratyoyd/asterixdb`, head `046a78a586`)
**Tree:** `/scratch/asterixdb_eightynode/asterixdb_v3` on sensoria-2
**Upstream merged:** apache/asterixdb `master` at `a4a85d80c6` (2026-11-12), which includes the Iceberg work

## Outcome

- Everything from the last good PLAQUE/SmartRabbit commit (`2537f530f0`) plus the uncommitted GROUP BY work is on top of current upstream.
- `hyracks-fullstack` and `asterixdb` both build.
- The server starts through `AsterixHyracksIntegrationUtil` on fresh storage.
- PLAQUE speedups at TPC-H SF10 match the numbers recorded before the merge, with identical query results with PLAQUE on and off.
- From here on, pulling upstream is an ordinary `git merge upstream/master`.

## Why the four earlier attempts failed

`origin/interactive-processing` began as a snapshot import: its root commit `1c6422d377` has no parent in upstream history. Git therefore had no common ancestor, so every merge or rebase treated all of upstream's 1,311 commits since the snapshot as conflicting with the whole tree. The attempts in September 2026 ended in a hand-port (`interactive-processing-v2` in the old tree) that never converged.

## What made it work

1. **Find the true base.** The snapshot matches upstream commit `ecc0031b99` (2025-02-06).
2. **Graft, merge, ungraft.** A temporary replace ref made the snapshot root a child of that base:

   ```bash
   GIT_REPLACE_REF_BASE=refs/baleen-graft/ git replace --graft 1c6422d377 ecc0031b99
   ```

   With the graft in place, `git merge upstream/master` was a normal three-way merge: just under 60 conflicted files instead of thousands. The graft was removed afterwards; the merge commit records both parents, so it is not needed again.
3. **Re-merge eight files against their real base.** Eight files in the snapshot came from older upstream revisions than `ecc0031b99`. Each was re-merged with `git merge-file` against the revision it actually derived from.
4. **Restore upstream test files** that the snapshot import had dropped, so the merge did not delete them.
5. **Port GROUP BY from the stash.** 25 PLAQUE-owned files were taken whole; edits to existing files were re-applied by hand and cross-checked against `PLAQUE_GROUPBY_MINMAX_FILTER_PLAN.md`.

## Commits

| Commit | What |
|---|---|
| `212fa68731` | Restore upstream test files dropped by the snapshot import |
| `cdd3c08714` | Merge upstream/master (`a4a85d80c6`) with PLAQUE and SmartRabbit preserved |
| `65437756e9` | PLAQUE GROUP BY: restore edits to existing files from stash |
| `a9c45d57ae` | Untrack `plaque/` and `.claude/`, add to `.gitignore` |
| `046a78a586` | Fix build after upstream merge |

## Resolutions worth knowing about

- **`AbstractColumnTupleReference`** was rewritten upstream. Upstream's file was taken and the PLAQUE physical page skip re-added: `newPage()` evaluates `startNewPage(numberOfTuples)` before primary-key decompression when skip is enabled, and `reset()` returns early for skipped pages. This is the code behind the 11.5x and 4.1x results below.
- **`PlaquePageFilterRule`** stays in `prepareForJobGenRewrites`, as in `2537f530f0`. The stash had it in `physicalRewritesTopLevel`. GROUP BY speedups reproduce with the current placement.
- **`PlaqueGroupByJobGenRule`** runs after `PushGroupByIntoSortRule`.
- **Config classes** (`CompilerProperties`, `OptimizationConfUtil`, `PhysicalOptimizationConfig`, `AlgebricksConfig`, `RuleCollections`, operator tags) are the union of both sides. Upstream's name `COMPILER_ORDERED_FIELDS` is used. The 1KB frame-size default is kept.
- **`EstimatedCostComputationVisitor`**: the incremental-order and eager-merge visitors now return upstream's `CardSizeCost`.
- **`IIndexAccessor`**: the three-argument `search` is a default no-op so upstream's new accessors compile.
- **`INLJMismatchWriter` and `INLJMismatchWriterFactory`** were never committed; they were restored from `~/plaque_code_backup/new_files`.

## Upstream API changes that touched our code

| Before | Now |
|---|---|
| `org.apache.hyracks.algebricks.common.utils.Pair` (`.first`, `.second`) | commons-lang3 `Pair` (`getLeft()`, `getRight()`, `Pair.of`, `MutablePair`) |
| `new VSizeFrame(ctx)` | `ctx.allocateVSizeFrame()` |
| `getGByList()` | `getGroupByList()` |
| `createSecondaryIndexUnnestMap(...)` | one fewer boolean argument |
| cost visitor returns a pair | returns `CardSizeCost` |

New upstream operators (ClusterBy, KMeansStage) needed cases in our visitors.

## Building

Hyracks first, with `install`, because `asterixdb/` resolves Hyracks `0.3.10-SNAPSHOT` from `~/.m2`:

```bash
cd hyracks-fullstack && mvn clean install -DskipTests -DskipRat -Drat.skip=true -Dlicense.skip=true -Dcheckstyle.skip=true
```

Then the README command in `asterixdb/`, with two additions:

```bash
cd asterixdb && mvn clean package -DskipTests -DskipRat -Drat.skip=true -Dlicense.skip=true -Dcheckstyle.skip=true -Dmaven.gitcommitid.nativegit=true
```

- `-Drat.skip=true`: 81 of our files have no Apache header, and `-DskipRat` alone does nothing.
- `-Dmaven.gitcommitid.nativegit=true`: only needed while the tree is a git worktree.

Java 21, Maven 3.8.7. A clean `asterixdb` build takes about 9 minutes.

## Verification

**Smoke tests.** PLAQUE scalar and GROUP BY operators appear in plans and return the same results as with PLAQUE off. With interactive mode on, the plan contains `INCREMENTAL_SORT` and `RANDOM_MERGE_ONE_EXCHANGE`.

**TPC-H SF10** (59,986,052 lineitem rows, row and column datasets). Median of three runs after one warm-up, alternating baseline and PLAQUE.

| Query | Baseline (s) | PLAQUE (s) | Speedup | Recorded before merge | Results match |
|---|---|---|---|---|---|
| Single-table `MAX(l_suppkey)`, column | 24.5 | 2.1 | 11.52x | 11.60x | yes |
| Lineitem-Orders join `MAX`, column, page filter | 33.1 | 8.0 | 4.12x | 4.20x | yes |
| `MAX(l_discount) GROUP BY l_shipmode`, column | 54.5 | 37.8 | 1.44x | 1.41x | yes |
| `MAX(l_discount) GROUP BY l_shipmode`, row | 38.5 | 21.6 | 1.79x | 1.41x | yes |
| Cross-table `MAX(l_extendedprice - ps_supplycost)`, column | 49.2 | 7.2 | 6.84x | 2.64x | yes |
| Single-table `MAX(l_shipdate)`, row | 15.2 | 14.8 | 1.02x | not recorded | yes |

Setup: 4 NCs in one process, 8GB buffer cache per NC, 64GB heap, freshly loaded single disk component. The recorded numbers used 16GB per NC, so compare ratios, not absolute times. Script and raw output: `/scratch/asterixdb_eightynode/build_logs/bench_plaque.py` and `bench_result.md`.

## Open items

- The cross-table expression speedup (6.84x against 2.64x recorded) is unexplained. Upstream changes to the join or scan may have moved the baseline.
- Not run on the merged build: SF30 and above, the clustered-table GROUP BY page filter, theta join, Top-K.
- Iceberg has not been exercised end to end; it only compiles.
- The `SmartRabbitExecutor.py` workflow has not been run; only the interactive plan shape was checked.
- The query-service JSON still has no comma after `requestID`. That comes from the incremental result printer and predates the merge.

## Where the old state lives

- Old tree, untouched: `/scratch/asterixdb_eightynode/asterixdb_eightnode` (branch `interactive-processing-v2`). Its NC storage has not been touched. Delete it only once this tree is trusted.
- Backup branches in the old tree: `baleen-backup/last-good` (`2537f530f0`), `baleen-backup/groupby-stash`, `baleen-backup/groupby-newfiles`.
- Stash as a patch: `~/plaque_code_backup/stash_2026-09-20/stash0_vs_f2db1b97b2.patch`.
- Build and benchmark logs and helper scripts: `/scratch/asterixdb_eightynode/build_logs/`.
