# PLAQUE Theta Join Filter — Experiment Log (July 11-12, 2026)

## Setup

- Single-node, 4 NCs (all in one JVM)
- 2GB join memory, 48GB buffer cache (from cc-main.conf)
- Columnar datasets: `Lineitem_column_10` (60M rows), `Orders_column_10` (15M rows), plus SF30/SF50
- Branch: `interactive-processing`

## Architecture Summary

The theta join filter reuses the same PLAQUE infrastructure as M1-M3 (MAX/MIN aggregate):

| Component | M1-M3 (aggregate) | Theta Join |
|---|---|---|
| **Observer** (writes threshold) | `PlaqueLocalSqlMinMaxAggregateFunction.onMinMaxChanged()` | `PlaqueThetaJoinMismatchWriter.onUnmatchedOuterTuple()` |
| **Tuple filter** (reads threshold) | `PlaqueFilterRuntime.passes()` | Same — reused |
| **Page filter** (reads threshold) | `PlaqueColumnFilterEvaluator.evaluate()` | Same — reused |
| **Shared state** | `PlaqueThresholdState` in `PlaqueThresholdRegistry` | Same — reused |
| **Physical page skip** | `AbstractColumnTupleReference.newPage()` | Same — reused |

New code for theta join: the NLJ mismatch observer + eager batch mechanism (so observer fires mid-stream). Everything downstream is identical to M1-M3.

## Data Exploration

### Column Ranges (SF10 Columnar)

| Column | Min | Max |
|---|---|---|
| `l_extendedprice` | 900.91 | 104,949.50 |
| `p_retailprice` | 900.91 | 2,098.99 |
| `l_shipdate` | 1992-01-02 | 1998-12-01 |
| `o_orderdate` | 1992-01-01 | 1998-08-02 |

### Why `l_extendedprice` Theta Joins Are Unusable

The `DoubleColumnFilterWriter` has a known bug (swapped reset values in `reset()`). The fix is in the working tree but **existing on-disk zone maps were written with the buggy writer** — so page-level filtering for double columns doesn't work on existing data without reload.

Additionally, there's a deeper undocumented issue with double normalization beyond the reset bug.

### Orders with `o_orderkey <= 20`

Only 6 orders exist: keys 1,2,3,4,5,7 with dates:

| o_orderkey | o_orderdate |
|---|---|
| 1 | 1996-01-02 |
| 2 | 1996-12-01 |
| 3 | 1993-10-14 |
| 4 | 1995-10-11 |
| 5 | 1994-07-30 |
| 7 | 1996-01-10 |

Max `o_orderdate` in set: **1996-12-01**


## Test Query

```sql
SET `compiler.plaque.enabled` "true";
SELECT COUNT(*)
FROM Lineitem_column_10 l, Orders_column_10 o
WHERE l.l_shipdate < o.o_orderdate
  AND o.o_orderkey <= 20;
```

### Plan (EXPLAIN)

```
AGGREGATE(count)
  └── NESTED_LOOP [condition: lt(l_shipdate, o_orderdate)]
        ├── [outer/probe] PLAQUE_FILTER($$56, MIN, plaque-theta-$$61)
        │     └── ASSIGN($$56 = l.getField(10))  ← l_shipdate
        │           └── DATASOURCE_SCAN(Lineitem_column_10)
        └── [inner/build] BROADCAST_EXCHANGE
              └── BTREE_SEARCH(o_orderkey <= 20)  ← 6 tuples
```

### Expected Filter Behavior

- Condition: `l_shipdate < o_orderdate`
- A lineitem FAILS the join when `l_shipdate >= max(o_orderdate) = "1996-12-01"`
- ~16.7M lineitems (28%) have `l_shipdate >= "1996-12-01"` → should be unmatched
- Filter direction: MIN (tracks minimum of failing values → prunes values >= threshold)
- Expected threshold: `"1996-12-01"` (the minimum shipdate among failing tuples)


## Bug Found: Premature `mismatchWriter.close()`

### Symptom

First run reported `unmatchedOuterTuples=0, thresholdInitialized=false` on all partitions, despite 16.7M tuples that should fail the join.

### Root Cause

In `NestedLoopJoin.closeCache()`, the `mismatchWriter.close()` was called when the **inner/build side** finishes caching (Activity 1). But the actual mismatch detection happens in `multiBlockJoin()` during the outer/probe side processing (Activity 2) — which hasn't even started yet.

The NLJ has two activities connected by a blocking edge:
```
Activity 1 (JoinCacheActivityNode): receives inner (Orders), caches to run file
    → close() calls joiner.closeCache() → mismatchWriter.close() ← PREMATURE!
    
    ─── blocking edge ───

Activity 2 (NestedLoopJoinActivityNode): receives outer (Lineitem), calls join()/completeJoin()
    → multiBlockJoin() → blockJoin() → notifyUnmatchedOuterTuples() ← TOO LATE, writer already closed
```

### Fix

Moved `mismatchWriter.close()` from `closeCache()` to end of `completeJoin()`:

```java
// BEFORE (broken):
public void closeCache() {
    runFileWriter.close();
    mismatchWriter.close();  // ← fires before any outer tuple is processed!
}

// AFTER (fixed):
public void closeCache() {
    runFileWriter.close();
    // mismatchWriter stays open
}

public void completeJoin(IFrameWriter writer) {
    multiBlockJoin(writer);          // processes all remaining outer tuples
    runFileWriter.eraseClosed();
    appender.write(writer, true);
    mismatchWriter.close();          // ← now fires after all outer tuples processed
}
```

### Post-Fix Verification

After fix, the observer correctly reports:
```
PLAQUE_NLJ_NOTIFY: outerFrames=9164 totalOuter=14999912 unmatched=4184999 matched=10814913 bitsetCard=10814913
PLAQUE_THETA_JOIN_MISMATCH: unmatchedOuterTuples=4184999, thresholdInitialized=true
```

~4.18M unmatched per partition (16.7M total across 4 partitions = 28% of 60M). Threshold IS being learned.


## The Temporal Problem: Filter Finishes Before Observer Fires

### Why the Filter Still Shows 0% Filtering

Despite the observer correctly detecting 4.18M unmatched tuples per partition, the PLAQUE filter reports:
```
PLAQUE_FILTER_STATS: total=14999912, filtered=0, thresholdUninitTuples=14999912, thresholdInitialized=false
```

### Execution Timeline (per partition, fused pipeline)

The scan → assign → PLAQUE_FILTER → NLJ.join() are all fused in the same push-based pipeline thread (ONE_TO_ONE_EXCHANGE does NOT break the pipeline). The flow:

```
T=0s     Orders cached (6 tuples, instant — 2.6ms)
T=0s     Activity 2 starts: Lineitem scan begins pushing frames
T=0–39s  Scan → ASSIGN → PLAQUE_FILTER (threshold NOT initialized) → NLJ.join()
         Filter passes ALL tuples (thresholdUninitTuples = 15M)
         NLJ.join() buffers frames in outerBufferMngr (2GB join memory = 65K frames capacity)
T=39s    Scan complete. All 15M tuples buffered in NLJ (9164 frames used, no overflow)
T=39s    NLJ.completeJoin() → multiBlockJoin():
T=39–55s   blockJoin() processes 15M outer × 6 inner = 90M comparisons
T=55s      notifyUnmatchedOuterTuples() → threshold = "1996-12-01" → thresholdInitialized=true
T=55s      mismatchWriter.close() reports 4.18M unmatched ← CORRECT but TOO LATE
```

The threshold becomes valid at T=55s, but the filter needed it at T=0–39s.

### Why: Single multiBlockJoin Call

With 2GB join memory, the outer buffer (65K frame capacity) holds all 9164 frames without overflowing. `multiBlockJoin()` is called only ONCE — from `completeJoin()` at the very end. The scan is long finished by then.

If join memory were smaller (e.g., 8MB = 256 frames), the buffer would overflow after ~256 frames (~420K tuples). `multiBlockJoin()` would fire, the observer would learn the threshold, then the scan would resume pushing more tuples through the filter which now HAS a threshold. Multiple batches would enable feedback.


## Cross-Partition Sharing (Layer 1) — The Only Working Path

### How the `l_shipdate >= '1998-11-01'` Query Filtered 11%

```sql
SET `compiler.plaque.enabled` "true";
SELECT COUNT(*)
FROM Lineitem_column_10 l, Orders_column_10 o
WHERE l.l_shipdate < o.o_orderdate
  AND o.o_orderkey <= 20
  AND l.l_shipdate >= '1998-11-01';
```

Result: 0 (all lineitems with shipdate >= 1998-11-01 fail the join). ~25K tuples per partition.

Filter stats:
```
partition 3: total=25502, filtered=0,    thresholdUninitTuples=25502, thresholdInitialized=false
partition 2: total=25866, filtered=2948, thresholdUninitTuples=22918, thresholdInitialized=true (11.4%)
partition 0: total=25758, filtered=2840, thresholdUninitTuples=22918, thresholdInitialized=true (11.0%)
partition 1: total=25992, filtered=3074, thresholdUninitTuples=22918, thresholdInitialized=true (11.8%)
```

### Explanation

Each partition runs in its own thread. With only ~25K tuples, a partition's entire pipeline (scan ~10.5s + NLJ ~32ms) completes fast. Partition 3 happened to finish its full pipeline first:
1. Partition 3's NLJ fires → observer updates shared `PlaqueThresholdState` in `PlaqueThresholdRegistry`
2. Partitions 0, 1, 2 are still scanning (slight timing differences between threads)
3. Their first 22,918 tuples passed through filter before threshold was set
4. Remaining ~2900 tuples see the initialized threshold → filtered (11%)

### Why This Doesn't Scale

With more tuples per partition, all partitions' scans finish at the same time, then all NLJs process at the same time. No stagger → no cross-partition benefit.

Profiled timing for this query:
- Lineitem scan: ~10.5s per partition
- NLJ processing: ~32ms per partition
- Orders BTREE_SEARCH: 2.6ms (instant)

The effect only works when the NLJ is near-instant relative to the scan, AND partition timing jitter creates overlap.


## Experiment Results

### Full Query (no extra shipdate filter)

```sql
SELECT COUNT(*) FROM Lineitem_column_10 l, Orders_column_10 o
WHERE l.l_shipdate < o.o_orderdate AND o.o_orderkey <= 20;
```

| | Baseline | PLAQUE | Delta |
|---|---|---|---|
| Time | 57.0s | 71.0s | +25% overhead |
| Result | 182,875,490 | 182,875,490 | ✓ correct |
| Filter rate | — | 0% | no filtering |
| Unmatched (per partition) | — | 4.18M | detected but too late |

### With `l_shipdate >= '1996-01-01'` (~6.27M tuples/partition)

| | Baseline | PLAQUE | Delta |
|---|---|---|---|
| Time | 31.3s | 39.4s | +26% overhead |
| Result | 8,599,698 | 8,599,698 | ✓ correct |
| Filter rate | — | 0% | no filtering |
| Unmatched (per partition) | — | 4.18M | detected but too late |

### With `l_shipdate >= '1998-11-01'` (~25K tuples/partition)

| | PLAQUE (no baseline run) |
|---|---|
| Time | 13.3s |
| Result | 0 |
| Filter rate | 11% on 3/4 partitions |
| Mechanism | Cross-partition Layer 1 sharing |


## Profiled Overhead Breakdown (`l_shipdate >= '1996-01-01'`)

Per-operator runtime comparison (summed across 4 partitions):

| Operator | Baseline | PLAQUE | Delta |
|---|---|---|---|
| **Index Search (Lineitem scan)** | 75,932 ms | 75,272 ms | ~same |
| **NLJ Activity 2** | **29,768 ms** | **55,127 ms** | **+85% (+25.4s)** |
| Assign (l_shipdate) | 8,049 ms | 8,044 ms | ~same |
| Stream-select (>= '1996') | 5,077 ms | 5,066 ms | ~same |
| Stream-project | ~4,900 ms | ~4,900 ms | ~same |
| **PLAQUE filter** | — | **1,826 ms** | +1.8s new |
| Aggregate (count) | 92 ms | 98 ms | ~same |

**The filter itself is cheap** (460ms per partition = ~73 nanoseconds per tuple). The overhead is entirely inside the NLJ:
- `notifyUnmatchedOuterTuples()` iterates 6.27M BitSet positions, finds 4.18M clear bits
- 4.18M calls to `onUnmatchedOuterTuple()`, each doing field extraction + type tag check
- 4.18M `synchronized thresholdState.update()` calls — 4 partitions contend on the same lock
- The synchronized contention across 4 threads is likely the dominant factor

This overhead is fixable: accumulate a local best per partition first, then do one synchronized update at the end of `notifyUnmatchedOuterTuples()` instead of per-tuple.


## The 22,918 Anomaly

All three filtering partitions (0, 1, 2) report exactly `thresholdUninitTuples=22918` despite having different total tuple counts (25,758 / 25,866 / 25,992). This number appears too deterministic for thread-timing jitter.

**Likely explanation:** All 4 partitions start their Lineitem scans at the same wall-clock moment (kicked off by the same scheduler event after the broadcast exchange completes). They scan at the same rate (same buffer cache, same CPU, same I/O subsystem). Partition 3 has the fewest tuples (25,502), so its pipeline (scan 10.5s + NLJ 32ms) finishes first. At that exact wall-clock moment, the other three partitions are all at the same scan position — 22,918 tuples through the filter. The threshold becomes visible (volatile read), and their remaining ~2,900 tuples get filtered.

This is deterministic because the starts are synchronized and the scan rates are uniform — not random jitter. Still, this should be verified with per-partition timestamps to confirm.


## Analysis: The Structural Problem and the Right Fix

### Why the Filter Doesn't Work (Not a Bug — A Design Mismatch)

The thesis's theta-join predicate learning assumes a **streaming probe**: outer tuples hit the join one at a time, failures are detected immediately, and the predicate tightens continuously. Hyracks's block NLJ is not that join. It buffers the entire outer stream (up to join memory) to amortize inner run-file rereads, then does all comparisons at the end. The failure signal arrives in one batch after the filter's entire window of usefulness has closed.

### Why "Shrink Join Memory" Is the Wrong Fix

Reducing join memory would force the outer buffer to overflow, creating multiple `multiBlockJoin()` calls and enabling feedback. But this approach is wrong for two reasons:

1. **It's a config accident, not a principled design.** "PLAQUE works if you starve the NLJ of memory" is not a result you can publish.

2. **It misunderstands why the buffering exists.** The outer buffering exists solely to amortize rereads of the inner run file. Each `multiBlockJoin()` call re-reads the entire inner file. With K batches, the inner is read K times. Smaller memory = more batches = more rereads.

### The Right Fix: Eager Join When Inner Is Memory-Resident

The key observation: **when the inner side fits in memory, re-reading it costs nothing.** The 6 Orders tuples are in one frame — re-reading that frame thousands of times is essentially free. All the outer buffering is buying nothing in this case, while destroying the feedback loop.

The clean fix: **when the inner side is fully memory-resident, don't buffer the outer side. Join each outer frame eagerly as it arrives.** This converts the block NLJ into exactly the streaming join the thesis assumes, at frame granularity, with zero reread cost.

This is not a special case — theta join filtering is only interesting when the inner side is small and selective (otherwise the join is too expensive regardless). So "inner fits in memory" is precisely the regime where this technique matters.

When the inner side genuinely spills to disk, batching is necessary, and there's an honest tradeoff: smaller batches mean faster predicate learning but more inner rereads. Characterizing that curve is a real research contribution. Reducing join memory config is not.

### Implementation Approach for Eager Join

In `NestedLoopJoin.join()`:

```java
public void join(ByteBuffer outerBuffer, IFrameWriter writer) {
    if (innerFitsInMemory) {
        // EAGER PATH: join this frame immediately, no buffering
        accessorOuter.reset(outerBuffer);
        accessorInner.reset(cachedInnerFrame);
        blockJoin(0, writer);
        // notify unmatched immediately — threshold available for next frame
        if (mismatchWriter != null) {
            notifyUnmatchedOuterTuples(1);  // just this one frame
        }
    } else {
        // EXISTING PATH: buffer outer frames
        if (outerBufferMngr.insertFrame(outerBuffer) < 0) {
            multiBlockJoin(writer);
            outerBufferMngr.reset();
            outerBufferMngr.insertFrame(outerBuffer);
        }
    }
}
```

The `innerFitsInMemory` flag is set during `cache()` — if all inner frames fit in a single in-memory buffer without spilling, it's true.

### What "Success" Looks Like

Even with perfect instantaneous feedback, the tuple-level filter sits above the scan. A dropped tuple only saves NLJ buffering and 6 comparisons — the scan cost (which dominates) is already paid. The ceiling on end-to-end speedup from the tuple-level filter alone is small.

**The real prize is page-level skipping via zone maps.** Once the threshold says "nothing with `l_shipdate >= 1996-12-01` can matter," any page whose zone map says "all my shipdates are >= 1996-12-01" can be skipped entirely — no read, no decode, no PK decompression. That's where 28% of tuples turns into actual seconds saved. This is the same mechanism that gave 4-46x speedups in M1-M3.

The tuple-level filter and eager-join work are plumbing to deliver a threshold early enough for page skipping to use. The page-skip integration is the main event.


## Roadmap (Ordered by Priority)

### Step 1: Fix Observer Overhead

**Problem:** 4.18M `synchronized thresholdState.update()` calls per partition, 4 threads contending on same lock. NLJ doubles in cost.

**Fix:** In `notifyUnmatchedOuterTuples()`, accumulate a local-best value (unsynchronized), then do ONE `thresholdState.update()` call at the end. This eliminates lock contention entirely.

### Step 2: Eager Join When Inner Is Memory-Resident — COMPLETE

**Problem:** Block NLJ buffers entire outer side, joins at the end. Feedback loop is dead.

**Fix:** When inner tuple count is small (≤ 1 frame worth, detected during `cache()`), flush every `eagerBatchSize` frames instead of waiting for buffer overflow. After each batch's `multiBlockJoin()`, `notifyUnmatchedOuterTuples()` + `flushBatch()` publishes the threshold. The filter in the same pipeline thread reads it via volatile on the next tuple.

**Implementation:**
- `NestedLoopJoin`: new fields `cachedInnerTupleCount`, `eagerMismatchEnabled`, `eagerBatchSize`
- `cache()`: counts inner tuples
- `closeCache()`: sets `eagerMismatchEnabled = mismatchWriter != null && eagerBatchSize > 0 && cachedInnerTupleCount <= maxInnerTuplesForEager`
- `join()`: one `else if` branch — when `eagerMismatchEnabled && numFrames >= eagerBatchSize`, calls `multiBlockJoin()` + `reset()`
- Config: `compiler.plaque.eager.batch.size` (default 32), plumbed through `CompilerProperties` → `PhysicalOptimizationConfig` → `PlaqueRewriteRule` → `PlaqueThetaJoinPOperator` → `NestedLoopJoinOperatorDescriptor` → `NestedLoopJoin`

**Key insight:** The inner side arrives via BROADCAST_EXCHANGE in multiple frames from multiple senders. We count **tuples** not frames (6 orders across 4 senders = 4 frames but only 6 tuples). Any inner small enough to fit in one frame of tuples is considered memory-resident — re-reading the run file is free (OS page cache).

**Result: Feedback loop closed.** Batch 1 (32 frames = ~52K tuples) learns the threshold. From batch 2 onward, the filter drops 66% of tuples upstream of the NLJ. NLJ processes only 2.12M tuples per partition instead of 6.27M.


## Step 1+2 Combined Results

### With `l_shipdate >= '1996-01-01'` (~6.27M tuples/partition, 66% filterable)

```sql
SELECT COUNT(*) FROM Lineitem_column_10 l, Orders_column_10 o
WHERE l.l_shipdate < o.o_orderdate AND o.o_orderkey <= 20
  AND l.l_shipdate >= '1996-01-01';
```

| | Baseline (warm) | PLAQUE (warm) | Delta |
|---|---|---|---|
| **Wall clock** | **39.0s** | **27.7s** | **1.41x speedup** |
| Scan (sum) | 76,293 ms | 76,307 ms | ~same |
| **NLJ (sum)** | **44,471 ms** | **10,696 ms** | **4.2x less** |
| PLAQUE filter | — | 2,771 ms | new |
| Filter rate | — | 66.1% | 4.15M filtered/partition |
| thresholdUninitTuples | — | 52,385 | only first batch |
| Result | 8,599,698 | 8,599,698 | ✓ correct |

Filter stats per partition:
```
total=6,265,494  passed=2,120,207  filtered=4,145,287  thresholdUninitTuples=52,385  filterRate=66.16%
total=6,266,946  passed=2,123,039  filtered=4,143,907  thresholdUninitTuples=52,385  filterRate=66.12%
total=6,267,454  passed=2,122,661  filtered=4,144,793  thresholdUninitTuples=52,385  filterRate=66.13%
total=6,275,205  passed=2,125,423  filtered=4,149,782  thresholdUninitTuples=52,385  filterRate=66.13%
```

NLJ batch pattern:
```
Batch 1: outerFrames=32 totalOuter=52384 unmatched=35217 matched=17167  ← threshold learned
Batch 2: outerFrames=32 totalOuter=52384 unmatched=1    matched=52383  ← filter now active
Batch 3+: outerFrames=32 totalOuter=52384 unmatched=0   matched=52384  ← all incoming tuples pass
```

### Full query, no pre-filter (15M tuples/partition, 28% filterable)

```sql
SELECT COUNT(*) FROM Lineitem_column_10 l, Orders_column_10 o
WHERE l.l_shipdate < o.o_orderdate AND o.o_orderkey <= 20;
```

| | Baseline (warm) | PLAQUE (warm) | Delta |
|---|---|---|---|
| **Wall clock** | **52.2s** | **49.6s** | **1.05x speedup** |
| Scan (sum) | 82,232 ms | 86,423 ms | ~same |
| **NLJ (sum)** | **85,224 ms** | **65,771 ms** | **1.30x less** |
| Result | 182,875,490 | 182,875,490 | ✓ correct |

Smaller speedup: only 28% of tuples are filterable (lineitems with `l_shipdate >= "1996-12-01"` = max order date). The scan is a larger share of total runtime. With page-level skipping, the scan reduction would amplify this.


## Batch Size Ablation (SF10, `l_shipdate >= '1996-01-01'`, 66% filterable)

All runs warm. Batch size = number of outer frames per eager flush.

| Batch Size | Wall Clock | Scan (sum) | NLJ (sum) | Filter (sum) |
|---|---|---|---|---|
| baseline | 30.6s | 74,988 | 27,317 | — |
| 1 | 28.4s | 76,195 | 11,522 | 2,431 |
| 2 | 28.3s | 75,559 | 10,740 | 2,493 |
| 4 | 27.0s | 74,515 | 10,235 | 2,431 |
| 8 | 27.6s | 76,575 | 10,190 | 2,452 |
| 16 | 26.8s | 74,308 | 10,194 | 2,432 |
| 32 | 27.4s | 75,457 | 10,535 | 2,433 |
| 64 | 27.1s | 74,235 | 10,292 | 2,404 |
| 128 | 26.8s | 72,573 | 10,537 | 2,369 |
| 256 | 26.8s | 70,139 | 11,275 | 2,303 |
| 512 | 26.9s | 71,587 | 12,292 | 2,289 |

**Observation:** Batch size barely matters — NLJ is ~10-12s across all sizes. The threshold converges in the first batch regardless. The batch size knob matters more when the threshold tightens progressively across batches. Default 32 is a good balance.


## Scale Factor Comparison (batch=32, `l_shipdate >= '1996-01-01'`, 66% filterable)

Query: `SELECT COUNT(*) FROM Lineitem_column_SF l, Orders_column_SF o WHERE l.l_shipdate < o.o_orderdate AND o.o_orderkey <= 20 AND l.l_shipdate >= '1996-01-01'`

4 runs each, R1 discarded as warmup, median of R2-R4 reported.

| SF | Rows/partition | Baseline | PLAQUE | Speedup | NLJ baseline | NLJ PLAQUE | NLJ reduction |
|---|---|---|---|---|---|---|---|
| 10 | 6.27M | 30.6s | 27.0s | **1.13x** | 27,317 | 10,128 | 2.7x |
| 30 | 18.8M | 94.5s | 81.3s | **1.16x** | 88,725 | 29,931 | 3.0x |
| 50 | 31.3M | 157.1s | 132.0s | **1.19x** | 148,184 | 49,410 | 3.0x |

**Observations:**
- NLJ consistently **3x reduction** across all scale factors
- Wall-clock speedup increases slightly with SF (1.13x → 1.19x) because NLJ's share of total time grows
- Scan is unaffected — the tuple-level filter sits above the scan
- Speedup ceiling from tuple-level filtering is bounded by scan cost
- With page-level skip (Step 3), scan reduction would multiply the benefit


## No Pre-Filter Results (full lineitem, 28% filterable)

Query: `SELECT COUNT(*) FROM Lineitem_column_SF l, Orders_column_SF o WHERE l.l_shipdate < o.o_orderdate AND o.o_orderkey <= 20`

| SF | Baseline | PLAQUE | Speedup | NLJ baseline | NLJ PLAQUE | NLJ reduction |
|---|---|---|---|---|---|---|
| 10 | 52.2s | 49.6s | **1.05x** | 85,224 | 65,771 | 1.30x |
| 30 | 161.7s | 148.5s | **1.09x** | 267,494 | 198,160 | 1.35x |

Lower speedup than with pre-filter (28% vs 66% filterable). The NLJ reduction is smaller because most tuples pass the filter — only those with `l_shipdate >= "1996-12-01"` (28%) are pruned.


### Step 3: Wire Threshold into Columnar Page Skip — COMPLETE

**Problem:** The tuple-level filter saves only NLJ work, not scan work. Scan dominates.

**Fix:** Extended `PlaquePageFilterRule` to handle theta join operators (in addition to aggregates). When a PLAQUE-annotated theta join is found, the rule walks the outer side to find the DataSourceScan, extracts the filtered column's field name, and calls `columnInfo.setPlaqueInfo()` to attach the page filter. Also added `"plaque-isMax"` and `"plaque-filteredVar"` annotations on the join operator in `PlaqueRewriteRule`.

Fixed string normalization bug in `PlaqueColumnFilterEvaluatorFactory.normalizeThreshold()`: was reading byte pairs at 2-byte stride (treating UTF-8 as UTF-16). Fixed to use `UTF8StringUtil.charAt()` / `charSize()` matching `StringColumnFilterWriter.normalize()`.

**Files modified:**
- `PlaqueRewriteRule.java` — added `plaque-isMax` and `plaque-filteredVar` annotations on theta join
- `PlaquePageFilterRule.java` — refactored to dispatch aggregate vs theta join, added `handleThetaJoin()` and shared `attachPageFilter()` method
- `PlaqueColumnFilterEvaluatorFactory.java` — fixed string normalization to match zone map scheme


## Step 3: Page Skip Results

### String Column Limitation: `l_shipdate`

Page skip does NOT work for `l_shipdate` (string dates). The 4-char normalization (`>>> 1`) collapses years into only 4 distinct values:

| Year | Normalized Long |
|---|---|
| 1992, 1993 | 6896259337846809 |
| 1994, 1995 | 6896259337846810 |
| 1996, 1997 | 6896259337846811 |
| 1998 | 6896259337846812 |

With ~15K tuples per page and uniformly distributed dates (1992-1998), every page has min="1992..." and max="1998...". The threshold "1996..." normalizes to the same value as many page mins → no pages skipped. Additionally, with random `l_shipdate` uncorrelated with PK order, page zone maps span the full date range on every page.

This is a fundamental limitation of string zone maps for date columns stored as text. Would be resolved by storing dates as integers (days since epoch).

### Bigint Column Success: `l_suppkey`

Page skip works for `l_suppkey` (bigint) with full long precision in zone maps.

**Query:**
```sql
SET `compiler.plaque.enabled` "true";
SELECT COUNT(*)
FROM Lineitem_column_10 l, Orders_column_10 o
WHERE l.l_suppkey > o.o_custkey
  AND o.o_orderkey = 3271;
```

- Inner: 1 order with `o_custkey = 330575`
- `l_suppkey` range: 1-100,000 → `l_suppkey > 330575` is FALSE for all lineitems
- All 60M tuples fail → threshold = MAX of failing = 100,000
- `isMax=true`, page filter: skip when `pageMAX < 100000`
- With 15K random draws from [1, 100000], most pages have max ≈ 99,950 < 100,000 → **skipped**

### Full Ablation: Tuple Filter vs Page Skip (SF10 / SF30 / SF50)

| SF | Baseline | Tuple only | Tuple + Page skip | Tuple speedup | Full speedup |
|---|---|---|---|---|---|
| 10 | 28.8s | 26.1s | 1.7s | 1.10x | **21.8x** |
| 30 | 84.2s | 81.2s | 1.2s | 1.04x | **72.7x** |
| 50 | 143.0s | 133.9s | 1.2s | 1.07x | **116.5x** |

**Detailed per-operator (SF10):**

| | Baseline | Tuple only | Tuple + Page skip |
|---|---|---|---|
| Wall clock | 28.8s | 26.1s | 1.7s |
| Scan (sum) | 68,703 ms | 67,189 ms | 2,506 ms |
| NLJ (sum) | 11,357 ms | 386 ms | 387 ms |
| Filter (sum) | — | 3,511 ms | 253 ms |
| Page skip rate | — | 0% | 97.9% |

**Detailed per-operator (SF30):**

| | Baseline | Tuple only | Tuple + Page skip |
|---|---|---|---|
| Wall clock | 84.2s | 81.2s | 1.2s |
| Scan (sum) | 205,275 ms | 216,079 ms | 2,061 ms |
| NLJ (sum) | 33,203 ms | 75 ms | 64 ms |
| Filter (sum) | — | 9,606 ms | 156 ms |

**Detailed per-operator (SF50):**

| | Baseline | Tuple only | Tuple + Page skip |
|---|---|---|---|
| Wall clock | 143.0s | 133.9s | 1.2s |
| Scan (sum) | 343,338 ms | 348,026 ms | 2,642 ms |
| NLJ (sum) | 55,543 ms | 76 ms | 59 ms |
| Filter (sum) | — | 16,102 ms | 123 ms |

Page filter stats (SF10):
```
pages=9000, skipped=8823 (98.0%)   thresholdInit=true
pages=9000, skipped=8814 (97.9%)   thresholdInit=true
pages=9000, skipped=8815 (97.9%)   thresholdInit=true
pages=9000, skipped=8806 (97.9%)   thresholdInit=true
```

**Observations:**
- **Tuple filter alone:** eliminates NLJ work (30-730x less) but scan unchanged → ceiling ~1.1x
- **Page skip on top:** eliminates scan work (27-130x less) → the dominant factor
- **Speedup grows dramatically with SF:** 21.8x → 72.7x → 116.5x because larger dataset = more pages to skip, but page skip time is constant (~1.2s regardless of SF)
- **Page skip time is scale-invariant:** only ~2% of pages survive → ~200 pages processed regardless of SF
- Comparable to M1 single-table results (92x at SF30, 45.8x at SF100)

### Why Page Skip Works at Distribution Extremes

With N random draws from [1, M], the expected maximum is M·N/(N+1). For N=15000, M=100000: expected max ≈ 99,993. The threshold is 100,000. So pages with max < 100,000 (~86-98% of pages) are skipped. This only works when the threshold is at the extreme of the value distribution — near the global min or max.

For thresholds in the middle of the distribution (like the l_shipdate case), every page spans the full range → no pages skippable.


### Step 4: Fix Double Column Zone Maps + Reload Data

**Problem:** `DoubleColumnFilterWriter.reset()` bug means existing on-disk zone maps for double columns are wrong. Additional undocumented double normalization issue.

**Fix:** Investigate the normalization issue, fix it, reload columnar datasets. This unblocks testing with `l_extendedprice` (the thesis's motivating column) and is required for the original theta join query (`l_extendedprice < p_retailprice`).
