# PLAQUE State of Affairs — June 28, 2026

## What PLAQUE Does

PLAQUE (Predicate Learning at Query Time) learns filter predicates during query execution to prune tuples early. For a `SELECT MAX(col)` query, as the aggregate discovers the running maximum, a filter near the scan drops any tuple with a value below that threshold — avoiding unnecessary network shuffle, join probing, and aggregation.

Based on Chapter 4 of Yiming Lin's PhD thesis (UCI, 2023, advisor Sharad Mehrotra). Thesis at `/scratch/asterixdb_eightynode/asterixdb_eightnode/Yiming_s_PhD_Thesis_final.pdf`.

Student **apmohant** (Aparna Mohant) implemented all PLAQUE code. Her repo: `/scratch/apmohant/asterixdb/`.


## Implementation Status

### M1 (Single-Table MAX/MIN) — Complete

Single-table queries like `SELECT MAX(l_shipdate) FROM Lineitem`. Filter and observer (local aggregate) are micro-operators in the same pipeline. Originally used per-task `FilterState` sharing via `IOperatorEnvironment`; migrated in M2 to NC-level registry.

### M2 (Join Queries with MAX/MIN) — Complete

Join queries like:
```sql
SELECT MAX(l.l_extendedprice)
FROM Lineitem l, Orders o
WHERE l.l_orderkey = o.o_orderkey AND o.o_orderdate < '1995-01-01'
```

Two-layer threshold propagation:
- **Layer 1 (intra-node):** All partitions on the same NC share a `PlaqueThresholdState` via `PlaqueThresholdRegistry` (static singleton, `ConcurrentHashMap`). Zero messaging overhead.
- **Layer 2 (inter-node):** NC sends `PlaqueThresholdUpdateMessage` to CC; CC maintains global best in `PlaqueThresholdCCState`; CC broadcasts `PlaqueThresholdBroadcastMessage` to all NCs.


## Source Files (all uncommitted on `interactive-processing` branch)

### New files (untracked)

| File | Module | Role |
|---|---|---|
| `PlaqueRewriteRule.java` | asterix-algebra/.../optimizer/rules/ | Optimizer rule: identifies eligible aggregates (LOCAL_SQL_MAX/MIN with single variable arg), walks plan to find filter insertion points, inserts SelectOperator filters and marks aggregate with PlaqueAggregatePOperator |
| `PlaqueAggregatePOperator.java` | asterix-algebra/.../operators/physical/ | Physical operator for PLAQUE-marked aggregate. Overrides `contributeRuntimeOperator` to produce `PlaqueLocalAggregateEvaluatorFactory` instead of standard factory. Carries handle, isMax, propagationEnabled, propagationInterval. |
| `PlaqueFilterPOperator.java` | asterix-algebra/.../operators/physical/ | Physical operator for PLAQUE filter SelectOperator. `isMicroOperator()=true`. Contributes `PlaqueFilterRuntimeFactory`. Has `requiresPartitioning` flag — when true, enforces parent's partitioning on child, causing `EnforceStructuralPropertiesRule` to insert exchange. |
| `PlaqueLocalSqlMinMaxAggregateFunction.java` | asterix-runtime/.../aggregates/std/ | Extends `SqlMinMaxAggregateFunction`. Overrides `onMinMaxChanged()` to call `thresholdState.update()`. Every `propagationInterval` steps, calls `maybePropagateThreshold()` which sends `PlaqueThresholdUpdateMessage` to CC if state is dirty. Logs PLAQUE_AGG_STATS on `finish()`. |
| `PlaqueLocalAggregateEvaluatorFactory.java` | asterix-runtime/.../operators/plaque/ | Serializable `IAggregateEvaluatorFactory`. On `createAggregateEvaluator()`: resolves `PlaqueThresholdState` from registry, sets up propagator lambda (gets `INCMessageBroker` from service context), creates `PlaqueLocalSqlMinMaxAggregateFunction`. |
| `PlaqueFilterRuntimeFactory.java` | asterix-runtime/.../operators/plaque/ | Contains inner `PlaqueFilterRuntime`. On `open()`: resolves state from `PlaqueThresholdRegistry`, creates thread-local `IBinaryComparator`. On `nextFrame()`: per-tuple `passes()` check. Logs PLAQUE_FILTER_STATS on `close()` (total, passed, filtered, nullPassed, thresholdUninitTuples, filterRate%). Calls `removeJob()` on close. |
| `PlaqueThresholdState.java` | asterix-runtime/.../operators/plaque/ | Thread-safe threshold cell. `synchronized update()/mergeGlobal()` for writes. `volatile` fields for lock-free `passes()` reads. `AtomicBoolean dirty` for propagation tracking. `passes()` takes caller-supplied `IBinaryComparator` (since `AGenericAscBinaryComparator` has mutable internal state). |
| `PlaqueThresholdRegistry.java` | asterix-runtime/.../operators/plaque/ | NC-level singleton. `ConcurrentHashMap<String, PlaqueThresholdState>` keyed by `jobId:handle`. `getOrCreate()`, `get()`, `mergeGlobal()`, `removeJob()`. |
| `PlaqueThresholdCCState.java` | asterix-runtime/.../operators/plaque/ | CC-level singleton. `ConcurrentHashMap<String, ThresholdEntry>` keyed by `jobId:handle`. `ThresholdEntry` holds isMax, bytes, length, initialized, comparator. `update()` returns true only if threshold improved. Used by `PlaqueThresholdUpdateMessage.handle()` to decide whether to broadcast. |
| `PlaqueThresholdPropagator.java` | asterix-runtime/.../operators/plaque/ | Functional interface (lambda) for sending threshold updates from observer to CC. |
| `PlaqueThresholdUpdateMessage.java` | asterix-runtime/.../message/ | NC-to-CC `ICcAddressedMessage`. Carries jobId, handle, thresholdBytes, thresholdLength, isMax. Handler: calls `PlaqueThresholdCCState.INSTANCE.update()`, if improved gets snapshot and broadcasts `PlaqueThresholdBroadcastMessage` to all participant nodes via `ICCMessageBroker`. |
| `PlaqueThresholdBroadcastMessage.java` | asterix-runtime/.../message/ | CC-to-NC `INcAddressedMessage`. Handler: calls `PlaqueThresholdRegistry.INSTANCE.mergeGlobal()`. |

### Modified existing files

| File | Change |
|---|---|
| `AbstractMinMaxAggregateFunction.java` | Added `protected void onMinMaxChanged()` hook (called after first-value assignment, LT branch for MIN, GT branch for MAX). Changed `outputVal` from `private` to `protected`. |
| `RuleCollections.java` | Added `PlaqueRewriteRule` to consolidation phase after `IntroduceAggregateCombinerRule` |
| `CompilerProperties.java` | Added 4 PLAQUE options: `COMPILER_PLAQUE_ENABLED`, `COMPILER_PLAQUE_PROPAGATION`, `COMPILER_PLAQUE_PROPAGATION_INTERVAL`, `COMPILER_PLAQUE_PUSH_THROUGH_JOIN` + their key constants |
| `OptimizationConfUtil.java` | Reads PLAQUE config from query-specific config, propagates to `PhysicalOptimizationConfig` |
| `AlgebricksConfig.java` | Added defaults: `PLAQUE_ENABLED_DEFAULT=false`, `PLAQUE_PROPAGATION_DEFAULT=true`, `PLAQUE_PROPAGATION_INTERVAL_DEFAULT=5000`, `PLAQUE_PUSH_THROUGH_JOIN_DEFAULT=true` |
| `PhysicalOptimizationConfig.java` | Added PLAQUE getters/setters (plaqueEnabled, plaquePropagation, plaquePropagationInterval, plaquePushThroughJoin) |
| `PhysicalOperatorTag.java` | Added `PLAQUE_FILTER`, `PLAQUE_AGGREGATE` |
| `ConsolidateSelectsRule.java` | Modified to skip PLAQUE-annotated selects |
| `PushSelectDownRule.java` | Modified to skip PLAQUE-annotated selects |
| `RemoveRedundantSelectRule.java` | Modified to skip PLAQUE-annotated selects |
| `SqlppCompilationProvider.java` | Modified (presumably for PLAQUE registration) |

### Configuration Parameters

| Parameter | Key | Type | Default | Description |
|---|---|---|---|---|
| Master switch | `compiler.plaque.enabled` | boolean | false | Enables PLAQUE filter insertion |
| Cross-node propagation | `compiler.plaque.propagation` | boolean | true | Layer 2 on/off |
| Propagation interval | `compiler.plaque.propagation.interval` | integer | 5000 | Tuples between propagation attempts |
| Push through join | `compiler.plaque.push.through.join` | boolean | true | Filter below exchange vs. above join |


## Current Filter Placement (M2)

The `PlaqueRewriteRule.findInsertionPoints()` walks backward from the local aggregate through the plan:
- Passes through ASSIGN (unless it defines the filtered variable), PROJECT, EXCHANGE
- When hitting INNERJOIN/LEFTOUTERJOIN (if `pushThroughJoin=true`): checks if variable comes from probe side (input 0). If yes, saves `joinProbeRef` and enters probe side. If from build side, stops with weak filter above join.
- Stops at DATASOURCESCAN, SELECT, or unknown operators

Two filters are inserted when pushing through a join:

```
GlobalAggregate(global-sql-max)
  └── RANDOM_MERGE_EXCHANGE
      └── LocalAggregate(local-sql-max)    ← OBSERVER: onMinMaxChanged() → PlaqueThresholdState.update()
          └── [STREAM_PROJECT / ASSIGN]
              └── HYBRID_HASH_JOIN(l_orderkey = o_orderkey)
                  ├── HASH_PARTITION_EXCHANGE(l_orderkey)
                  │    └── PlaqueFilter [SHALLOW] (requiresPartitioning=true)
                  │        └── HASH_PARTITION_EXCHANGE     ← inserted by EnforceStructuralPropertiesRule
                  │            └── PlaqueFilter [DEEP] (requiresPartitioning=false)
                  │                └── [ASSIGN]
                  │                    └── DATASOURCE_SCAN(Lineitem)
                  └── HASH_PARTITION_EXCHANGE(o_orderkey)
                       └── SELECT(o_orderdate < '1995-01-01')
                           └── DATASOURCE_SCAN(Orders)
```

- **Deep filter** — below the hash-partition exchange, near the scan. Opportunistic: only effective if the scan activity pipeline overlaps with the probe activity (i.e., scan is still producing tuples while aggregate is already learning thresholds).
- **Shallow filter** — above the hash-partition exchange, at the join's probe input. Sets `requiresPartitioning=true`, forcing exchange insertion between the two filters.


## Experiment Results

### Query

```sql
SELECT MAX(l.l_extendedprice)
FROM Lineitem_SF l, Orders_SF o
WHERE l.l_orderkey = o.o_orderkey AND o.o_orderdate < '1995-01-01'
```

### Setup
- Single-node, 4 NCs (all in one JVM), 16GB buffer cache, 64GB JVM heap
- 5 runs per configuration, 1 warmup run before each SF

### SF 10 — 1.7x speedup

| Mode | Interval | Median (s) | Mean (s) | Speedup |
|---|---|---|---|---|
| Baseline | N/A | 37.00 | 37.07 | 1.00x |
| PLAQUE | 1 (eager) | 22.43 | 22.11 | 1.65x |
| PLAQUE | 100 | 21.40 | 21.69 | 1.71x |
| PLAQUE | 5000 (default) | 20.87 | 20.87 | 1.77x |
| PLAQUE | MAX_INT (finish only) | 21.45 | 21.43 | 1.73x |

All intervals ~equal on single-node (Layer 1 dominates). Correct answer: 104949.0.

### SF 50 — No speedup (1.00x)

| Mode | Interval | Median (s) | Mean (s) | Speedup |
|---|---|---|---|---|
| Baseline | N/A | 331.63 | 330.51 | 1.00x |
| PLAQUE | 1 | 331.78 | 331.63 | 1.00x |
| PLAQUE | 100 | 332.21 | 333.38 | 0.99x |
| PLAQUE | 5000 | 334.71 | 343.42 | 0.96x |
| PLAQUE | MAX_INT | 331.37 | 333.70 | 0.99x |

Correct answer: 104949.5.

### SF 100 — No speedup, slight overhead

| Mode | Interval | Median (s) | Mean (s) | Speedup |
|---|---|---|---|---|
| Baseline | N/A | 661.59 | 667.63 | 1.00x |
| PLAQUE | 1 | 691.11 | 701.86 | 0.95x |
| PLAQUE | 100 | 680.91 | 688.19 | 0.97x |
| PLAQUE | 5000 | 694.58 | 699.66 | 0.95x |
| PLAQUE | MAX_INT | 696.45 | 693.99 | 0.96x |

Correct answer: 104947.5. PLAQUE adds slight overhead (~5%) due to filter evaluation and registry lookups without filtering any tuples.


## The Problem: Why PLAQUE Fails at SF 50+

**Root cause: The lineitem scan completes before the aggregate produces useful thresholds.**

In a hash join, execution proceeds in two phases:
1. **Build phase:** Orders is fully consumed into the hash table.
2. **Probe phase:** Lineitem streams through, probing the hash table. Matching tuples flow to the aggregate.

The PLAQUE filter sits below the hash-partition exchange on the probe side. The problem is **temporal**: the filter only becomes useful once the aggregate has processed enough tuples to establish a meaningful threshold. But:

- **SF 10 (~60M lineitem rows):** Data fits in the 16GB buffer cache. The pipeline overlaps — while the probe activity feeds tuples to the aggregate, the scan activity is still reading new lineitem tuples. The filter can drop late-arriving tuples. The 1.7x speedup is partly a buffer-cache artifact.
- **SF 50 (~300M rows) and SF 100 (~600M rows):** The scan activity reads all lineitem tuples and sends them through the hash-partition exchange **before** the probe activity has processed enough to establish a useful threshold. By the time the aggregate knows the max is ~104949, there are no more scan tuples left to filter.

The filter is in the right logical position but the wrong temporal position: **the scan is finished by the time the threshold is useful.**

Additionally, `l_extendedprice` has a roughly uniform distribution with max ~104949. Even if the filter were active during the scan, the predicate `price >= threshold` would have low selectivity for most of the run — most prices are close to the max.


## Experiment Scripts & Tools

| File | Purpose |
|---|---|
| `plaque/plaque_bench.py` | Python benchmark tool. Runs normal vs PLAQUE, extracts profiler timings (scan/agg/plaque-filter), saves full profiler JSON per run. Supports `--sf`, `--runs`, `--agg`, `--col`, `--where`, `--url`, `--out-dir`. Uses `profile:timings` and `optimized-logical-plan:true`. |
| `plaque/plaque_experiment.sh` | Shell script: runs SF 10/30/100 × intervals {off, 1, 100, 5000, MAX_INT} × 5 runs. Outputs CSV. |
| `plaque/run_sf50.sh` | Shell script: SF 50 specific, same intervals × 5 runs. |
| `plaque/run_sf100.sh` | Shell script: SF 100 specific, same intervals × 5 runs. |

### Raw Data Files

| File | Contents |
|---|---|
| `plaque/plaque_results.csv` | SF 10 (5 runs × 5 configs) + SF 30 baseline (5 runs) |
| `plaque/plaque_results_sf50.csv` | SF 50 (5 runs × 5 configs) |
| `plaque/plaque_results_sf100.csv` | SF 100 (5 runs × 5 configs) |
| `plaque/plaque_experiment_results.csv` | Same as plaque_results.csv (SF 10 + SF 30) |
| `plaque/sf100_run.log` | Full log of SF 100 runs including warmup |


## Design Documents

| Document | Path | Description |
|---|---|---|
| M1 Original | `plaque/PLAQUE_M1.md` | First M1 design (superseded by refactor) |
| M1 Refactor | `plaque/PLAQUE_M1_REFACTOR.md` | Decoupled filter (reader) from aggregate (writer) |
| M1 Standalone | `plaque/PLAQUE_M1_STANDALONE.md` | Comprehensive M1 spec with code snippets, correctness argument, all 11 components |
| M2 Design | `plaque/PLAQUE_M2.md` | Full M2 spec: NC-level registry, two-layer propagation, messaging, thread safety, config plumbing |
| Results | `plaque/PLAQUE_RESULTS.md` | Experiment results (SF 10 and SF 50 filled in; SF 100 marked pending but data exists in CSV) |


## Profiler Fix (June 28)

The AsterixDB REST API was not returning per-operator `profile:timings` data for synchronous (IMMEDIATE delivery) queries. Root cause: in `QueryTranslator.java` line 5384, the `updateJobStats()` call was commented out in the IMMEDIATE delivery path. The DEFERRED path had it enabled. Fix: uncommented `updateJobStats(id, stats, metadataProvider.getResultSetId(), clientRequest)` in the IMMEDIATE case block. File: `asterix-app/src/main/java/org/apache/asterix/app/translator/QueryTranslator.java`.


## Deep Dive: Execution Timeline (June 28)

Profiled runs with `"profile":"timings"` revealed the full execution timeline for the join query.

### Query Execution Phases

```
Phase 1 (0 - 35s):    Orders scan → filter by date → hash-partition-exchange → Join build
Phase 2 (35s onward): Lineitem scan + Join probe start SIMULTANEOUSLY
                       Lineitem scan sends tuples through hash-partition-exchange
                       Join probe receives tuples, probes hash table, feeds aggregate
Phase 3 (~250s):       Lineitem scan completes (all 300M tuples sent)
Phase 4 (~350s):       Join probe completes (processes remaining buffered tuples)
```

Key finding: **Lineitem scan does NOT start with Orders scan.** It starts at ~35s after Orders build completes. The scan and probe then overlap for ~215s.

### Why Strong Filter Failed at SF50 with 8MB Join Memory

The `OptimizedHybridHashJoin` is a **hybrid** hash join. With `compiler.joinmemory=8MB` (256 frames at 32KB), the Orders build side (~8.5M filtered tuples per partition at SF50) doesn't fit in memory. The join **spills**: it partitions build tuples to disk.

When the build spills, the probe behavior changes fundamentally. In `OptimizedHybridHashJoin.probe()` (line 498):

```java
if (isBuildRelAllInMemory()) {
    // ALL in memory: probe tuples flow through hash table → output → aggregate immediately
    inMemJoiner.join(i, writer);
} else {
    // SPILLED: probe tuples for spilled partitions are BUFFERED TO DISK
    if (spilledStatus.get(pid)) {
        processTupleProbePhase(i, pid);  // writes to disk, NO output
    } else {
        inMemJoiner.join(i, writer);    // only resident partitions emit
    }
}
```

**When spilled, the aggregate doesn't receive tuples until the spilled partitions are processed later.** The PLAQUE filter on the scan side checks `thresholdState.isInitialized()` — but the threshold is never initialized because the aggregate never fires during the scan window. Filter stats confirmed: `thresholdUninitTuples=75,004,182` (ALL tuples), `thresholdInitialized=false`.

At SF10, the build side fits in 8MB (or most partitions are resident), so `isBuildRelAllInMemory()` returns true or enough partitions are resident. Probe tuples flow directly to the aggregate, threshold initializes immediately, and the filter works during the 44s scan window → **2.76x speedup**.

### Weak Filter (Above Join) Results

With `push.through.join=false`, the filter sits between the join output and aggregate. It processes all 136M post-join tuples.

**SF50, 8MB join, cache-warm (R2 vs R2):**

| | Baseline | Weak PLAQUE |
|---|---|---|
| Exec time | 351.0s | 345.8s |
| Join Probe | 471,469 ms | 473,273 ms |
| Local Aggregate | 14,421 ms | 5.2 ms (2,773x less) |
| PLAQUE Filter | — | 7,987 ms |

The weak filter can't help the join (same 136M tuples flow through), but reduces aggregate from 14.4s to 5ms. The filter costs 8s but saves 14.4s → net 6.4s saving. Small overall impact because join+scan dominate.

### Strong Filter with 2GB Join Memory — The Fix

Changed `compiler.joinmemory=8MB` → `compiler.joinmemory=2GB` in `cc-main.conf`. The entire Orders build side fits in memory. No spilling → probe tuples stream directly to aggregate → threshold initializes immediately → filter works.

**SF10, 2GB join (cache-warm):**

| | Baseline | Strong PLAQUE |
|---|---|---|
| **Exec time** | **65.0s** | **23.6s (2.76x)** |
| Scan | 135,501 ms | 31,568 ms |
| Join Probe | 74,479 ms | 3,486 ms (21x less) |
| Local Aggregate | 2,892 ms | 25 ms |
| Filter tuples out | — | 834,245 (from 60M, 98.6% filtered) |
| Join tuples out | 27,328,478 | 188,367 (145x less) |

**SF50, 2GB join (cache-warm R2 vs R2):**

| | Baseline | Strong PLAQUE |
|---|---|---|
| **Exec time** | **243.2s** | **242.1s (~same)** |
| Scan | 611,373 ms | 622,704 ms |
| Join Probe | 425,009 ms | 482 ms (881x less) |
| Local Aggregate | 17,794 ms | 8 ms (2,224x less) |
| PLAQUE Filter | — | 22,116 ms |
| Filter tuples out | — | 93,126 (from 300M, 99.97% filtered) |
| Join tuples out | 136,638,480 | 20,336 (6,720x less) |

PLAQUE obliterates join and aggregate costs at SF50. But wall clock is the same because **the scan is I/O-bound** and dominates at ~610s summed across partitions (~153s wall clock). The scan reads all 300M lineitem tuples regardless of filtering — the filter sits after the scan in the pipeline, so scan I/O is unchanged.

### Summary of Findings

1. **Strong filter works when the hash join doesn't spill.** The spill/no-spill behavior of `OptimizedHybridHashJoin` determines whether the probe pipelines results immediately (enabling threshold learning) or buffers to disk (preventing it).
2. **At SF50 with 2GB join memory**, PLAQUE reduces join work by 881x and aggregate work by 2,224x, but wall clock doesn't improve because the lineitem scan is the bottleneck.
3. **The weak filter (above join)** provides a small but consistent benefit (~6s at SF50) regardless of join memory, by reducing aggregate work.
4. **For PLAQUE to improve wall clock on scan-dominated queries**, the filter would need to reduce scan I/O — e.g., by integrating with storage-level filtering or index access methods.

### 48GB Buffer Cache Experiment (data fully in memory)

Changed `storage.buffercache.size=48GB`. After one warmup run, buffer cache hit ratio = **100%** (2,678,370 page reads, all from cache). SF50 data (~46GB) fits entirely in memory. Scan becomes CPU-bound (tuple deserialization + pipeline), not I/O-bound.

**SF50, 2GB join, 48GB cache, single deep filter (no shallow), cache-warm:**

| | Baseline | Strong PLAQUE | Delta |
|---|---|---|---|
| **Exec time** | **142.8s** | **126.1s (1.13x)** | **-16.7s** |
| Scan | 217,301 ms | 192,436 ms | -24,865 ms (-11.4%) |
| Join Build | 26,830 ms | 26,333 ms | ~same |
| Join Probe | 366,350 ms | 146 ms | -366,204 ms (-99.96%) |
| Local Aggregate | 14,250 ms | 119 ms | -14,131 ms (-99.2%) |
| PLAQUE Filter | — | 22,155 ms | +22,155 ms (new) |
| Filter tuples out | — | 36,179 (from 300M) | 99.99% filtered |
| Cache hit ratio | 100% | 100% | both fully cached |

**Where the savings come from:**
- Total savings across partitions: -405,697 ms (scan + join + agg)
- New cost: +22,155 ms (filter)
- Net across partitions: -383,542 ms

**Why wall-clock improvement is only 16.7s, not 96s:**
Operators run in a pipeline (overlapping). In baseline, the join probe is the bottleneck (~92s/partition > scan's ~54s/partition). With PLAQUE, probe drops to near zero, so the scan becomes the new bottleneck (~48s/partition). The 16.7s saving = removing the probe bottleneck, limited by the scan taking over as the new bottleneck.

**Why 64GB cache wouldn't help further:**
Already at 100% hit ratio. The scan is purely CPU-bound: reading frames from buffer cache, deserializing tuples (field access), and pushing through the pipeline. No I/O to eliminate.

### Remaining Bottleneck: CPU-Bound Scan

The scan processes all 300M lineitem tuples regardless of the PLAQUE filter. The filter sits *after* the scan in the pipeline — 

To reduce scan time, we would need **storage-level filtering** — skipping tuples or pages *before* they enter the pipeline. Possible approaches:

1. **LSM B-Tree min/max page filters (zone maps):** AsterixDB's LSM storage could maintain per-page min/max metadata for columns. During scan, pages where `max(l_extendedprice) < threshold` could be skipped entirely. This requires:
   - Storing per-page column statistics in the LSM component metadata
   - A callback from the PLAQUE filter to the storage scan that provides the current threshold
   - The scan operator checking page-level stats before reading the page
   - Challenge: AsterixDB uses row-store (not columnar), so page-level column stats aren't naturally maintained

2. **Column-group / columnar storage:** If `l_extendedprice` were stored in a separate column group, the scan could read only that column for filtering, avoiding deserialization of the full 16-field lineitem record. AsterixDB doesn't natively support this, but columnar external formats (Parquet) do.

3. **Secondary index on the aggregated column:** A B-Tree index on `l_extendedprice` could directly seek to the maximum value. But this changes the query plan fundamentally (index-only scan instead of full scan + aggregate), which is a different optimization than PLAQUE.

4. **Predicate pushdown to scan operator:** Pass the PLAQUE threshold into the `DataSourceScanOperator` so it can skip tuples during deserialization. This would require a runtime-mutable predicate pushed into the scan, which AsterixDB's current scan infrastructure doesn't support (predicates are fixed at compile time).

5. **Adaptive page skipping (most practical for PLAQUE):** During the scan, after the filter drops N consecutive tuples from a page, assume the rest of the page is unlikely to pass and skip ahead. This is a heuristic (may miss valid tuples) but could work for sorted or clustered data. For the uniform `l_extendedprice` distribution this wouldn't help much, but for skewed data it could.

None of these are trivial to implement. For the research prototype, the current 1.13x speedup at SF50 (CPU-bound, all in cache) and 2.76x at SF10 demonstrate PLAQUE's value in reducing join and aggregate work. The scan bottleneck is an orthogonal problem.

### Propagation ON vs OFF Experiment (Single-Node)

Tested whether disabling Layer 2 cross-node propagation (`compiler.plaque.propagation=false`) reduces overhead on single-node deployment (48GB cache, 2GB join, strong filter).

**SF50, back-to-back R2:**

| | No Propagation | With Propagation |
|---|---|---|
| Exec time | 137.6s | 139.6s |
| Filter tuples out | 46,203 | 47,446 |
| Join Probe out | 21,161 | 21,700 |

**Conclusion:** No meaningful difference on single-node. Layer 1 (shared JVM state via `PlaqueThresholdRegistry`) already propagates thresholds to all partitions instantly. Layer 2 messaging is redundant on single-node — the few CC message roundtrips add negligible overhead. This knob only matters on multi-node clusters where each NC has a separate JVM.

### Columnar SF30 Experiment

Tested PLAQUE on columnar format tables (`Lineitem_column_30`, `Orders_column_30`). Columnar reads only needed columns (`l_orderkey`, `l_extendedprice`) instead of all 16 fields.

**SF30 columnar, 48GB cache, 2GB join, cache-warm:**

| | Baseline | Strong PLAQUE |
|---|---|---|
| Exec time | 145.8s | 136.3s (1.07x) |
| Scan | 358,864 ms | 339,146 ms |
| Join Probe | 208,561 ms | 301 ms (693x less) |
| Filter tuples out | — | 39,369 (from 180M) |

Same pattern as row format: PLAQUE eliminates join/aggregate work but scan dominates. AsterixDB's columnar format already has **per-page min/max zone maps** and can skip entire pages via `rangeFilterEvaluator` — but these filters are set at compile time. Integrating PLAQUE's runtime threshold into this page-level filter could skip pages where `max(col) < threshold`, reducing scan time. Plan documented at `plaque/PLAQUE_M3_COLUMNAR_PAGE_FILTER_PLAN.md`.

### Columnar Storage Background

**Mega Leaf Node Layout (one page group = one batch of ~15,000 tuples):**

```
┌──────────────────────────────────────────────────────────────────────┐
│ PAGE ZERO (header page)                                              │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │ Fixed Header: tuple count, next-leaf B-tree pointer             │ │
│  ├─────────────────────────────────────────────────────────────────┤ │
│  │ Column Offsets: [col0_offset, col1_offset, ... col15_offset]    │ │
│  ├─────────────────────────────────────────────────────────────────┤ │
│  │ Min/Max Zone Maps: 16 bytes per column (normalized min + max)   │ │
│  │   col0: [min_long, max_long]  ← l_orderkey                     │ │
│  │   col1: [min_long, max_long]  ← l_partkey                      │ │
│  │   col2: [min_long, max_long]  ← l_suppkey                      │ │
│  │   ...                                                           │ │
│  │   col5: [min_long, max_long]  ← l_extendedprice                │ │
│  │   ...                                                           │ │
│  ├─────────────────────────────────────────────────────────────────┤ │
│  │ Primary Key Columns (compressed): l_orderkey, l_linenumber      │ │
│  │   → Decompression walks all ~15K tuples                         │ │
│  └─────────────────────────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────────────────────────┤
│ PAGE 1: Non-key column data (e.g., l_suppkey, compressed)            │
├──────────────────────────────────────────────────────────────────────┤
│ PAGE 2: Non-key column data (e.g., l_extendedprice, compressed)      │
├──────────────────────────────────────────────────────────────────────┤
│ PAGE 3+: More non-key columns...                                     │
└──────────────────────────────────────────────────────────────────────┘
```

**"Pin page"** = request page from buffer cache. If cached (warm) → instant pointer. If not → disk read. For warm scans (100% cache hit), pinning is essentially free.

**Normal AsterixDB columnar scan flow (WITHOUT PLAQUE), per mega leaf node:**

```
                    ┌───────────────────────────────┐
                    │  Pin Page Zero                 │
                    │  (read header + zone maps)     │
                    └───────────────┬───────────────┘
                                    │
                                    ▼
                    ┌───────────────────────────────┐
                    │  Evaluate Page-Level Filter    │
                    │  (compare page's stored min/max│
                    │   against query WHERE clause)  │
                    └───────────┬───────────────────┘
                                │
              ┌─────────────────┴─────────────────┐
              │                                   │
      PASSES FILTER                       REJECTED BY FILTER
              │                                   │
              ▼                                   ▼
┌───────────────────────────┐     ┌───────────────────────────┐
│  Decompress Primary Keys  │     │  Decompress Primary Keys  │
│  (EXPENSIVE — ~15K tuples)│     │  (STILL REQUIRED for      │
└─────────────┬─────────────┘     │   multi-component merge)  │
              │                   └─────────────┬─────────────┘
              ▼                                 │
┌───────────────────────────┐                   ▼
│  Decompress Filter Columns│     ┌───────────────────────────┐
│  (WHERE clause columns)   │     │  Walk PKs for merge       │
└─────────────┬─────────────┘     │  positioning, then skip   │
                                  │  No columns decompressed   │
                                  │  No tuples emitted         │
                                  └───────────────────────────┘
              │
              ▼
┌───────────────────────────┐
│  Row-Level Filter         │
│  (exact per-tuple check)  │
└─────────────┬─────────────┘
              │
              ▼
┌───────────────────────────┐
│  Decompress Output Columns│
│  Assemble + emit tuples   │
└───────────────────────────┘
```

**Key observation:** Both paths decompress primary keys. This is required for multi-component merge correctness but is the dominant per-page cost for cached data.

**PLAQUE physical page skip flow (single-component scan):**

```
                    ┌───────────────────────────────┐
                    │  Pin Page Zero                 │
                    │  (read header + zone maps)     │
                    └───────────────┬───────────────┘
                                    │
                                    ▼
                    ┌───────────────────────────────┐
                    │  Evaluate PLAQUE Page Filter   │
                    │  (runtime threshold vs page    │
                    │   max from zone map)           │
                    └───────────┬─────────────��─────┘
                                │
              ┌─────────────────┴─────────────────┐
              │                                   │
      PASSES FILTER                    REJECTED BY FILTER
      (~1% of pages)                   (~99% of pages)
              │                                   │
              ▼                                   ▼
┌───────────────────────────┐     ┌───────────────────────────┐
│  Decompress Primary Keys  │     │  *** PHYSICAL SKIP ***    │
│  Decompress Columns       │     │  No PK decompression      │
│  Row-Level Filter         │     │  No column decompression  │
│  Assemble + emit tuples   │     │  No tuple emission        │
└───────────────────────────┘     │  Advance B-tree pointer   │
                                  │  to next mega leaf node    │
                                  └───────────────────────────┘
```

**Why physical skip is safe only for single-component:** In a multi-component scan, the merge cursor needs PKs from every component to know which row version is live (newer wins, tombstones suppress). Skipping PKs would emit stale/deleted rows. Single-component = one sorted file, no duplicates, no tombstones, no reconciliation needed.

**When is a table single-component?** After bulk load, after full compaction, or any read-heavy analytical table that isn't actively being updated — the typical PLAQUE target workload.

### Physical Page Skip (July 2-3) — THE BREAKTHROUGH

Found and fixed the missing piece: when a page is filtered out, `newPage()` was still decompressing primary keys (the dominant per-page cost). Fixed by checking the page filter IN `newPage()` before PK decompression, and looping in `fetchNextLeafPage()` to skip consecutive filtered pages.

Safety: only enabled for single-component scans (`operationalComponents.size() == 1`), where no multi-component merge reconciliation needs PKs.

Also found AsterixDB bug: `DoubleColumnFilterWriter.reset()` has swapped initial values (`min=Double.MIN_VALUE, max=Double.MAX_VALUE` instead of vice versa), so page-level min/max metadata for doubles is always wrong. Tested with `l_suppkey` (bigint) which has correct filter metadata via `LongColumnFilterWriter`.

**Results — `SELECT MAX(l.l_suppkey) FROM Lineitem_column_SF l, Orders_column_SF o WHERE l.l_orderkey = o.o_orderkey`:**

```
┌─────────────────┬─────────────┬─────────────────────────────┬───────────────┐
│     SF 10       │  Baseline   │ PLAQUE + Physical Page Skip │   Speedup     │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Exec time       │ 38.1s       │ 9.1s                        │ 4.20x         │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Scan            │ 84,565 ms   │ 18,888 ms                   │ 78% less      │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Scan tuples out │ 74,986,052  │ 19,627,647                  │ 74% fewer     │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Join            │ 72,824 ms   │ 57 ms                       │ 1,277x less   │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Join tuples out │ 59,986,052  │ 33,795                      │ 1,775x fewer  │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Aggregate       │ 6,459 ms    │ 8 ms                        │ 807x less     │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ PLAQUE Filter   │ —           │ 351 ms                      │ —             │
└─────────────────┴─────────────┴─────────────────────────────┴───────────────┘

┌─────────────────┬─────────────┬─────────────────────────────┬───────────────┐
│     SF 30       │  Baseline   │ PLAQUE + Physical Page Skip │   Speedup     │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Exec time       │ 117.1s      │ 24.6s                       │ 4.76x         │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Scan            │ 252,482 ms  │ 51,552 ms                   │ 80% less      │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Scan tuples out │ 224,998,372 │ 56,868,376                  │ 75% fewer     │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Join            │ 257,076 ms  │ 188 ms                      │ 1,368x less   │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Join tuples out │ 179,998,372 │ 35,514                      │ 5,069x fewer  │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Aggregate       │ 18,983 ms   │ 8 ms                        │ 2,373x less   │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ PLAQUE Filter   │ —           │ 929 ms                      │ —             │
└─────────────────┴─────────────┴─────────────────────────────┴───────────────┘
```

```
┌─────────────────┬─────────────┬─────────────────────────────┬───────────────┐
│     SF 50       │  Baseline   │ PLAQUE + Physical Page Skip │   Speedup     │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Exec time       │ 194.1s      │ 37.9s                       │ 5.11x         │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Scan            │ 430,150 ms  │ 77,906 ms                   │ 82% less      │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Scan tuples out │ 375,005,811 │ 87,848,111                  │ 77% fewer     │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Join            │ 444,641 ms  │ 103 ms                      │ 4,317x less   │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Join tuples out │ 300,005,811 │ 34,096                      │ 8,799x fewer  │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ Aggregate       │ 31,246 ms   │ 11 ms                       │ 2,840x less   │
├─────────────────┼─────────────┼─────────────────────────────┼───────────────┤
│ PLAQUE Filter   │ —           │ 918 ms                      │ —             │
└─────────────────┴─────────────┴─────────────────────────────┴───────────────┘
```

```
┌─────────────────┬──────────────┬─────────────────────────────┬───────────────┐
│     SF 100      │  Baseline    │ PLAQUE + Physical Page Skip │   Speedup     │
├─────────────────┼──────────────┼─────────────────────────────┼───────────────┤
│ Exec time       │ 380.9s       │ 71.2s                       │ 5.35x         │
├─────────────────┼──────────────┼─────────────────────────────┼───────────────┤
│ Scan            │ 832,341 ms   │ 134,209 ms                  │ 84% less      │
├─────────────────┼──────────────┼─────────────────────────────┼───────────────┤
│ Scan tuples out │ 750,037,902  │ 159,275,183                 │ 79% fewer     │
├─────────────────┼──────────────┼─────────────────────────────┼───────────────┤
│ Join            │ 1,000,514 ms │ 286 ms                      │ 3,498x less   │
├─────────────────┼──────────────┼─────────────────────────────┼───────────────┤
│ Join tuples out │ 600,037,902  │ 36,934                      │ 16,245x fewer │
├─────────────────┼──────────────┼─────────────────────────────┼───────────────┤
│ Aggregate       │ 60,790 ms    │ 133 ms                      │ 457x less     │
├─────────────────┼──────────────┼─────────────────────────────┼───────────────┤
│ PLAQUE Filter   │ —            │ 732 ms                      │ —             │
└─────────────────┴──────────────┴─────────────────────────────┴───────────────┘
```

Note: SF100 runs with 32GB buffer cache (reduced from 48GB to avoid OOM with 64GB heap). Data not fully cached — `bufferCacheHitRatio` < 100%. Despite this, speedup is the highest across all scale factors.

**Summary across scale factors (columnar, `MAX(l_suppkey)` join query):**

| Scale Factor | Baseline | PLAQUE | Speedup |
|---|---|---|---|
| SF 10 (60M rows) | 38.1s | 9.1s | **4.20x** |
| SF 30 (180M rows) | 117.1s | 24.6s | **4.76x** |
| SF 50 (300M rows) | 194.1s | 37.9s | **5.11x** |
| SF 100 (600M rows) | 380.9s | 71.2s | **5.35x** |

Speedup consistently increases with scale factor — the larger the dataset, the higher the fraction of pages that can be physically skipped.

### M1 Results: Single-Table `SELECT MAX(l_suppkey) FROM Lineitem_column_SF`

No join — filter and aggregate are micro-ops in the same pipeline. Threshold initializes on the first tuple, physical page skip kicks in immediately.

**SF 10:**
```
┌─────────────┬─────────────┬───────────┬─────────────┐
│             │  Baseline   │  PLAQUE   │   Speedup   │
├─────────────┼─────────────┼───────────┼─────────────┤
│ Exec time   │ 28.7s       │ 2.48s     │ 11.6x       │
├─────────────┼─────────────┼───────────┼─────────────┤
│ Scan        │ 69,525 ms   │ 6,127 ms  │ 11x less    │
├─────────────┼─────────────┼───────────┼─────────────┤
│ Scan tuples │ 59,986,052  │ 5,238,873 │ 91.3% fewer │
├─────────────┼─────────────┼───────────┼─────────────┤
│ Aggregate   │ 6,245 ms    │ 4 ms      │ 1,561x less │
└─────────────┴─────────────┴───────────┴─────────────┘
```

**SF 30:**
```
┌─────────────┬─────────────┬───────────┬─────────────┐
│             │  Baseline   │  PLAQUE   │   Speedup   │
├─────────────┼─────────────┼───────────┼─────────────┤
│ Exec time   │ 82.0s       │ 0.89s     │ 92x         │
├─────────────┼─────────────┼───────────┼─────────────┤
│ Scan        │ 209,747 ms  │ 1,751 ms  │ 120x less   │
├─────────────┼─────────────┼───────────┼─────────────┤
│ Scan tuples │ 179,998,372 │ 1,019,923 │ 99.4% fewer │
├─────────────┼─────────────┼───────────┼─────────────┤
│ Aggregate   │ 17,685 ms   │ 3 ms      │ 5,895x less │
└─────────────┴─────────────┴───────────┴─────────────┘
```

**SF 50:**
```
┌─────────────┬─────────────┬────────────┬─────────────┐
│             │  Baseline   │  PLAQUE    │   Speedup   │
├─────────────┼─────────────┼────────────┼─────────────┤
│ Exec time   │ 140.0s      │ 5.90s      │ 23.7x       │
├─────────────┼─────────────┼────────────┼─────────────┤
│ Scan        │ 352,572 ms  │ 15,879 ms  │ 22x less    │
├─────────────┼─────────────┼────────────┼─────────────┤
│ Scan tuples │ 300,005,811 │ 12,217,481 │ 95.9% fewer │
├─────────────┼─────────────┼────────────┼─────────────┤
│ Aggregate   │ 31,042 ms   │ 4 ms       │ 7,760x less │
└─────────────┴─────────────┴────────────┴─────────────┘
```

**SF 100:**
```
┌─────────────┬─────────────┬────────────┬─────────────┐
│             │  Baseline   │  PLAQUE    │   Speedup   │
├─────────────┼─────────────┼────────────┼─────────────┤
│ Exec time   │ 278.2s      │ 6.07s      │ 45.8x       │
├─────────────┼─────────────┼────────────┼─────────────┤
│ Scan        │ 702,873 ms  │ 15,493 ms  │ 45x less    │
├─────────────┼─────────────┼────────────┼─────────────┤
│ Scan tuples │ 600,037,902 │ 12,040,253 │ 98.0% fewer │
├─────────────┼─────────────┼────────────┼─────────────┤
│ Aggregate   │ 61,787 ms   │ 3 ms       │ 20,596x less│
└─────────────┴─────────────┴────────────┴─────────────┘
```

**M1 summary:**

| Scale Factor | Baseline | PLAQUE | Speedup |
|---|---|---|---|
| SF 10 (60M rows) | 28.7s | 2.48s | **11.6x** |
| SF 30 (180M rows) | 82.0s | 0.89s | **92x** |
| SF 50 (300M rows) | 140.0s | 5.90s | **23.7x** |
| SF 100 (600M rows) | 278.2s | 6.07s | **45.8x** |

M1 speedups are dramatically higher than M2 because there is no join pipeline delay — the threshold initializes immediately and pages start getting skipped from the beginning. SF30 achieves 92x because the data is fully cached (48GB cache, SF30 fits); SF50/100 are not fully cached (32GB cache) so I/O becomes a factor, but still achieve 24-46x.

### Profiled Run Files

| File | Description |
|---|---|
| `sf10_baseline_profiled.json` | SF10 baseline, 8MB join |
| `sf10_strong_plaque_profiled.json` | SF10 strong PLAQUE, 8MB join |
| `sf50_baseline_profiled.json` | SF50 baseline, 8MB join, run 1 |
| `sf50_baseline_profiled_r2.json` | SF50 baseline, 8MB join, run 2 |
| `sf50_weak_plaque_profiled.json` | SF50 weak PLAQUE, 8MB join, run 1 |
| `sf50_weak_plaque_profiled_r2.json` | SF50 weak PLAQUE, 8MB join, run 2 |
| `sf50_strong_plaque_profiled.json` | SF50 strong PLAQUE, 8MB join (filter doesn't work) |
| `sf50_baseline_2gb_join.json` | SF50 baseline, 2GB join |
| `sf50_baseline_2gb_join_r2.json` | SF50 baseline, 2GB join, run 2 |
| `sf50_strong_plaque_2gb_join.json` | SF50 strong PLAQUE, 2GB join |
| `sf50_strong_plaque_2gb_join_r2.json` | SF50 strong PLAQUE, 2GB join, run 2 |
| `sf50_baseline_48gb_cache_warmup.json` | SF50 warmup run, 48GB cache (hit ratio 0%) |
| `sf50_baseline_48gb_cache.json` | SF50 baseline, 48GB cache, 2GB join (hit ratio 100%) |
| `sf50_strong_plaque_48gb_cache.json` | SF50 strong PLAQUE, 48GB cache, 2GB join, deep filter only (hit ratio 100%) |
| `sf50_strong_plaque_no_propagation.json` | SF50 strong PLAQUE, propagation OFF, run 1 |
| `sf50_strong_noprop_r2.json` | SF50 strong PLAQUE, propagation OFF, run 2 |
| `sf50_strong_prop_r2.json` | SF50 strong PLAQUE, propagation ON, run 2 |
| `sf30_col_baseline.json` | SF30 columnar baseline |
| `sf30_col_strong_plaque.json` | SF30 columnar strong PLAQUE |
