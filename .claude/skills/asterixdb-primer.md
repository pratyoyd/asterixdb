---
name: asterixdb-primer
description: Reference guide for AsterixDB internals relevant to this project - query plans, optimizer rules, physical operators, columnar storage, and the PLAQUE optimization framework
user_invocable: true
---

# AsterixDB Primer

Use this knowledge when answering questions about AsterixDB internals, query plans, optimizer architecture, or the PLAQUE optimization framework in this codebase.

## Query Compilation Pipeline

```
SQL++ text
  → Parser (asterix-lang-sqlpp)
  → Logical plan (Algebricks operators)
  → Rewrite rules (multiple phases):
      1. Normalization & simplification
      2. Consolidation (PlaqueRewriteRule runs here)
      3. Physical operator assignment (SetAsterixPhysicalOperatorsRule)
      4. Physical rewrites (PushLimitIntoOrderByRule, EnforceStructuralPropertiesRule)
      5. PrepareForJobGen (PlaquePageFilterRule, PlaqueCrossExprJobGenRule)
  → Hyracks job specification
  → Distributed execution on NCs
```

## Key Operator Tags

| Logical Tag | Physical Tag | Description |
|-------------|-------------|-------------|
| `AGGREGATE` | `AGGREGATE` | Local/global aggregation |
| `INNERJOIN` | `HYBRID_HASH_JOIN` | Hash join with spill support |
| `ORDER` | `STABLE_SORT` | Sort, optionally with `topK` |
| `LIMIT` | `STREAM_LIMIT` | Row limit |
| `EXCHANGE` | `HASH_PARTITION_EXCHANGE` | Hash-based data redistribution |
| `EXCHANGE` | `SORT_MERGE_EXCHANGE` | Sorted merge across partitions |
| `DATASOURCESCAN` | `DATASOURCE_SCAN` | Table scan |
| `ASSIGN` | `ASSIGN` | Expression evaluation |
| `PROJECT` | `STREAM_PROJECT` | Column projection |

## Hash Join Internals

**Descriptor:** `OptimizedHybridHashJoinOperatorDescriptor`

**Two activities:**
- Activity 0 (`PartitionAndBuildActivityNode`): Receives BUILD side, calls `build()` per tuple, then `closeBuild()` to construct hash table
- Activity 1 (`ProbeAndJoinActivityNode`): Receives PROBE side, probes hash table, outputs joined tuples

**Plan-to-descriptor input mapping:**
- Plan input 0 → `addSourceEdge(0, probeActivity, 0)` → PROBE side
- Plan input 1 → `addSourceEdge(1, buildActivity, 0)` → BUILD side

**Build completes before probe starts** (blocking edge between activities). This is the temporal guarantee PLAQUE relies on.

**Spilling:** When memory is insufficient, build partitions spill to disk. During probe, matching probe tuples also spill. After initial pass, spilled partitions are reloaded and re-processed.

## Columnar Storage

**Format:** LSM B-Tree with columnar storage (`hyracks-storage-am-lsm-btree-column`)

**Page structure:** Each "mega page" contains:
- Page zero: column offsets + filter zone maps (min/max per column)
- Column pages: actual column data

**Zone maps:** 16 bytes per column (8 bytes min + 8 bytes max), normalized to `long`:
- BIGINT: raw long value
- DOUBLE: `Double.doubleToLongBits(value)` (known bug in existing data - DoubleColumnFilterWriter reset values were swapped)
- STRING: first 4 UTF-8 chars packed into long

**Page filter evaluation:** `QueryColumnTupleReference.startNewPage()`:
1. Read page zero
2. Populate filter accessors from zone maps via `FilterAccessorProvider.setFilterValues()`
3. Call `rangeFilterEvaluator.evaluate()` — returns false to skip entire page

## Optimizer Rule Phases

**Consolidation** (where PlaqueRewriteRule runs):
- Plan has logical operators, no physical operators yet
- No EXCHANGE operators (inserted later by EnforceStructuralPropertiesRule)
- LIMIT and ORDER are separate operators (not yet merged by PushLimitIntoOrderByRule)
- Good for: pattern detection, annotation setting

**Physical rewrites:**
- `SetAsterixPhysicalOperatorsRule`: assigns physical operators (HYBRID_HASH_JOIN, STABLE_SORT, etc.)
- `EnforceStructuralPropertiesRule`: inserts EXCHANGE operators for data redistribution
- `PushLimitIntoOrderByRule`: merges LIMIT into ORDER (sets `topK`), creates NEW OrderOperator (loses annotations!)

**PrepareForJobGen** (where PlaqueCrossExprJobGenRule and PlaquePageFilterRule run):
- Physical operators assigned, exchanges present, topK merged
- Good for: replacing physical operators with PLAQUE variants, attaching page filters

## PLAQUE Framework

**Core idea:** Learn thresholds at query time and push them down to skip work.

### Components

| Component | Location | Purpose |
|-----------|----------|---------|
| `PlaqueThresholdState` | asterix-runtime/.../plaque/ | Stores current threshold value (byte[]), thread-safe updates |
| `PlaqueThresholdRegistry` | asterix-runtime/.../plaque/ | NC-level singleton, keyed by (JobId, handle) |
| `PlaqueBuildSideMinState` | asterix-runtime/.../plaque/ | Per-partition MIN/MAX vector from build side |
| `PlaqueRewriteRule` | asterix-algebra/.../rules/ | Detects MAX/MIN/Top-K patterns, sets annotations |
| `PlaqueCrossExprJobGenRule` | asterix-algebra/.../rules/ | Late-phase: replaces physical operators |
| `PlaquePageFilterRule` | asterix-algebra/.../rules/ | Late-phase: attaches page filter to scan |

### Threshold Propagation

1. Local aggregate/sort updates `PlaqueThresholdState` on the NC
2. State marked dirty → NC sends `PlaqueThresholdUpdateMessage` to CC
3. CC broadcasts `PlaqueThresholdBroadcastMessage` to all NCs
4. All NCs update their local copy → exchange filters read updated threshold

### Cross-Expression Filter (MAX(a - b) or Top-K ORDER BY a - b DESC)

Three filter levels:
1. **Page filter** (scan time): Skip page if `pageMax(a) <= t + global_c_min`
2. **Exchange filter** (pre-join): Drop tuple if `a <= t + c_min[dest_partition]`
3. **Build observer** (join build): Track `c_min = MIN(b)` per partition

### Annotation Convention

Annotations are stored on logical operators via `op.getAnnotations().put(key, value)`.

**On aggregate/ORDER:** `plaque-aggregate`, `plaque-handle`, `plaque-isMax`, `plaque-filteredVar`, `plaque-cross-expr`, `plaque-crossexpr-probe-var`, `plaque-crossexpr-build-handle`, `plaque-crossexpr-combine-op`, `plaque-crossexpr-filter-direction`

**On join:** `plaque-cross-expr`, `plaque-crossexpr-build-handle`, `plaque-crossexpr-build-var`, `plaque-crossexpr-track-min`, `plaque-crossexpr-num-partitions`, `plaque-crossexpr-threshold-handle`, `plaque-crossexpr-probe-var`, `plaque-crossexpr-combine-op`, `plaque-crossexpr-filter-direction`, `plaque-crossexpr-done`

**Warning:** `PushLimitIntoOrderByRule` creates a NEW `OrderOperator`, destroying annotations. Use join annotations (which persist) as the source of truth in late-phase rules.

## Running Queries

```bash
# Simple query
curl -s http://localhost:19002/query/service \
  --data-urlencode 'statement=SELECT ...' 2>&1

# With PLAQUE
curl -s http://localhost:19002/query/service \
  --data-urlencode 'statement=SET `compiler.plaque.enabled` "true"; SELECT ...' 2>&1

# With profiling
curl -s http://localhost:19002/query/service \
  --data-urlencode 'statement=SELECT ...' \
  -d 'profile=timings' -d 'optimized-logical-plan=true' 2>&1

# EXPLAIN only
curl -s http://localhost:19002/query/service \
  --data-urlencode 'statement=EXPLAIN SELECT ...' 2>&1
```

## Build

```bash
cd asterixdb/
mvn clean package -DskipTests -DskipRat -Dlicense.skip=true -Dcheckstyle.skip=true
```

The user handles all builds. Never run mvn.

## Dataset Naming

TPC-H columnar datasets: `{Table}_column_{SF}` (e.g., `Lineitem_column_10`, `Partsupp_column_100`)

## Config

- Main config: `asterixdb/asterix-app/src/test/resources/cc-main.conf`
- Buffer cache, partition count, join memory configured there
- PLAQUE config keys must be whitelisted in `SqlppCompilationProvider.getCompilerOptions()`
