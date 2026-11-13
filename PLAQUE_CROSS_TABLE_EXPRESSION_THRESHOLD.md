# PLAQUE Cross-Table Expression Threshold

## Target Query

```sql
SELECT MAX(l_extendedprice - ps_supplycost)
FROM Lineitem l, Partsupp ps
WHERE l.l_partkey = ps.ps_partkey AND l.l_suppkey = ps.ps_suppkey;
```

## The Problem

The aggregate computes `MAX(l_extendedprice - ps_supplycost)`. Standard PLAQUE learns a running threshold `t` from the aggregate and pushes `value >= t` to the scan. But the expression `l_extendedprice - ps_supplycost` spans both tables — it can't be pushed to the Lineitem scan because the scan doesn't know `ps_supplycost`.

## The Mechanism

Close the expression against a build-side bound.

The build side (Partsupp) is fully materialized before the probe (Lineitem) starts. During build, each NC computes `c_min = MIN(ps_supplycost)` over its local Partsupp tuples. This is the smallest cost any lineitem on that NC could be paired with.

For a lineitem to contribute to the MAX, it needs `l_extendedprice - ps_supplycost > t` for at least one partner. The best case is when paired with the cheapest partner: `l_extendedprice - c_min`. If even that doesn't beat `t`:

```
l_extendedprice - c_min <= t
→ l_extendedprice <= t + c_min
```

So the filter keeps only tuples where `l_extendedprice > t + c_min`.

`t` tightens over time (running MAX). `c_min` is fixed after build. The combined bound `t + c_min` only increases → monotonic → safe. A stale `t` or loose `c_min` only under-prunes, never mis-prunes.

## Two Filter Levels

1. **Exchange filter (per-partition):** Uses `c_min[dest_partition]` — the min ps_supplycost on the specific destination NC. Tighter bound because it knows where the tuple is going. Applied inside HASH_PARTITION_EXCHANGE after computing the hash but before sending.

2. **Page filter (global):** Uses `global_c_min = min(c_min[0..N-1])` — the absolute minimum across all NCs. Looser bound because at scan time we don't know the destination. Applied during columnar page scan to skip entire pages.


## Experimental Results

### Setup
- TPC-H SF10: Lineitem (~60M tuples), Partsupp (~8M tuples)
- 4 NCs on single machine, columnar storage
- Buffer cache: 16GB, data fully cached after warmup

### Results (2026-07-18, warm cache)

| Metric | Baseline | PLAQUE cross-expr | Improvement |
|--------|----------|-------------------|-------------|
| **Elapsed time** | 55.75s | 49.34s | **1.13x** |
| Result | 104869.16 | 104869.16 (correct) | — |
| Processed objects | 67,986,052 | 67,986,052 | — |
| Buffer cache hit ratio | 100% | 100% | — |
| Page reads | 158,479 | 158,479 | — |
| **Tuples joined** | 59,986,052 | 31,683 | **1,893x fewer** |
| Exchange filter rate | — | **99.94%** | — |
| Threshold updates | — | 12-22 per NC | — |

### Operator-Level Profiling (summed across 4 NCs)

| Operator | Baseline | PLAQUE | Speedup |
|----------|----------|--------|---------|
| Index Search (scan) | 92,156 ms | 90,541 ms | ~1x |
| Hash Join: Build | 7,941 ms | 9,048 ms | ~1x |
| **Hash Join: Probe & Join** | **146,618 ms** | **329 ms** | **445x** |
| numeric-subtract eval | 25,170 ms | 21 ms | 1,198x |
| Local aggregate | 6,677 ms | 14 ms | 477x |
| Field access (Lineitem) | 40,485 ms | 39,787 ms | ~1x |
| stream-project | 23,593 ms | 15,774 ms | 1.5x |

Profile JSONs: `plaque/crossexpr_baseline_sf10_4nc_warm.json`, `plaque/crossexpr_plaque_sf10_4nc_warm.json`

### Results with Reduced Join Memory (8MB, warm cache)

| Metric | Baseline | PLAQUE | Improvement |
|--------|----------|--------|-------------|
| **Elapsed** | 85.49s | 82.42s | **1.04x** (negligible) |
| **Hash Join: Probe & Join** | 142,385 ms | 136,659 ms | ~1x |
| **Tuples joined** | 59,986,052 | 59,986,052 | none |
| Exchange filter rate | — | **0%** (all uninit) | — |

**Root cause: heavy spilling defeats the filter.**

With 8MB join memory, the hash join spills the entire build side and all probe tuples to disk. The join cannot produce ANY output until it reloads spilled partitions. The data flow pipeline is:

```
Lineitem scan → Exchange (filter here) → Join (spills everything) → Aggregate (produces t)
         ↑                                                                      │
         └── threshold t never reaches here because join blocks all output ─────┘
```

NC logs confirm: `uninit=14,997,768` — all 60M probe tuples passed through the exchange filter before threshold `t` was ever initialized. The first threshold update message arrived AFTER the exchange had already closed.

The build observer correctly published `c_min = 1.0` (available before probe starts), but without `t` from the aggregate, the filter condition `l_extendedprice > t + c_min` cannot be evaluated.

**This is a fundamental limitation:** the cross-expression filter only helps when the join produces output concurrently with receiving probes (i.e., when the build side fits mostly in memory). The page-level filter has the same limitation — it also requires `t` to be initialized before the scan completes.

**Mitigation strategies (not yet implemented):**
1. Persistent/cached threshold from previous query execution
2. Sampling pre-pass to estimate `t` before the main query
3. Two-pass execution: quick first pass with small sample → get approximate `t` → second pass with filter

### Results with 128MB Join Memory (warm cache)

| Metric | Baseline | PLAQUE | Improvement |
|--------|----------|--------|-------------|
| **Elapsed** | 57.40s | 43.63s | **1.32x** |
| **Hash Join: Probe & Join** | 152,729 ms | 1,861 ms | **82x** |
| **Tuples joined** | 59,986,052 | 53,107 | **1,130x fewer** |
| **Subtract eval** | 25,202 ms | 26 ms | **978x** |
| Index Search (scan) | 94,305 ms | 87,533 ms | ~1x |

At 128MB, enough of the build fits in memory for the join to produce output early → threshold initializes → filter activates. Performance similar to default memory.

### Results with Page Filter — MAX(l_suppkey - ps_availqty) (bigint, warm cache)

| Metric | Baseline | PLAQUE | Improvement |
|--------|----------|--------|-------------|
| **Elapsed** | 53.99s | 20.45s | **2.64x** |
| **Hash Join: Probe & Join** | 139,569 ms | 175 ms | **796x** |
| **Tuples joined** | 59,986,052 | 56,902 | **1,054x fewer** |
| **Index Search (scan)** | 83,604 ms | 40,391 ms | **2.1x** |
| **Page reads** | 121,154 | 80,548 | **1.5x fewer** |
| **Processed objects** | 67,986,052 | 35,165,393 | **2x fewer** |

Page filter skip rate: ~47-55% (tightens as threshold converges). l_suppkey range [1, 100000], bound converges to 99976. Pages with max(l_suppkey) < 99976 are skipped entirely.

Exchange filter rate: 99.77% of surviving tuples dropped before join.

Note: Page filter only works for bigint columns on existing data. Double columns (l_extendedprice) require data reload due to known DoubleColumnFilterWriter zone map bug.

### Scalability: MAX(l_suppkey - ps_availqty) across scale factors (page filter, warm cache)

| SF | Baseline | PLAQUE | Speedup | Tuples Joined (B→P) | Join Reduction | Page Reads (B→P) | Scan Speedup |
|----|----------|--------|---------|----------------------|----------------|------------------|--------------|
| 10 | 54.0s | 20.5s | **2.64x** | 60M → 57K | 1,054x | 121K → 81K | 2.1x |
| 30 | 191.0s | 37.6s | **5.08x** | 180M → 81K | 2,228x | 363K → 190K | 5.0x |
| 50 | 344.8s | 51.0s | **6.76x** | 300M → 79K | 3,774x | 606K → 294K | 6.7x |
| 100 | 756.6s | 81.2s | **9.31x** | 600M → 77K | 7,810x | 1.2M → 533K | 9.6x |

All results verified correct against baseline.

**Key insight:** Speedup scales superlinearly with data size (2.64x → 9.31x from SF10 to SF100). The number of tuples that survive filtering is nearly constant (~57-81K) regardless of scale factor — it depends only on how many distinct l_suppkey values exceed the threshold, not on total data size. Meanwhile baseline cost grows linearly with data.

### Analysis

The exchange filter eliminates 99.94% of probe tuples → join sees only 31K tuples instead of 60M. This collapses the join probe time from 146s to 0.3s (summed across NCs).

Wall-clock improvement is 1.13x (55.75s → 49.34s) because:
- The Lineitem scan (~90s summed = ~23s wall-clock on 4 parallel NCs) is the bottleneck
- All 60M tuples must still be read from columnar storage to extract `l_extendedprice` for the filter check
- On shared-memory (single machine), the "network" savings from dropping tuples are minimal

Expected to improve further with:
1. **Page-level filter:** Skip entire Lineitem pages where `pageMax(l_extendedprice) <= t + global_c_min`
2. **Multi-node cluster:** Network I/O savings become significant when tuples cross machines
3. **Larger data / tighter threshold:** Higher selectivity means more scan pages can be skipped

### Build Observer Stats

```
PLAQUE_BUILD_OBSERVER [partition=0] observed=2,000,035, min=1.0000
PLAQUE_BUILD_OBSERVER [partition=1] observed=1,998,995, min=1.0000
PLAQUE_BUILD_OBSERVER [partition=2] observed=2,000,855, min=1.0000
PLAQUE_BUILD_OBSERVER [partition=3] observed=2,000,115, min=1.0000
```

All partitions found `min(ps_supplycost) = 1.00` (TPC-H data has supplycost ranging from 1.00 to 1000.00).

### Exchange Filter Stats

```
PLAQUE_EXCHANGE_FILTER [NC1] total=14,999,912, filtered=14,991,575, uninit=7,610, filterRate=99.9450%
PLAQUE_EXCHANGE_FILTER [NC2] total=14,993,305, filtered=14,981,234, uninit=11,415, filterRate=99.9195%
PLAQUE_EXCHANGE_FILTER [NC3] total=14,995,067, filtered=14,982,972, uninit=11,415, filterRate=99.9193%
PLAQUE_EXCHANGE_FILTER [NC4] total=14,997,768, filtered=14,989,521, uninit=7,610, filterRate=99.9444%
```

- ~14K uninit tuples per NC: tuples that arrived before the threshold was initialized (first batch)
- After initialization: nearly all tuples filtered because `MAX(l_extendedprice - ps_supplycost) = 104,869.16` and most `l_extendedprice` values are well below `104,869.16 + 1.00 = 104,870.16`


## Implementation Architecture

### Data Flow

```
┌──────────────────────────────────────────────────────────────────────┐
│                        COMPILE TIME                                   │
│                                                                      │
│  PlaqueRewriteRule.rewriteCrossTableExpression()                     │
│    → Detects MAX(subtract($$68, $$69)) above INNERJOIN               │
│    → Identifies: probeVar=$$68 (l_extendedprice), buildVar=$$69      │
│    → Annotates aggregate with PLAQUE_AGGREGATE + threshold handle    │
│    → Annotates join with cross-expr metadata (all params)            │
│                                                                      │
│  PlaqueCrossExprJobGenRule (prepareForJobGen phase)                   │
│    → Reads annotations from join                                     │
│    → Replaces join POP: HybridHashJoinPOperator →                    │
│         PlaqueCrossExprHashJoinPOperator (injects build observer)     │
│    → Replaces probe exchange POP: HashPartitionExchangePOperator →   │
│         PlaqueCrossExprExchangePOperator (injects filter factory)     │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│                         RUNTIME                                       │
│                                                                      │
│  Activity 0 (Build - Partsupp):                                      │
│    OptimizedHybridHashJoin.build()                                    │
│      → For each build tuple: observer.observe(accessor, i)           │
│      → Observer tracks running MIN(ps_supplycost)                    │
│    OptimizedHybridHashJoin.closeBuild()                              │
│      → observer.publishResult()                                      │
│      → Writes c_min to PlaqueBuildSideMinState in registry           │
│                                                                      │
│  ──── blocking edge (build completes before probe starts) ────       │
│                                                                      │
│  Activity 1 (Probe - Lineitem):                                      │
│    Lineitem scan → HASH_PARTITION_EXCHANGE                            │
│      PlaqueFilteringPartitionDataWriter.processTuple():               │
│        p = hash(l_partkey, l_suppkey) % numNCs                       │
│        c = buildSideState.getValue(p)  // c_min on dest NC           │
│        t = thresholdState.getThreshold()  // running MAX             │
│        if l_extendedprice > t + c:                                   │
│          send to NC p                                                │
│        else:                                                         │
│          DROP (can never beat current MAX)                            │
│                                                                      │
│    Surviving tuples → join → subtract → aggregate                    │
│      PlaqueLocalSqlMinMaxAggregateFunction:                          │
│        → Updates running MAX threshold t                             │
│        → Broadcasts via CC to all NCs                                │
└──────────────────────────────────────────────────────────────────────┘
```

### Two-Phase Rule Architecture

The implementation uses a **two-phase approach** because exchange operators don't exist during the consolidation phase:

**Phase 1: Consolidation (PlaqueRewriteRule)**
- Runs early in optimization, before physical operators are assigned
- Detects the pattern, computes all parameters
- Stores everything as annotations on the JOIN operator (exchanges don't exist yet)
- Also annotates the aggregate for the threshold observer

**Phase 2: PrepareForJobGen (PlaqueCrossExprJobGenRule)**
- Runs after `SetAsterixPhysicalOperatorsRule` and `EnforceStructuralPropertiesRule`
- Physical operators now exist on all operators
- Reads annotations from the join
- Replaces the join's physical operator with `PlaqueCrossExprHashJoinPOperator`
- Walks down join input 0 (probe side) to find the HASH_PARTITION_EXCHANGE
- Replaces that exchange's physical operator with `PlaqueCrossExprExchangePOperator`


## File Inventory

### New Files

| File | Layer | Role |
|------|-------|------|
| `PlaqueBuildSideMinState.java` | asterix-runtime/.../plaque/ | Per-NC min/max vector. Thread-safe (each NC writes own slot). Published once at build-close. |
| `IBuildSideObserver.java` | hyracks-dataflow-std/.../join/ | Interface: `observe(accessor, tupleIndex)`, `publishResult()` |
| `IBuildSideObserverFactory.java` | hyracks-dataflow-std/.../join/ | Serializable factory: `createObserver(ctx, partitionIdx)` |
| `PlaqueBuildSideObserverFactory.java` | asterix-runtime/.../plaque/ | Tracks running MIN/MAX of build column. Handles all numeric types (TINYINT through DOUBLE). |
| `IPlaqueExchangeFilter.java` | hyracks-dataflow-std/.../connectors/ | Interface: `passes(accessor, tupleIndex, destPartition)` |
| `IPlaqueExchangeFilterFactory.java` | hyracks-dataflow-std/.../connectors/ | Serializable factory: `createFilter(ctx)` |
| `PlaqueFilteringPartitionDataWriter.java` | hyracks-dataflow-std/.../connectors/ | Extends partition writer. After computing dest partition, checks filter before routing. |
| `PlaqueCrossExprExchangeFilterFactory.java` | asterix-runtime/.../plaque/ | Resolves threshold + build-side state, evaluates `probeValue > combine(t, c_min[p])` |
| `PlaqueCrossExprHashJoinPOperator.java` | asterix-algebra/.../physical/ | Extends `HybridHashJoinPOperator`. Overrides `contributeRuntimeOperator()` to inject build-side observer. |
| `PlaqueCrossExprExchangePOperator.java` | asterix-algebra/.../physical/ | Extends `HashPartitionExchangePOperator`. Overrides `createConnectorDescriptor()` to produce filtering connector. |
| `PlaqueCrossExprJobGenRule.java` | asterix-algebra/.../rules/ | Late-phase rule: reads annotations, replaces physical operators. |

### Modified Files

| File | Change |
|------|--------|
| `PlaqueThresholdRegistry.java` | Added `getOrCreateBuildSideMinState()`, `getBuildSideMinState()` |
| `OptimizedHybridHashJoin.java` | Added `IBuildSideObserver` field, `setBuildSideObserver()`, calls in `build()` and `closeBuild()` |
| `OptimizedHybridHashJoinOperatorDescriptor.java` | New constructor with `IBuildSideObserverFactory`, creates observer in `PartitionAndBuildActivityNode.open()` |
| `MToNPartitioningConnectorDescriptor.java` | Added `IPlaqueExchangeFilterFactory` field, new constructor, creates `PlaqueFilteringPartitionDataWriter` when factory non-null |
| `PlaqueRewriteRule.java` | Added `rewriteCrossTableExpression()` method |
| `RuleCollections.java` | Registered `PlaqueCrossExprJobGenRule` in `prepareForJobGenRewrites()` |
| `PhysicalOperatorTag.java` | (inherits from parent — no new tag needed, subclasses reuse HYBRID_HASH_JOIN / HASH_PARTITION_EXCHANGE tags) |


## Compiled Plan

Actual EXPLAIN output with `compiler.plaque.enabled = "true"`:

```
distribute result [$$58]
-- DISTRIBUTE_RESULT  |UNPARTITIONED|
  exchange
  -- ONE_TO_ONE_EXCHANGE  |UNPARTITIONED|
    project ([$$58])
    -- STREAM_PROJECT  |UNPARTITIONED|
      assign [$$58] <- [{"$1": $$67}]
      -- ASSIGN  |UNPARTITIONED|
        aggregate [$$67] <- [agg-global-sql-max($$72)]
        -- AGGREGATE  |UNPARTITIONED|
          exchange
          -- RANDOM_MERGE_EXCHANGE  |PARTITIONED|
            aggregate [$$72] <- [agg-local-sql-max($$56)]
            -- PLAQUE_AGGREGATE(MAX, plaque-crossexpr-t-$$73)  |PARTITIONED|
              project ([$$56])
              -- STREAM_PROJECT  |PARTITIONED|
                assign [$$56] <- [numeric-subtract($$68, $$69)]
                -- ASSIGN  |PARTITIONED|
                  project ([$$68, $$69])
                  -- STREAM_PROJECT  |PARTITIONED|
                    exchange
                    -- ONE_TO_ONE_EXCHANGE  |PARTITIONED|
                      join (and(eq($$63, $$62), eq($$65, $$61)))
                      -- HYBRID_HASH_JOIN [$$63, $$65][$$62, $$61]  |PARTITIONED|
                        exchange
                        -- HASH_PARTITION_EXCHANGE [$$63, $$65]  |PARTITIONED|
                          project ([$$68, $$63, $$65])
                          -- STREAM_PROJECT  |PARTITIONED|
                            assign [$$65, $$63, $$68] <- [$$l.getField(1), $$l.getField(2), $$l.getField(5)]
                            -- ASSIGN  |PARTITIONED|
                              data-scan <- Lineitem_column_10
                        exchange
                        -- HASH_PARTITION_EXCHANGE [$$62, $$61]  |PARTITIONED|
                          project ([$$69, $$62, $$61])
                          -- STREAM_PROJECT  |PARTITIONED|
                            assign [$$69] <- [$$ps.getField(3)]
                            -- ASSIGN  |PARTITIONED|
                              data-scan <- Partsupp_column_10
```

Note: `HYBRID_HASH_JOIN` and `HASH_PARTITION_EXCHANGE` display their parent class tags. The actual physical operators are `PlaqueCrossExprHashJoinPOperator` and `PlaqueCrossExprExchangePOperator` (confirmed by NC debug logs). The `PLAQUE_AGGREGATE` tag is visible because it has its own `PhysicalOperatorTag`.


## Soundness Argument

1. **`c_min[p]` is exact:** every build tuple is observed exactly once in `build()`, regardless of spilling. The min per NC is the true minimum supplycost on that NC.

2. **`t` only tightens:** the running MAX from the aggregate only increases. A stale `t` gives a lower bound → under-prunes → correct.

3. **`c_min` is immutable after publication:** build completes before probe starts (blocking edge). No races.

4. **The filter never mis-prunes:** if `l_extendedprice <= t + c_min[p]`, then for every partner `ps` on NC `p` with `ps_supplycost >= c_min[p]`: `l_extendedprice - ps_supplycost <= l_extendedprice - c_min[p] <= t`. The expression value can't exceed `t`. Safe to drop.

5. **Eventual delivery:** `t` is shared via the existing `PlaqueThresholdRegistry` (volatile reads). The build-side min vector is published once at build-close, before any probe tuple is processed. No synchronization beyond the blocking edge.


## Per-NC Partitioning

The HASH_PARTITION_EXCHANGE routes both sides to the same set of NCs using the same hash on the join keys `(partkey, suppkey)`. A lineitem with `(partkey=5, suppkey=3)` goes to the same NC as the partsupp with `(ps_partkey=5, ps_suppkey=3)`.

So `c_min[NC_p]` = min(ps_supplycost) over all Partsupp tuples on NC p. A lineitem routed to NC p can only join with Partsupp tuples on NC p. The per-NC bound is tight and correct.

N = `consumerPartitionCount` (number of NCs, e.g., 4). The vector is tiny — 4 doubles.

The join's INTERNAL `numOfPartitions` (Shapiro's formula for spill management) is irrelevant. It subdivides tuples WITHIN an NC for memory management, not for routing.


## Extensibility: General Binary Expressions

The mechanism generalizes to any monotonic binary expression `f(a, b)` where `a` is probe-side and `b` is build-side:

### Expression Table

For `MAX(f(a, b))` with running threshold `t`:

| Expression f(a,b) | Build observer tracks | Combine rule | Probe filter |
|---|---|---|---|
| `a - b` | `c = MIN(b)` | `bound = t + c` | `a > bound` |
| `a + b` | `c = MIN(b)` | `bound = t - c` | `a > bound` |
| `a * b` (b > 0) | `c = MAX(b)` | `bound = t / c` | `a > bound` |
| `a / b` (b > 0) | `c = MAX(b)` | `bound = t * c` | `a > bound` |
| `b - a` | `c = MAX(b)` | `bound = c - t` | `a < bound` |
| `b / a` (a > 0) | `c = MAX(b)` | `bound = c / t` | `a < bound` |

For `MIN(f(a, b))`: flip all directions (track MAX instead of MIN, etc.).

### Parameterization

The observer and filter are parameterized by:

1. **`trackMin`** (boolean): what to track on the build side — MIN or MAX of the build column
2. **`CombineOp`** (enum): how to combine threshold `t` with build bound `c` — ADD, SUBTRACT, MULTIPLY, DIVIDE
3. **`FilterDirection`** (enum): whether the probe filter is `a > bound` (GREATER) or `a < bound` (LESS)

These are determined at compile time from the expression structure and aggregate type. Carried as enum values in the physical operator and serialized to the filter factory.

Example for `MAX(a - b)`: trackMin=true, CombineOp=ADD, FilterDirection=GREATER
Example for `MAX(a + b)`: trackMin=true, CombineOp=SUBTRACT, FilterDirection=GREATER


## Numeric Type Handling

Both the build observer and exchange filter handle all AsterixDB numeric types:

| Type | Tag (byte) | Size | Extraction |
|------|-----------|------|------------|
| TINYINT | 1 | 2 bytes | 1 byte signed |
| SMALLINT | 2 | 3 bytes | 2 bytes signed big-endian |
| INTEGER | 3 | 5 bytes | 4 bytes signed big-endian |
| BIGINT | 4 | 9 bytes | 8 bytes signed big-endian |
| FLOAT | 11 | 5 bytes | 4 bytes IEEE 754 |
| DOUBLE | 12 | 9 bytes | 8 bytes IEEE 754 |

All values are converted to `double` for comparison. Unknown types are passed through (observer skips, filter passes the tuple).

**Initial bug:** The first implementation used type tag `0x05` (which is UINT8 in AsterixDB, not DOUBLE). AsterixDB's DOUBLE tag is `12` (0x0C). This caused the observer to see 2M tuples but never extract a value → `c_min` stayed at `Double.MAX_VALUE` → 0% filtering.


## Implementation Trip-Ups and Lessons

### 1. Exchange operators don't exist during consolidation phase

**Problem:** The initial approach tried to find and annotate the probe-side HASH_PARTITION_EXCHANGE during the `PlaqueRewriteRule` (consolidation phase). But exchanges are inserted later by `EnforceStructuralPropertiesRule`.

**Fix:** Two-phase approach. Phase 1 (PlaqueRewriteRule) stores all parameters as annotations on the JOIN. Phase 2 (PlaqueCrossExprJobGenRule, registered in `prepareForJobGenRewrites`) runs after exchanges exist and replaces the physical operators.

### 2. Physical operator check fails during consolidation

**Problem:** The rewrite rule checked `joinOp.getPhysicalOperator().getOperatorTag() == HYBRID_HASH_JOIN` but the join has no physical operator during consolidation.

**Fix:** Removed the check. Just detect INNERJOIN with an equi-condition. The late-phase rule verifies the physical operator is HYBRID_HASH_JOIN before replacing.

### 3. Variable mapping swap (probe vs build)

**Problem:** In AsterixDB's plan representation:
- Plan input 0 of the join = **probe** (Lineitem)
- Plan input 1 of the join = **build** (Partsupp)

But in the descriptor:
- `addSourceEdge(1, buildActivity, 0)` — descriptor input 1 feeds build
- `addSourceEdge(0, probeActivity, 0)` — descriptor input 0 feeds probe

The initial implementation confused these, assigning `probeVar` from the wrong side.

**Fix:** Use `VariableUtilities.getLiveVariables()` on each join input to determine which variable belongs to which side. Input 0's live variables → probe side, input 1's → build side.

### 4. PlaqueCrossExprJobGenRule walking wrong input for probe exchange

**Problem:** The rule walked `joinOp.getInputs().get(1)` looking for the probe exchange, but input 1 is the build side.

**Fix:** Walk `joinOp.getInputs().get(0)` for probe exchange (input 0 = probe in the plan).

### 5. Wrong AsterixDB type tag for DOUBLE

**Problem:** Used `0x05` (UINT8) instead of `12` (DOUBLE/0x0C). The build observer saw all tuples but never extracted values.

**Symptom:** `min=1.7976e+308` (Double.MAX_VALUE) in logs — the initial value was never updated.

**Fix:** Use correct type tags from `ATypeTag.java`: DOUBLE=12, BIGINT=4, FLOAT=11, INTEGER=3, SMALLINT=2, TINYINT=1.

### 6. SqlppCompilationProvider whitelist

**Problem:** New config parameters (e.g., `compiler.plaque.enabled`) must be added to `SqlppCompilationProvider.getCompilerOptions()` whitelist, otherwise they're silently ignored.

**Fix:** Add all PLAQUE config keys to the whitelist.


## Page-Level Filter (Future)

For page-level skip, use `globalMin = min(c_min[0..N-1])` — the loosest per-NC bound. A page spans tuples routed to all NCs, so the global min is the only safe bound.

Check: `pageMax(l_extendedprice) <= t + globalMin` → skip page (no tuple on this page can beat the threshold even with the cheapest possible partner).

**Status:** Not yet implemented for cross-expression. Would integrate with existing `PlaqueColumnFilterEvaluatorFactory` by passing the combined bound (`t + globalMin`) as the filter threshold against the `l_extendedprice` column's page zone map.


## Compile-Time Expression Decomposition

The rewrite rule detects the pattern by tracing from the aggregate:

1. Find local aggregate `agg-local-sql-max($$56)` → `$$56` is the filtered variable
2. Walk down to find ASSIGN defining `$$56`: `$$56 = numeric-subtract($$68, $$69)`
3. Recognize `numeric-subtract` as a decomposable binary operator
4. Continue walking to find INNERJOIN with equi-condition
5. Determine which variable comes from which join input using `VariableUtilities.getLiveVariables()`
6. Look up expression table: `MAX(a - b)` → trackMin=true, CombineOp=ADD, FilterDirection=GREATER
7. Generate unique handles for threshold and build-side state
8. Annotate both the aggregate and join with all parameters
9. The late-phase rule reads annotations and injects physical operators


## Configuration

Enable with:
```sql
SET `compiler.plaque.enabled` "true";
```

The cross-expression rewrite fires automatically when it detects `MAX/MIN(binary_expr)` above a hash join where the expression operands come from different join inputs.

No additional configuration needed. The `numPartitions` parameter (number of NCs) is currently hardcoded to 4 in the rewrite rule — should be derived from cluster config for production use.
