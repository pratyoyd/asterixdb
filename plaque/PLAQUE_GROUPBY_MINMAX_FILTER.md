new # PLAQUE: GROUP BY MIN/MAX Filter

## Status: Research / Design Phase

## What It Does

For queries like `SELECT MAX(a), b FROM ... GROUP BY b`, PLAQUE maintains a **per-group threshold** for each group value `b`. Unlike scalar MIN/MAX (M1-M3) where there's one global threshold, here each group has its own running max/min. The filter is a disjunction: a tuple passes if either (a) its group hasn't been seen yet, or (b) its aggregate value beats the threshold for its group.

**Motivating example** (thesis Section 4.3.2, p.73-75):

```sql
SELECT MAX(l_discount), l_shipmode
FROM lineitem
GROUP BY l_shipmode
```

When the first tuple with `l_shipmode = "Air"` and `l_discount = 0.3` reaches the aggregate, PLAQUE learns: for group "Air", any future tuple needs `l_discount > 0.3`. When later a tuple with `l_shipmode = "Air"` and `l_discount = 0.8` arrives, the threshold for "Air" tightens to `l_discount > 0.8`.

A tuple with `l_shipmode = "Mail"` (a group not yet seen) always passes -- we have no threshold for it yet.


## Thesis Formalization (Section 4.3.2, p.73-75)

### Event 3: Predicate Initialization

- **WHEN:** at start of query execution
- **THEN:** Add predicate `p = NOT(b IN $groups)`, where `$groups = {}` (empty set)

Initially the predicate is vacuously true (all tuples pass since no groups have been seen).

### Event 4: Predicate Addition (new group seen)

- **WHEN:** `MAX(a)` operator receives tuple `t`
- **IF:** `t` is the first tuple in the group where `b = t.b`
- **THEN:** 
  - Create per-group predicate `p_i = (b = b_i) AND (a > $a_i)` where `$a_i = t.a`
  - Add `b_i` to `$groups`
  - Update composite predicate: `p = NOT(b IN $groups) OR p' OR ((b = b_i) AND (a > $a_i))`

### Event 5: Predicate Refinement (existing group threshold strengthens)

- **WHEN:** `MAX(a)` operator receives tuple `t`
- **IF:** `t` is in group `b_i` where `b_i IN $groups`, and `t.a > $a_i`
- **THEN:** Update predicate to `p = NOT(b IN $groups) OR p' OR ((b = b_i) AND (a > $a_i))` where `$a_i = t.a`

### Composite Predicate Structure

The full predicate has the form:

```
p = NOT(b IN {seen_groups})           -- pass unseen groups unconditionally
  OR (b = "Air"   AND a > 0.8)       -- per-group threshold
  OR (b = "Mail"  AND a > 0.5)       -- per-group threshold  
  OR (b = "Truck" AND a > 0.3)       -- per-group threshold
  OR ...
```

A tuple passes if:
1. Its group value `b` has NOT been seen before (conservative -- no threshold available), OR
2. Its group value `b` has been seen AND its `a` value beats the current threshold for that group

### Optimizations for Large Number of Groups (p.75)

- **Top-k groups only:** When the number of groups is large, maintaining per-group predicates for all groups is expensive. PLAQUE uses a **bootstrapping** approach: process an initial sample without predicates, determine the top-k largest groups, then only maintain predicates for those groups. Filtering is most effective on large groups (more tuples to potentially prune).
- **Bloom filter for group membership:** Use a bloom filter for the `NOT(b IN $groups)` check instead of a hash set. False positives in the bloom filter only reduce effectiveness (a tuple might not get filtered when it could have been), never correctness.


## AsterixDB Implementation Considerations

### How AsterixDB Handles GROUP BY with Aggregation

In AsterixDB, `GROUP BY b` with `MAX(a)` goes through:

1. **SQL++ rewriter:** Creates a `GroupByOperator` with nested `AggregateOperator`
2. **Phase 3 (condPushDown):** `EliminateGroupByEmptyKeyRule` does NOT fire (there IS a group key)
3. **Phase 5 (consolidation):** `AsterixIntroduceGroupByCombinerRule` fires -- creates local GROUP BY (hash-based, per-partition) + global GROUP BY (merge results across partitions)

The local GROUP BY uses a hash table keyed by `b`. For each new tuple:
- If `b` is new: create a new entry, init aggregate
- If `b` exists: call `step()` on the aggregate

**Key files:**
- `AsterixIntroduceGroupByCombinerRule.java` -- creates the local/global split
- `GroupByOperator.java` -- logical operator
- `HashGroupByPOperator.java` / `PreSortedGroupByPOperator.java` -- physical operators
- `HashGroupByRuntime.java` / `PreSortedGroupByRuntime.java` -- runtimes
- `ExternalGroupByRuntime.java` -- external (spilling) variant

### Key Difference from Scalar MIN/MAX (M1-M3)

| | Scalar MIN/MAX (M1-M3) | GROUP BY MIN/MAX |
|---|---|---|
| **Groups** | 1 implicit group (all tuples) | N groups (one per distinct `b` value) |
| **Threshold state** | Single threshold value | Map: `group_value -> threshold` |
| **Filter logic** | `col >= threshold` | `group NOT seen yet` OR `(group matches AND col >= group_threshold)` |
| **Observer** | `onMinMaxChanged()` hook in aggregate | Same hook, but must track which group changed |
| **State size** | O(1) | O(number of groups) |
| **Optimizer target** | Standalone `AggregateOperator` after `IntroduceAggregateCombinerRule` | `GroupByOperator` after `AsterixIntroduceGroupByCombinerRule` |

### Filter State: `PlaqueGroupThresholdState`

Instead of a single threshold, the state is a `ConcurrentHashMap<GroupKey, ThresholdEntry>`:

```
Map<byte[], ThresholdEntry> groupThresholds;
Set<byte[]> seenGroups;  // or bloom filter

passes(groupValue, aggValue):
  if groupValue NOT IN seenGroups: return true  // unseen group, pass
  entry = groupThresholds.get(groupValue)
  if entry == null: return true                  // no threshold yet
  return aggValue >= entry.threshold             // (for MAX)
```

### Observer Placement

The observer hooks into the **local GROUP BY aggregate**, similar to M1-M3. But instead of a single `onMinMaxChanged()`, we need:
- `onMinMaxChanged(groupKey)` -- so the hook knows which group's threshold just changed
- The group key must be passed down to the aggregate function

**Challenge:** In AsterixDB's GROUP BY runtime, the aggregate function (`IAggregateEvaluator`) does NOT currently know which group it's processing. The runtime manages groups via the hash table and calls `step()` on the appropriate evaluator, but the evaluator has no group-key context.

**Possible approaches:**
1. **Group-aware wrapper aggregate:** Similar to `PlaqueLocalSqlMinMaxAggregateFunction`, but the `onMinMaxChanged()` hook also captures the group key. This requires the GROUP BY runtime to pass group-key context to the aggregate evaluator.
2. **Observer at the GROUP BY level:** Instead of hooking into the aggregate, observe at the GROUP BY operator level where the group key IS known. When a tuple enters a group and the aggregate changes, update the group's threshold.
3. **Post-aggregate check:** After `step()`, compare the aggregate's current value with the stored threshold for that group. This avoids modifying the aggregate but requires knowing the group key outside the evaluator.

### Filter Placement

Same as scalar PLAQUE: below the scan, before the exchange/join. The filter needs access to BOTH:
- The **group column** (`b`) -- to look up the group's threshold
- The **aggregate column** (`a`) -- to compare against the threshold

Both columns must be available at the filter's position in the plan (they come from the same scan).

### Rewrite Rule Changes

The `PlaqueRewriteRule` needs to be extended:
1. **Match pattern:** Instead of matching a standalone `AggregateOperator` (scalar case), match a `GroupByOperator` containing an `AggregateOperator` with `LOCAL_SQL_MAX`/`LOCAL_SQL_MIN`
2. **Extract group variable:** Identify the variable used as the GROUP BY key
3. **Filter needs two variables:** The inserted `SelectOperator` / `PlaqueFilterPOperator` must track both the aggregate variable AND the group variable
4. **Physical operator:** `PlaqueGroupFilterPOperator` -- similar to `PlaqueFilterPOperator` but takes two column indices (group column + agg column)


## Example Queries for Testing

```sql
-- Simple GROUP BY MAX
SELECT MAX(l_discount), l_shipmode
FROM Lineitem
GROUP BY l_shipmode;

-- GROUP BY MAX with join
SELECT MAX(l.l_extendedprice), l.l_shipmode
FROM Lineitem l, Orders o
WHERE l.l_orderkey = o.o_orderkey
  AND o.o_orderdate < '1995-01-01'
GROUP BY l.l_shipmode;

-- GROUP BY MIN
SELECT MIN(l_quantity), l_returnflag
FROM Lineitem
GROUP BY l_returnflag;

-- GROUP BY with many groups (high cardinality)
SELECT MAX(l_extendedprice), l_suppkey
FROM Lineitem
GROUP BY l_suppkey;
```


## Open Questions

1. **How to pass group-key context to the aggregate evaluator?** The `IAggregateEvaluator` interface has no group-key parameter. Need to trace how the GROUP BY runtime manages groups and where we can intercept.
2. **Should we limit to top-k groups (as the thesis suggests)?** For high-cardinality group keys (e.g., `l_suppkey` with 10K+ distinct values), maintaining per-group thresholds may have non-trivial memory overhead. The top-k approach bootstraps by sampling.
3. **How does the local/global GROUP BY split interact with the filter?** The local GROUP BY builds a hash table per partition. The filter sits below the GROUP BY, so it needs to share threshold state with the local GROUP BY's aggregates across partitions (same Layer 1/Layer 2 propagation as M2).
4. **Can we reuse `PlaqueThresholdRegistry` with composite keys?** E.g., key = `jobId:handle:groupValue`. This would give us per-group state sharing across partitions for free.
5. **What's the plan structure for GROUP BY queries in AsterixDB?** Need to EXPLAIN a GROUP BY query and trace the operator chain: DATASOURCE_SCAN -> ... -> GROUP_BY (local, with nested AGGREGATE) -> EXCHANGE -> GROUP_BY (global).


## Implementation Plan

*(To be filled in as we proceed)*


## Experiment Results

*(To be filled in as we run experiments)*
