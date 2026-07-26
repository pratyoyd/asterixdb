# PLAQUE on AsterixDB — Milestone 2 (Join Queries)


## Summary

Consider the query: "What is the highest price among all lineitems whose order was placed before 1995?" This joins Lineitem with Orders, filters by date, and takes the MAX of `l_extendedprice`. In a distributed system like AsterixDB, every lineitem tuple gets shuffled across the network to the right join partition, probed against the Orders hash table, and — if it survives — fed to the aggregate. Most of that work is wasted: the vast majority of lineitems have prices well below the final maximum.

**The idea:** As the query runs, the aggregate learns the running maximum — say 800 so far. Any lineitem with price below 800 is guaranteed to not be the answer, so we can throw it away *before* it gets shuffled and joined. We place a lightweight filter near the Lineitem scan that checks each tuple against the best-known maximum and drops anything too small.

**The challenge:** The filter sits near the scan (before the network shuffle), but the aggregate that learns the threshold sits above the join (after the shuffle) — potentially on a different machine. They can't share memory. Worse, machine A might discover a high value that machine B's filter needs to know about.

**The solution:** Two layers of cross-partition threshold propagation. Layer 1 (intra-node): all partitions on the same machine share one threshold in memory — zero network cost, immediate cross-partition benefit within that machine. Layer 2 (inter-node): periodically, each machine reports its best threshold to the cluster controller (CC), which broadcasts the global best back to all machines. This propagates thresholds across partitions on *different* machines — so machine B's filter learns about the high value discovered on machine A within milliseconds, not never.

Everything is tunable for experiments: cross-node propagation on/off, cross-node propagation frequency, and whether the filter is pushed below the join (strong) or kept above it (weak). Three knobs, one factorial experiment.


## Scope

Extend PLAQUE to handle equi-join queries with `SELECT MAX(col)` or `SELECT MIN(col)`. The motivating query:

```sql
SELECT MAX(l.l_extendedprice)
FROM Lineitem_10 l, Orders_10 o
WHERE l.l_orderkey = o.o_orderkey
  AND o.o_orderdate < '1995-01-01';
```

In M1, the filter and the observer (local aggregate) share state via the per-task `IOperatorEnvironment` registry because they are micro-operators in the same pipeline on the same Hyracks task. In M2, the filter sits below a hash-partition exchange (in the scan activity) while the observer sits above the join (in the probe activity). They are in **different activities, different tasks, potentially different nodes**. M2 introduces a two-layer threshold propagation architecture: NC-level shared state for intra-node propagation, and CC-mediated messaging for cross-node propagation.

```
GlobalAggregate(global-sql-max)
  +-- RANDOM_MERGE_EXCHANGE
     +-- LocalAggregate(local-sql-max)    <-- OBSERVER: updates threshold
        +-- [STREAM_PROJECT / ASSIGN]
           +-- HYBRID_HASH_JOIN(l_orderkey = o_orderkey)
              |-- HASH_PARTITION_EXCHANGE(l_orderkey)
              |    +-- PlaqueFilter(l_extendedprice)  <-- FILTER: drops cheap tuples
              |       +-- [ASSIGN]
              |          +-- DATASOURCE_SCAN(Lineitem)
              +-- HASH_PARTITION_EXCHANGE(o_orderkey)
                   +-- SELECT(o_orderdate < '1995-01-01')
                      +-- DATASOURCE_SCAN(Orders)
```

The filter is BELOW the hash-partition exchange (scan activity). The observer is ABOVE the join (probe activity). The hash-partition exchange shuffles tuples, so a filter on partition `i` may receive threshold updates from observers on partitions `j` and `k` — possibly on different nodes.


## Prerequisites

This document assumes M1 is fully implemented and working. You should have:
- `FilterState`, `FilterStateRegistry`, `PlaqueFilterRuntimeFactory`/`PlaqueFilterRuntime` in `asterix-runtime/.../operators/plaque/`
- `PlaqueLocalSqlMinMaxAggregateFunction` in `asterix-runtime/.../aggregates/std/`
- `PlaqueLocalAggregateEvaluatorFactory` in `asterix-runtime/.../operators/plaque/`
- `PlaqueFilterPOperator`, `PlaqueAggregatePOperator` in `asterix-algebra/.../operators/physical/`
- `PlaqueRewriteRule` in `asterix-algebra/.../optimizer/rules/`
- `onMinMaxChanged()` hook in `AbstractMinMaxAggregateFunction`
- Config plumbing: `compiler.plaque.enabled` flag


## Tunable parameters (new in M2)

All PLAQUE parameters should be settable per-query via `SET` statements (like `compiler.plaque.enabled`) and as server defaults in `cc.conf`. This is critical for research — every knob must be tunable so experiments can isolate the effect of each design choice.

### Parameter table

| Parameter | Key | Type | Default | Description |
|---|---|---|---|---|
| PLAQUE enabled | `compiler.plaque.enabled` | boolean | false | Master switch (from M1) |
| Cross-node propagation | `compiler.plaque.propagation` | boolean | true | Enable/disable Layer 2 (CC-mediated cross-node threshold propagation). When false, each NC uses only its locally observed threshold. Useful for measuring the marginal value of cross-node propagation. |
| Propagation interval | `compiler.plaque.propagation.interval` | integer | 5000 | Number of tuples between propagation attempts. Lower = faster propagation but more CC messages. Set to 1 for eager propagation (every improving tuple triggers a message). Set to MAX_INT to effectively disable periodic propagation (only propagate at `finish()`). |
| Filter push-through-join | `compiler.plaque.push.through.join` | boolean | true | When true, the rewrite rule attempts to push the filter below the hash-partition exchange (M2 strong placement). When false, the filter always stays above the join (M1-style weak placement). Useful for measuring the value of pre-join filtering vs. post-join filtering. |

### Config plumbing (four files, same pattern as M1)

**a) `AlgebricksConfig.java`** — add defaults:
```java
public static final boolean PLAQUE_PROPAGATION_DEFAULT = true;
public static final int PLAQUE_PROPAGATION_INTERVAL_DEFAULT = 5000;
public static final boolean PLAQUE_PUSH_THROUGH_JOIN_DEFAULT = true;
```

**b) `CompilerProperties.java`** — add enum entries after `COMPILER_PLAQUE_ENABLED`:
```java
COMPILER_PLAQUE_PROPAGATION(BOOLEAN, AlgebricksConfig.PLAQUE_PROPAGATION_DEFAULT,
        "Enable/disable cross-node PLAQUE threshold propagation"),
COMPILER_PLAQUE_PROPAGATION_INTERVAL(INTEGER, AlgebricksConfig.PLAQUE_PROPAGATION_INTERVAL_DEFAULT,
        "Tuples between cross-node propagation attempts"),
COMPILER_PLAQUE_PUSH_THROUGH_JOIN(BOOLEAN, AlgebricksConfig.PLAQUE_PUSH_THROUGH_JOIN_DEFAULT,
        "Push PLAQUE filter below hash-partition exchange through joins"),
```

And the key constants:
```java
public static final String COMPILER_PLAQUE_PROPAGATION = Option.COMPILER_PLAQUE_PROPAGATION.ini();
public static final String COMPILER_PLAQUE_PROPAGATION_INTERVAL = Option.COMPILER_PLAQUE_PROPAGATION_INTERVAL.ini();
public static final String COMPILER_PLAQUE_PUSH_THROUGH_JOIN = Option.COMPILER_PLAQUE_PUSH_THROUGH_JOIN.ini();
```

**c) `OptimizationConfUtil.java`** — read and propagate (after `setPlaqueMode`):
```java
boolean plaquePropagation = getBoolean(querySpecificConfig,
        CompilerProperties.COMPILER_PLAQUE_PROPAGATION, AlgebricksConfig.PLAQUE_PROPAGATION_DEFAULT);
int plaquePropagationInterval = getInt(querySpecificConfig,
        CompilerProperties.COMPILER_PLAQUE_PROPAGATION_INTERVAL,
        AlgebricksConfig.PLAQUE_PROPAGATION_INTERVAL_DEFAULT);
boolean plaquePushThroughJoin = getBoolean(querySpecificConfig,
        CompilerProperties.COMPILER_PLAQUE_PUSH_THROUGH_JOIN,
        AlgebricksConfig.PLAQUE_PUSH_THROUGH_JOIN_DEFAULT);
physOptConf.setPlaquePropagation(plaquePropagation);
physOptConf.setPlaquePropagationInterval(plaquePropagationInterval);
physOptConf.setPlaquePushThroughJoin(plaquePushThroughJoin);
```

**d) `PhysicalOptimizationConfig.java`** — add getters/setters:
```java
private static final String PLAQUE_PROPAGATION = "PLAQUE_PROPAGATION";
private static final String PLAQUE_PROPAGATION_INTERVAL = "PLAQUE_PROPAGATION_INTERVAL";
private static final String PLAQUE_PUSH_THROUGH_JOIN = "PLAQUE_PUSH_THROUGH_JOIN";

public boolean getPlaquePropagation() {
    return getBoolean(PLAQUE_PROPAGATION, AlgebricksConfig.PLAQUE_PROPAGATION_DEFAULT);
}
public void setPlaquePropagation(boolean v) { setBoolean(PLAQUE_PROPAGATION, v); }

public int getPlaquePropagationInterval() {
    return getInt(PLAQUE_PROPAGATION_INTERVAL, AlgebricksConfig.PLAQUE_PROPAGATION_INTERVAL_DEFAULT);
}
public void setPlaquePropagationInterval(int v) { setInt(PLAQUE_PROPAGATION_INTERVAL, v); }

public boolean getPlaquePushThroughJoin() {
    return getBoolean(PLAQUE_PUSH_THROUGH_JOIN, AlgebricksConfig.PLAQUE_PUSH_THROUGH_JOIN_DEFAULT);
}
public void setPlaquePushThroughJoin(boolean v) { setBoolean(PLAQUE_PUSH_THROUGH_JOIN, v); }
```

### How each parameter flows into the runtime

- **`plaque.propagation`**: Read by `PlaqueAggregatePOperator.contributeRuntimeOperator()` from `context.getPhysicalOptimizationConfig()`. Passed into `PlaqueLocalAggregateEvaluatorFactory` as a boolean. When false, the factory does not resolve the message broker and `maybePropagateThreshold()` becomes a no-op.

- **`plaque.propagation.interval`**: Read the same way. Passed into `PlaqueLocalAggregateEvaluatorFactory`, which passes it to `PlaqueLocalSqlMinMaxAggregateFunction` as the propagation interval. Replaces the hardcoded `5000`.

- **`plaque.push.through.join`**: Read by `PlaqueRewriteRule.rewritePost()` from `context.getPhysicalOptimizationConfig()`. When false, the rule's `findInsertionPoint()` method treats `INNERJOIN`/`LEFTOUTERJOIN` as opaque operators (does not enter the probe side), falling back to M1-style placement above the join.

### Usage in experiments

```sql
-- Baseline: no PLAQUE
SELECT MAX(l.l_extendedprice) FROM Lineitem_10 l, Orders_10 o ...;

-- PLAQUE with full M2 (strong filter + cross-node propagation)
SET `compiler.plaque.enabled` "true";
SELECT MAX(l.l_extendedprice) FROM Lineitem_10 l, Orders_10 o ...;

-- PLAQUE with strong filter but NO cross-node propagation (measure Layer 1 alone)
SET `compiler.plaque.enabled` "true";
SET `compiler.plaque.propagation` "false";
SELECT MAX(l.l_extendedprice) FROM Lineitem_10 l, Orders_10 o ...;

-- PLAQUE with weak filter only (M1-style, above join — measure filter placement effect)
SET `compiler.plaque.enabled` "true";
SET `compiler.plaque.push.through.join` "false";
SELECT MAX(l.l_extendedprice) FROM Lineitem_10 l, Orders_10 o ...;

-- PLAQUE with aggressive propagation (every tuple)
SET `compiler.plaque.enabled` "true";
SET `compiler.plaque.propagation.interval` "1";
SELECT MAX(l.l_extendedprice) FROM Lineitem_10 l, Orders_10 o ...;

-- PLAQUE with lazy propagation (only at finish)
SET `compiler.plaque.enabled` "true";
SET `compiler.plaque.propagation.interval` "2147483647";
SELECT MAX(l.l_extendedprice) FROM Lineitem_10 l, Orders_10 o ...;
```

These knobs let you run a full factorial experiment: {strong/weak filter} × {propagation on/off} × {propagation interval 1/100/5000/∞}.


## Reading tasks

Before writing code, read these files to understand the join plan structure, the messaging infrastructure, and the NC runtime model.

### 1. `OptimizedHybridHashJoinOperatorDescriptor` — the hash join operator
**Path:** `hyracks-fullstack/hyracks/hyracks-dataflow-std/src/main/java/org/apache/hyracks/dataflow/std/join/OptimizedHybridHashJoinOperatorDescriptor.java`

This operator has two inputs and creates two activities:

```java
private static final int BUILD_AND_PARTITION_ACTIVITY_ID = 0;
private static final int PARTITION_AND_JOIN_ACTIVITY_ID = 1;
```

In `contributeActivities()`:
```java
ActivityId buildAid = new ActivityId(odId, BUILD_AND_PARTITION_ACTIVITY_ID);
ActivityId probeAid = new ActivityId(odId, PARTITION_AND_JOIN_ACTIVITY_ID);
PartitionAndBuildActivityNode phase1 = new PartitionAndBuildActivityNode(buildAid, probeAid);
ProbeAndJoinActivityNode phase2 = new ProbeAndJoinActivityNode(probeAid, buildAid);

builder.addActivity(this, phase1);
builder.addSourceEdge(1, phase1, 0);    // input 1 = build side (Orders)

builder.addActivity(this, phase2);
builder.addSourceEdge(0, phase2, 0);    // input 0 = probe side (Lineitem)

builder.addBlockingEdge(phase1, phase2);  // build completes before probe starts
builder.addTargetEdge(0, phase2, 0);      // output from probe activity
```

Key insight: **Input 0 is the probe side** (Lineitem = outer relation), **Input 1 is the build side** (Orders = inner relation). The blocking edge ensures all build tuples are partitioned before any probe tuple is processed. The probe activity produces the join output.

In the Algebricks plan, the `INNERJOIN` operator maps to this descriptor. The probe side is input 0 of the join (left child in the plan tree), and the build side is input 1 (right child).

### 2. `INCMessageBroker` — NC-to-NC and NC-to-CC messaging
**Path:** `asterixdb/asterix-common/src/main/java/org/apache/asterix/common/messaging/api/INCMessageBroker.java`

```java
public interface INCMessageBroker extends IMessageBroker {
    void sendMessageToPrimaryCC(ICcAddressedMessage message) throws Exception;
    void sendMessageToCC(CcId ccId, ICcAddressedMessage message) throws Exception;
    void sendMessageToNC(String nodeId, INcAddressedMessage message) throws Exception;
    void queueReceivedMessage(INcAddressedMessage msg);
    // ...
}
```

NC-to-CC messages implement `ICcAddressedMessage` and are handled on the CC by calling `handle(ICcApplicationContext appCtx)`. CC-to-NC messages implement `INcAddressedMessage` and are handled on the NC by calling `handle(INcApplicationContext appCtx)`.

Access path from a runtime operator:
```java
INCServiceContext serviceCtx = ctx.getJobletContext().getServiceContext();
INCMessageBroker broker = (INCMessageBroker) serviceCtx.getMessageBroker();
String myNodeId = serviceCtx.getNodeId();
```

### 3. `ICCMessageBroker` — CC-to-NC messaging
**Path:** `asterixdb/asterix-common/src/main/java/org/apache/asterix/common/messaging/api/ICCMessageBroker.java`

```java
public interface ICCMessageBroker extends IMessageBroker {
    boolean sendApplicationMessageToNC(INcAddressedMessage msg, String nodeId) throws Exception;
    // ...
}
```

The CC broker is obtained from `appCtx.getServiceContext().getMessageBroker()` inside a `ICcAddressedMessage.handle()` method. The CC can enumerate participating nodes via `appCtx.getClusterStateManager().getParticipantNodes()`.

### 4. `ICcAddressedMessage` / `INcAddressedMessage` — message contracts
**Path:** `asterixdb/asterix-common/src/main/java/org/apache/asterix/common/messaging/api/ICcAddressedMessage.java`
**Path:** `asterixdb/asterix-common/src/main/java/org/apache/asterix/common/messaging/api/INcAddressedMessage.java`

Both are `@FunctionalInterface` extending `IMessage` (which extends `Serializable`). Each has a single `handle()` method:

```java
// NC -> CC
public interface ICcAddressedMessage extends IMessage {
    void handle(ICcApplicationContext appCtx) throws HyracksDataException, InterruptedException;
}

// CC -> NC
public interface INcAddressedMessage extends IMessage {
    void handle(INcApplicationContext appCtx) throws HyracksDataException, InterruptedException;
}
```

Messages are serialized via Java serialization and transported over Hyracks' TCP MuxDemux layer. All fields must be `Serializable`.

### 5. `ResourceIdRequestMessage` — example NC-to-CC message
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/message/ResourceIdRequestMessage.java`

A concrete example of an `ICcAddressedMessage`. Fields: `String src` (the sending NC's node ID), `int blockSize`. In `handle()`:

```java
public void handle(ICcApplicationContext appCtx) throws HyracksDataException, InterruptedException {
    ICCMessageBroker broker = (ICCMessageBroker) appCtx.getServiceContext().getMessageBroker();
    ResourceIdRequestResponseMessage response = new ResourceIdRequestResponseMessage();
    // ... fill in response ...
    broker.sendRealTimeApplicationMessageToNC(response, src);
}
```

This is the exact pattern PLAQUE will follow: NC sends threshold update to CC, CC broadcasts back to all NCs.

### 6. `IClusterStateManager.getParticipantNodes()` — enumerating NCs
**Path:** `asterixdb/asterix-common/src/main/java/org/apache/asterix/common/cluster/IClusterStateManager.java`

```java
Set<String> getParticipantNodes();
Set<String> getParticipantNodes(boolean excludePendingRemoval);
```

Used in the CC-side message handler to broadcast threshold updates to all NCs. Obtained via `appCtx.getClusterStateManager()`.

### 7. `INCServiceContext.getNodeId()` — identifying the current NC
**Path:** `hyracks-fullstack/hyracks/hyracks-api/src/main/java/org/apache/hyracks/api/application/INCServiceContext.java`

```java
String getNodeId();
```

Used by the observer to identify itself as the source NC when sending threshold updates to the CC.

### 8. The M1 `PlaqueRewriteRule` — the starting point for extension
**Path:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/optimizer/rules/PlaqueRewriteRule.java`

The M1 rule walks down from the local aggregate through ASSIGN/PROJECT/EXCHANGE operators to find the insertion point for the filter. In M2, the walk must also traverse through INNERJOIN operators, entering the probe side (input 0) and continuing down through the hash-partition exchange to the scan.


## Design decisions

### NC-level registry instead of per-task registry

M1 used the per-task `IOperatorEnvironment` registry (`ctx.setStateObject / getStateObject`) because the filter and observer share the same task. In M2, the filter and observer are on different tasks (different activities). They cannot share state via the per-task registry.

Solution: a **static NC-level singleton** (`PlaqueThresholdRegistry`), keyed by `(JobId, handle)`. All tasks on the same NC for the same job access the same `PlaqueThresholdState` instance. This gives immediate intra-node propagation with zero messaging overhead.

Why a static singleton and not a service registered on `INcApplicationContext`:
- `INcApplicationContext` has no generic `getObject(key)` / `setObject(key, value)` API. Adding one would require Hyracks API changes.
- A static singleton is pragmatic for a research prototype. The M1 per-task approach was cleaner but cannot work cross-task.
- The singleton is scoped by `(JobId, handle)` keys and entries are cleaned up on job completion, so there is no cross-query bleed.

### Unifying M1 and M2 under the NC-level registry

The M1 single-table case should continue to work with no code path divergence. We migrate M1 to also use the NC-level `PlaqueThresholdRegistry` instead of the per-task `FilterStateRegistry`. This is safe because:
- On a single-table query, the filter and observer are still micro-operators on the same task. They access the same `PlaqueThresholdState` from the NC-level registry. The behavior is identical to the per-task approach.
- On a join query, the filter and observer are on different tasks but potentially on the same NC. They access the same `PlaqueThresholdState`, enabling immediate cross-partition propagation within the NC.

The M1 `FilterState` and `FilterStateRegistry` classes are retired (or kept as dead code). All code paths use `PlaqueThresholdRegistry` and `PlaqueThresholdState`.

### Two-layer propagation architecture

**Layer 1 (intra-node, zero overhead):** All tasks on the same NC share a `PlaqueThresholdState` via the NC-level registry. On single-node deployments, this gives full cross-partition propagation for free.

**Layer 2 (inter-node, CC-mediated):** Each NC periodically sends its best threshold to the CC. The CC maintains the global best and broadcasts updates to all NCs. Each NC merges the received global threshold with its local threshold.

Why CC-mediated rather than NC-to-NC direct:
- The CC already knows all participating NCs and has messaging infrastructure to reach them.
- NC-to-NC would require O(N^2) connections or a gossip protocol. CC-mediated is O(N) messages per propagation round.
- The CC acts as the authoritative merge point — threshold updates are monotonic, so the CC simply keeps the max of all received values.

### Propagation frequency

Not every threshold update triggers a network message. That would flood the network. Instead:
- The observer marks the NC-level state as "dirty" when the threshold improves.
- After processing each frame in the observer's `nextFrame()`, if the state is dirty, it sends an update message to the CC.
- The CC broadcasts to all NCs only when the global threshold actually improves (not on every received message).

Frame-level batching is a natural granularity: a frame contains many tuples (typically thousands), so the overhead is amortized. The propagation delay is bounded by the time to process one frame — typically sub-millisecond.

### Variable tracing through the join

The aggregated variable (e.g., `$$extendedprice`) must originate from the **probe side** of the join for the filter to be pushable below the join. The join operator does not produce new variables — it passes through variables from both inputs. The rewrite rule checks whether the variable is produced by operators on the probe side (input 0).

**Why the filter cannot be pushed to the build side:** In a hash join, the build side is fully consumed into the hash table *before* the probe phase begins. The observer only starts learning thresholds during the probe phase (when surviving tuples reach the aggregate). By that point, the build-side scan is already complete — there is no running scan left to filter. The filter can only be useful on the probe side, which is actively streaming tuples while the observer is learning.

If the aggregated variable comes from the build side (e.g., `MAX(o.o_totalprice)` where Orders is the build side), the filter cannot cross the join. Instead, the rule falls back to M1-style placement: the filter is placed above the join, below the aggregate, in the same pipeline. This is a "weak" filter — it can't prevent tuples from going through the expensive shuffle and join, but still reduces tuples flowing through the merge exchange and into the global aggregate. The M1 placement walk already handles this correctly: when it encounters the join operator (which is not ASSIGN, PROJECT, or EXCHANGE), it stops and inserts above it.

In summary, the rule has three outcomes:
1. **No join in the path** → M1 behavior (filter between scan and aggregate)
2. **Join present, variable from probe side** → M2 strong filter pushed below the probe-side exchange (saves shuffle + join work)
3. **Join present, variable from build side** → M1-style weak filter above the join (saves only post-join work, but still correct and useful)

If `compiler.plaque.push.through.join` is disabled (set to false), all join cases fall back to outcome 3 regardless of which side the variable comes from.

### Thread safety model

M1 was single-threaded (one task, one pipeline). M2 has multiple concurrent threads accessing the same `PlaqueThresholdState`:
- Multiple observer threads calling `update()` concurrently (one per join partition on this NC)
- Multiple filter threads calling `passes()` concurrently (one per scan partition on this NC)
- `update()` and `passes()` running concurrently across different partitions

Solution: **Copy-on-write with volatile reads**.
- `update()` uses `synchronized` to serialize writes. Writes are infrequent (only when the threshold improves).
- `passes()` reads a `volatile` snapshot of the threshold bytes without locking. Reads are on every tuple — must be lock-free.
- A stale read in `passes()` is safe: it might pass a tuple that could have been filtered (reduced effectiveness), but never drops a tuple that should pass (correctness preserved).


## Components to build

### 1. `PlaqueThresholdState` (NC-level, thread-safe)
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueThresholdState.java`

Replaces M1's `FilterState`. Unlike `FilterState` which extends `AbstractStateObject` (for the per-task registry), this is a plain Java object stored in the NC-level registry. Must be thread-safe.

```java
package org.apache.asterix.runtime.operators.plaque;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.asterix.dataflow.data.nontagged.comparators.AGenericAscBinaryComparatorFactory;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.exceptions.HyracksDataException;

public class PlaqueThresholdState {

    private final boolean isMax;
    private final IBinaryComparator comparator;

    // Written under synchronized(this), read via volatile snapshot
    private volatile byte[] thresholdSnapshot;     // immutable once published
    private volatile int thresholdSnapshotLength;
    private volatile boolean initialized;

    // Dirty flag: set by update(), cleared by the propagation sender
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public PlaqueThresholdState(boolean isMax) {
        this.isMax = isMax;
        this.comparator = AGenericAscBinaryComparatorFactory.INSTANCE.createBinaryComparator();
        this.initialized = false;
    }

    /**
     * Called by observer threads (potentially multiple partitions on the same NC).
     * Serialized via synchronized. Only copies bytes when the threshold actually improves.
     */
    public synchronized void update(byte[] data, int start, int len) throws HyracksDataException {
        if (!initialized) {
            byte[] copy = new byte[len];
            System.arraycopy(data, start, copy, 0, len);
            thresholdSnapshot = copy;
            thresholdSnapshotLength = len;
            initialized = true;
            dirty.set(true);
            return;
        }
        int cmp = comparator.compare(data, start, len, thresholdSnapshot, 0, thresholdSnapshotLength);
        if ((isMax && cmp > 0) || (!isMax && cmp < 0)) {
            byte[] copy = new byte[len];
            System.arraycopy(data, start, copy, 0, len);
            // Publish atomically: write length before reference (volatile write on
            // thresholdSnapshot acts as a store-store fence for thresholdSnapshotLength)
            thresholdSnapshotLength = len;
            thresholdSnapshot = copy;
            dirty.set(true);
        }
    }

    /**
     * Called by filter threads (potentially multiple partitions on the same NC).
     * Lock-free: reads volatile snapshot. A stale read is safe — it only reduces
     * filtering effectiveness, never drops a tuple that should pass.
     *
     * Returns true if the value should pass through (>= threshold for MAX, <= for MIN).
     */
    public boolean passes(byte[] data, int start, int len) throws HyracksDataException {
        if (!initialized) {
            return true;
        }
        // Read snapshot — may be slightly stale, which is safe
        byte[] snap = thresholdSnapshot;
        int snapLen = thresholdSnapshotLength;
        int cmp = comparator.compare(data, start, len, snap, 0, snapLen);
        return isMax ? cmp >= 0 : cmp <= 0;
    }

    /**
     * Merges an externally received threshold (from CC broadcast) with the local threshold.
     * Called from the message handler thread on this NC.
     */
    public synchronized void mergeGlobal(byte[] data, int start, int len) throws HyracksDataException {
        // Delegate to update() — same monotonic merge logic
        update(data, start, len);
        // Don't set dirty — this came FROM the CC, don't echo it back
        // Actually update() already set dirty. Clear it:
        // No — clearing is racy. Better: the propagation sender checks dirty
        // before sending. The worst case is one extra message. Acceptable.
    }

    /**
     * Returns true if the threshold has improved since the last clearDirty() call.
     */
    public boolean isDirty() {
        return dirty.get();
    }

    /**
     * Clears the dirty flag. Called by the propagation sender after sending.
     */
    public void clearDirty() {
        dirty.set(false);
    }

    /**
     * Returns a snapshot of the current threshold bytes for sending to the CC.
     * Returns null if not initialized.
     */
    public synchronized byte[] getThresholdSnapshot() {
        if (!initialized) {
            return null;
        }
        byte[] copy = new byte[thresholdSnapshotLength];
        System.arraycopy(thresholdSnapshot, 0, copy, 0, thresholdSnapshotLength);
        return copy;
    }

    /**
     * Returns the length of the current threshold snapshot.
     */
    public int getThresholdSnapshotLength() {
        return thresholdSnapshotLength;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isMax() {
        return isMax;
    }
}
```

**Thread safety analysis:**
- `update()` is `synchronized` — only one writer at a time. Multiple observer partitions on the same NC are serialized here. This is acceptable because `update()` is only called when the threshold actually improves (i.e., `onMinMaxChanged()` fires), which becomes increasingly rare as the threshold converges.
- `passes()` reads `volatile` fields without locking. The `IBinaryComparator` instance used in `passes()` is **not thread-safe** (it has mutable internal state — `leftValue` and `rightValue` in `AGenericAscBinaryComparator`). To fix this, each filter thread must create its own comparator instance. See `PlaqueFilterRuntime` below.
- `mergeGlobal()` is `synchronized` — same as `update()`. Called from the NC message handler thread, which is separate from the operator threads.

**Comparator thread-safety fix:** The `passes()` method shown above uses `this.comparator`, which is shared across threads. This is **incorrect** because `AGenericAscBinaryComparator` has mutable internal state. The fix: `passes()` should NOT use a shared comparator. Instead, each `PlaqueFilterRuntime` creates its own comparator and calls a variant that takes the comparator as a parameter:

```java
/**
 * Thread-safe variant: caller supplies their own comparator instance.
 */
public boolean passes(byte[] data, int start, int len,
        IBinaryComparator threadLocalComparator) throws HyracksDataException {
    if (!initialized) {
        return true;
    }
    byte[] snap = thresholdSnapshot;
    int snapLen = thresholdSnapshotLength;
    int cmp = threadLocalComparator.compare(data, start, len, snap, 0, snapLen);
    return isMax ? cmp >= 0 : cmp <= 0;
}
```

The `update()` method uses `this.comparator` inside a `synchronized` block, so it is safe (only one thread at a time).


### 2. `PlaqueThresholdRegistry` (NC-level singleton)
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueThresholdRegistry.java`

A static singleton holding all active threshold states for all jobs on this NC. Keyed by a composite string `(jobId + ":" + handle)`.

```java
package org.apache.asterix.runtime.operators.plaque;

import java.util.concurrent.ConcurrentHashMap;

import org.apache.hyracks.api.job.JobId;

public final class PlaqueThresholdRegistry {

    public static final PlaqueThresholdRegistry INSTANCE = new PlaqueThresholdRegistry();

    private final ConcurrentHashMap<String, PlaqueThresholdState> states = new ConcurrentHashMap<>();

    private PlaqueThresholdRegistry() {}

    /**
     * Gets or creates a PlaqueThresholdState for the given (jobId, handle) pair.
     * Thread-safe: uses ConcurrentHashMap.computeIfAbsent.
     */
    public PlaqueThresholdState getOrCreate(JobId jobId, String handle, boolean isMax) {
        String key = makeKey(jobId, handle);
        return states.computeIfAbsent(key, k -> new PlaqueThresholdState(isMax));
    }

    /**
     * Gets an existing PlaqueThresholdState, or null if none exists.
     */
    public PlaqueThresholdState get(JobId jobId, String handle) {
        return states.get(makeKey(jobId, handle));
    }

    /**
     * Removes all entries for a given job. Called on job completion.
     */
    public void removeJob(JobId jobId) {
        String prefix = jobId.toString() + ":";
        states.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
    }

    /**
     * Merges a global threshold received from the CC into the local state.
     * If no state exists for this (jobId, handle), creates one.
     */
    public void mergeGlobal(JobId jobId, String handle, byte[] thresholdBytes, int len, boolean isMax)
            throws org.apache.hyracks.api.exceptions.HyracksDataException {
        PlaqueThresholdState state = getOrCreate(jobId, handle, isMax);
        state.mergeGlobal(thresholdBytes, 0, len);
    }

    private static String makeKey(JobId jobId, String handle) {
        return jobId.toString() + ":" + handle;
    }
}
```

**Lifecycle management:** Entries must be removed when a job completes. The NC does not have `IJobLifecycleListener` (that is a CC-side interface). Instead, we clean up lazily:
- Option A: The PlaqueFilterRuntime and PlaqueAggregateRuntime call `PlaqueThresholdRegistry.INSTANCE.removeJob(jobId)` in their `close()` methods. The first `close()` removes the entry; subsequent ones are no-ops (ConcurrentHashMap is safe for this).
- Option B: A background sweep that removes entries for JobIds no longer active. Overkill for a prototype.

We go with Option A. Both the filter and observer call `removeJob()` in `close()`. The ConcurrentHashMap handles the race where multiple partitions close concurrently.


### 3. `PlaqueFilterRuntime` and `PlaqueFilterRuntimeFactory` changes
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueFilterRuntimeFactory.java`

The M1 filter resolves state from the per-task `FilterStateRegistry`. In M2, it resolves from the NC-level `PlaqueThresholdRegistry` instead. Additionally, each filter runtime creates its own `IBinaryComparator` instance for thread-safe `passes()` calls.

Changes to `PlaqueFilterRuntime`:

```java
public class PlaqueFilterRuntime extends AbstractOneInputOneOutputOneFramePushRuntime {
    private final int columnIdx;
    private final boolean isMax;
    private final String handle;
    private PlaqueThresholdState thresholdState;  // from NC-level registry
    private IBinaryComparator threadLocalComparator;  // per-thread comparator
    private IHyracksTaskContext ctx;
    private JobId jobId;

    PlaqueFilterRuntime(IHyracksTaskContext ctx, int columnIdx, boolean isMax, String handle) {
        this.ctx = ctx;
        this.columnIdx = columnIdx;
        this.isMax = isMax;
        this.handle = handle;
    }

    @Override
    public void open() throws HyracksDataException {
        initAccessAppendRef(ctx);
        jobId = ctx.getJobletContext().getJobId();
        // Resolve from NC-level registry (works for both M1 and M2 queries)
        thresholdState = PlaqueThresholdRegistry.INSTANCE.getOrCreate(jobId, handle, isMax);
        // Each filter thread gets its own comparator (AGenericAscBinaryComparator
        // has mutable internal state, not thread-safe for concurrent passes() calls)
        threadLocalComparator = AGenericAscBinaryComparatorFactory.INSTANCE.createBinaryComparator();
        super.open();
    }

    @Override
    public void nextFrame(ByteBuffer buffer) throws HyracksDataException {
        tAccess.reset(buffer);
        int nTuple = tAccess.getTupleCount();
        for (int t = 0; t < nTuple; t++) {
            tRef.reset(tAccess, t);
            byte[] data = tRef.getFieldData(columnIdx);
            int start = tRef.getFieldStart(columnIdx);
            int len = tRef.getFieldLength(columnIdx);
            byte typeTag = data[start];

            // Pass NULL/MISSING unchanged — the aggregate handles them
            if (typeTag == ATypeTag.SERIALIZED_NULL_TYPE_TAG
                    || typeTag == ATypeTag.SERIALIZED_MISSING_TYPE_TAG
                    || typeTag == ATypeTag.SERIALIZED_SYSTEM_NULL_TYPE_TAG) {
                appendTupleToFrame(t);
                continue;
            }

            // Thread-safe read with per-thread comparator
            if (thresholdState.passes(data, start, len, threadLocalComparator)) {
                appendTupleToFrame(t);
            }
        }
    }

    @Override
    public void close() throws HyracksDataException {
        super.close();
        // Clean up NC-level registry entry for this job
        PlaqueThresholdRegistry.INSTANCE.removeJob(jobId);
    }

    @Override
    public void flush() throws HyracksDataException {
        appender.flush(writer);
    }
}
```

The `PlaqueFilterRuntimeFactory` changes are minimal — remove the `FilterStateRegistry` import and ensure the constructor parameters are the same. No structural changes needed in the factory.


### 4. `PlaqueLocalAggregateEvaluatorFactory` changes (observer side)
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueLocalAggregateEvaluatorFactory.java`

The factory resolves the `PlaqueThresholdState` from the NC-level registry instead of the per-task `FilterStateRegistry`. It also triggers cross-node propagation.

```java
public class PlaqueLocalAggregateEvaluatorFactory implements IAggregateEvaluatorFactory {
    private static final long serialVersionUID = 2L;  // bump from 1L

    private final IScalarEvaluatorFactory[] args;
    private final boolean isMin;
    private final SourceLocation sourceLoc;
    private final IAType aggFieldType;
    private final String handle;
    private final boolean isMax;
    private final boolean propagationEnabled;   // from compiler.plaque.propagation
    private final int propagationInterval;      // from compiler.plaque.propagation.interval

    public PlaqueLocalAggregateEvaluatorFactory(IScalarEvaluatorFactory[] args, boolean isMin,
            SourceLocation sourceLoc, IAType aggFieldType, String handle, boolean isMax,
            boolean propagationEnabled, int propagationInterval) {
        this.args = args;
        this.isMin = isMin;
        this.sourceLoc = sourceLoc;
        this.aggFieldType = aggFieldType;
        this.handle = handle;
        this.isMax = isMax;
        this.propagationEnabled = propagationEnabled;
        this.propagationInterval = propagationInterval;
    }

    @Override
    public IAggregateEvaluator createAggregateEvaluator(IEvaluatorContext ctx) throws HyracksDataException {
        JobId jobId = ctx.getTaskContext().getJobletContext().getJobId();
        PlaqueThresholdState state = PlaqueThresholdRegistry.INSTANCE.getOrCreate(jobId, handle, isMax);
        PlaqueLocalSqlMinMaxAggregateFunction agg = new PlaqueLocalSqlMinMaxAggregateFunction(
                args, ctx, isMin, sourceLoc, aggFieldType, state, jobId, handle,
                propagationInterval);
        // Resolve message broker for cross-node propagation (Layer 2)
        if (propagationEnabled) {
            try {
                INCServiceContext serviceCtx = (INCServiceContext) ctx.getTaskContext()
                        .getJobletContext().getServiceContext();
                INCMessageBroker broker = (INCMessageBroker) serviceCtx.getMessageBroker();
                agg.setMessageBroker(broker);
            } catch (ClassCastException e) {
                // Test environment without AsterixDB messaging — Layer 2 disabled
            }
        }
        return agg;
    }
}
```


### 5. `PlaqueLocalSqlMinMaxAggregateFunction` changes
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/aggregates/std/PlaqueLocalSqlMinMaxAggregateFunction.java`

The observer now uses `PlaqueThresholdState` (NC-level) instead of `FilterState` (per-task). It also triggers cross-node propagation after processing each frame.

```java
public class PlaqueLocalSqlMinMaxAggregateFunction extends SqlMinMaxAggregateFunction {

    private final PlaqueThresholdState thresholdState;
    private final JobId jobId;
    private final String handle;
    private INCMessageBroker messageBroker;  // resolved lazily
    private boolean messageBrokerResolved;

    PlaqueLocalSqlMinMaxAggregateFunction(IScalarEvaluatorFactory[] args, IEvaluatorContext context,
            boolean isMin, SourceLocation sourceLoc, IAType aggFieldType,
            PlaqueThresholdState thresholdState, JobId jobId, String handle,
            int propagationInterval)
            throws HyracksDataException {
        super(args, context, isMin, Type.LOCAL, sourceLoc, aggFieldType);
        this.thresholdState = thresholdState;
        this.jobId = jobId;
        this.handle = handle;
        this.propagationInterval = propagationInterval;
        this.messageBrokerResolved = false;
    }

    @Override
    protected void onMinMaxChanged() throws HyracksDataException {
        // Push the new running min/max into the NC-level shared state.
        thresholdState.update(
                outputVal.getByteArray(),
                outputVal.getStartOffset(),
                outputVal.getLength());
    }

    /**
     * Called by the aggregate runtime after each frame is processed.
     * Sends threshold update to CC if the local threshold has improved.
     * This method is called from PlaqueAggregateRuntime.nextFrame() — see below.
     */
    public void maybePropagateThreshold() {
        if (!thresholdState.isDirty()) {
            return;
        }
        try {
            if (!messageBrokerResolved) {
                // Lazy resolution: the IEvaluatorContext does not directly expose
                // the message broker, but we can access it via the task context.
                // This requires the aggregate to hold a reference to the task context.
                // See PlaqueAggregateRuntime for how this is plumbed.
                messageBrokerResolved = true;
                // messageBroker is set by the PlaqueAggregateRuntime after construction
            }
            if (messageBroker == null) {
                return;  // single-node or no broker available
            }
            byte[] snapshot = thresholdState.getThresholdSnapshot();
            if (snapshot == null) {
                return;
            }
            thresholdState.clearDirty();
            PlaqueThresholdUpdateMessage msg = new PlaqueThresholdUpdateMessage(
                    jobId, handle, snapshot, snapshot.length, thresholdState.isMax());
            messageBroker.sendMessageToPrimaryCC(msg);
        } catch (Exception e) {
            // Swallow: propagation failure reduces effectiveness but not correctness.
            // Log at debug level to avoid flooding.
        }
    }

    /**
     * Sets the message broker for cross-node propagation. Called by the
     * PlaqueAggregateRuntime or the evaluator factory after construction.
     */
    public void setMessageBroker(INCMessageBroker broker) {
        this.messageBroker = broker;
        this.messageBrokerResolved = true;
    }
}
```

**Message broker resolution:** The `IEvaluatorContext` provides `getTaskContext()`, and from there:
```java
INCServiceContext serviceCtx = ctx.getTaskContext().getJobletContext().getServiceContext();
INCMessageBroker broker = (INCMessageBroker) serviceCtx.getMessageBroker();
```

The `PlaqueLocalAggregateEvaluatorFactory.createAggregateEvaluator()` should resolve the broker and pass it to the aggregate function:

```java
@Override
public IAggregateEvaluator createAggregateEvaluator(IEvaluatorContext ctx) throws HyracksDataException {
    JobId jobId = ctx.getTaskContext().getJobletContext().getJobId();
    PlaqueThresholdState state = PlaqueThresholdRegistry.INSTANCE.getOrCreate(jobId, handle, isMax);
    PlaqueLocalSqlMinMaxAggregateFunction agg = new PlaqueLocalSqlMinMaxAggregateFunction(
            args, ctx, isMin, sourceLoc, aggFieldType, state, jobId, handle);
    // Resolve message broker for cross-node propagation
    try {
        INCServiceContext serviceCtx = (INCServiceContext) ctx.getTaskContext()
                .getJobletContext().getServiceContext();
        INCMessageBroker broker = (INCMessageBroker) serviceCtx.getMessageBroker();
        agg.setMessageBroker(broker);
    } catch (ClassCastException e) {
        // Running in a test environment without AsterixDB messaging — no propagation
    }
    return agg;
}
```

**Triggering propagation:** The `maybePropagateThreshold()` method must be called after each frame. The standard `AggregatePushRuntime.nextFrame()` calls `aggEvals[i].step(tuple)` for each tuple but does not have a post-frame hook. Two approaches:

**Approach A (recommended):** Override `AggregatePushRuntime` to add a post-frame callback. Create `PlaqueAggregatePushRuntime` that extends `AggregatePushRuntime`, overrides `nextFrame()`, calls `super.nextFrame()`, then calls `maybePropagateThreshold()` on the PLAQUE aggregate evaluator.

**Approach B (simpler but less clean):** Add a frame counter in `PlaqueLocalSqlMinMaxAggregateFunction.step()` and call `maybePropagateThreshold()` every N tuples (e.g., every 10000). This avoids modifying the aggregate runtime but couples frame-level batching to tuple count.

We go with **Approach A**. See component 10 below for `PlaqueAggregatePushRuntime`.


### 6. `PlaqueThresholdUpdateMessage` (NC -> CC)
**Path:** `asterixdb/asterix-app/src/main/java/org/apache/asterix/app/message/PlaqueThresholdUpdateMessage.java`

Sent from an NC's observer to the CC when the local threshold improves.

```java
package org.apache.asterix.app.message;

import org.apache.asterix.common.dataflow.ICcApplicationContext;
import org.apache.asterix.common.messaging.api.ICCMessageBroker;
import org.apache.asterix.common.messaging.api.ICcAddressedMessage;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;

public class PlaqueThresholdUpdateMessage implements ICcAddressedMessage {
    private static final long serialVersionUID = 1L;

    private final JobId jobId;
    private final String handle;
    private final byte[] thresholdBytes;
    private final int thresholdLength;
    private final boolean isMax;

    public PlaqueThresholdUpdateMessage(JobId jobId, String handle,
            byte[] thresholdBytes, int thresholdLength, boolean isMax) {
        this.jobId = jobId;
        this.handle = handle;
        this.thresholdBytes = thresholdBytes;
        this.thresholdLength = thresholdLength;
        this.isMax = isMax;
    }

    @Override
    public void handle(ICcApplicationContext appCtx) throws HyracksDataException, InterruptedException {
        try {
            boolean improved = PlaqueThresholdCCState.INSTANCE.update(
                    jobId, handle, thresholdBytes, thresholdLength, isMax);
            if (improved) {
                // Broadcast the new global threshold to all NCs
                ICCMessageBroker broker =
                        (ICCMessageBroker) appCtx.getServiceContext().getMessageBroker();
                byte[] globalSnapshot = PlaqueThresholdCCState.INSTANCE.getSnapshot(jobId, handle);
                int globalLen = globalSnapshot.length;
                PlaqueThresholdBroadcastMessage broadcast =
                        new PlaqueThresholdBroadcastMessage(jobId, handle, globalSnapshot, globalLen, isMax);
                for (String nodeId : appCtx.getClusterStateManager().getParticipantNodes()) {
                    try {
                        broker.sendApplicationMessageToNC(broadcast, nodeId);
                    } catch (Exception e) {
                        // Node may have left the cluster — skip it
                    }
                }
            }
        } catch (Exception e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public String toString() {
        return "PlaqueThresholdUpdateMessage[job=" + jobId + ",handle=" + handle + "]";
    }
}
```


### 7. `PlaqueThresholdBroadcastMessage` (CC -> NC)
**Path:** `asterixdb/asterix-app/src/main/java/org/apache/asterix/app/message/PlaqueThresholdBroadcastMessage.java`

Sent from the CC to all NCs when the global threshold improves.

```java
package org.apache.asterix.app.message;

import org.apache.asterix.common.api.INcApplicationContext;
import org.apache.asterix.common.messaging.api.INcAddressedMessage;
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdRegistry;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;

public class PlaqueThresholdBroadcastMessage implements INcAddressedMessage {
    private static final long serialVersionUID = 1L;

    private final JobId jobId;
    private final String handle;
    private final byte[] thresholdBytes;
    private final int thresholdLength;
    private final boolean isMax;

    public PlaqueThresholdBroadcastMessage(JobId jobId, String handle,
            byte[] thresholdBytes, int thresholdLength, boolean isMax) {
        this.jobId = jobId;
        this.handle = handle;
        this.thresholdBytes = thresholdBytes;
        this.thresholdLength = thresholdLength;
        this.isMax = isMax;
    }

    @Override
    public void handle(INcApplicationContext appCtx) throws HyracksDataException, InterruptedException {
        PlaqueThresholdRegistry.INSTANCE.mergeGlobal(
                jobId, handle, thresholdBytes, thresholdLength, isMax);
    }

    @Override
    public String toString() {
        return "PlaqueThresholdBroadcastMessage[job=" + jobId + ",handle=" + handle + "]";
    }
}
```


### 8. `PlaqueThresholdCCState` (CC-side global state)
**Path:** `asterixdb/asterix-app/src/main/java/org/apache/asterix/app/message/PlaqueThresholdCCState.java`

Static singleton on the CC that maintains the global best threshold per (JobId, handle). Uses the same monotonic-merge logic as `PlaqueThresholdState`, but only needs to store the raw bytes (no `passes()` needed on CC).

```java
package org.apache.asterix.app.message;

import java.util.concurrent.ConcurrentHashMap;

import org.apache.asterix.dataflow.data.nontagged.comparators.AGenericAscBinaryComparatorFactory;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;

public final class PlaqueThresholdCCState {

    public static final PlaqueThresholdCCState INSTANCE = new PlaqueThresholdCCState();

    private static class ThresholdEntry {
        final boolean isMax;
        byte[] bytes;
        int length;
        boolean initialized;
        final IBinaryComparator comparator;

        ThresholdEntry(boolean isMax) {
            this.isMax = isMax;
            this.comparator = AGenericAscBinaryComparatorFactory.INSTANCE.createBinaryComparator();
            this.initialized = false;
        }
    }

    private final ConcurrentHashMap<String, ThresholdEntry> entries = new ConcurrentHashMap<>();

    private PlaqueThresholdCCState() {}

    /**
     * Updates the global threshold for (jobId, handle).
     * Returns true if the global threshold actually improved.
     */
    public boolean update(JobId jobId, String handle, byte[] data, int len, boolean isMax)
            throws HyracksDataException {
        String key = makeKey(jobId, handle);
        ThresholdEntry entry = entries.computeIfAbsent(key, k -> new ThresholdEntry(isMax));
        synchronized (entry) {
            if (!entry.initialized) {
                entry.bytes = new byte[len];
                System.arraycopy(data, 0, entry.bytes, 0, len);
                entry.length = len;
                entry.initialized = true;
                return true;
            }
            int cmp = entry.comparator.compare(data, 0, len, entry.bytes, 0, entry.length);
            if ((isMax && cmp > 0) || (!isMax && cmp < 0)) {
                entry.bytes = new byte[len];
                System.arraycopy(data, 0, entry.bytes, 0, len);
                entry.length = len;
                return true;
            }
            return false;
        }
    }

    /**
     * Returns a copy of the current global threshold bytes.
     */
    public byte[] getSnapshot(JobId jobId, String handle) {
        ThresholdEntry entry = entries.get(makeKey(jobId, handle));
        if (entry == null) {
            return null;
        }
        synchronized (entry) {
            if (!entry.initialized) {
                return null;
            }
            byte[] copy = new byte[entry.length];
            System.arraycopy(entry.bytes, 0, copy, 0, entry.length);
            return copy;
        }
    }

    /**
     * Removes all entries for a given job. Called on job completion.
     */
    public void removeJob(JobId jobId) {
        String prefix = jobId.toString() + ":";
        entries.entrySet().removeIf(e -> e.getKey().startsWith(prefix));
    }

    private static String makeKey(JobId jobId, String handle) {
        return jobId.toString() + ":" + handle;
    }
}
```

**CC-side cleanup:** `PlaqueThresholdCCState.removeJob()` must be called when a job finishes. The CC has `IJobLifecycleListener.notifyJobFinish()` for this. Register a listener that calls `PlaqueThresholdCCState.INSTANCE.removeJob(jobId)`.

Add a `PlaqueJobLifecycleListener` that implements `IJobLifecycleListener`:

```java
package org.apache.asterix.app.message;

import java.util.List;
import org.apache.hyracks.api.exceptions.HyracksException;
import org.apache.hyracks.api.job.IJobLifecycleListener;
import org.apache.hyracks.api.job.JobId;
import org.apache.hyracks.api.job.JobSpecification;
import org.apache.hyracks.api.job.JobStatus;
import org.apache.hyracks.api.job.IJobCapacityController;

public class PlaqueJobLifecycleListener implements IJobLifecycleListener {

    public static final PlaqueJobLifecycleListener INSTANCE = new PlaqueJobLifecycleListener();

    @Override
    public void notifyJobCreation(JobId jobId, JobSpecification spec,
            IJobCapacityController.JobSubmissionStatus status) throws HyracksException {
        // no-op
    }

    @Override
    public void notifyJobStart(JobId jobId, JobSpecification spec) throws HyracksException {
        // no-op
    }

    @Override
    public void notifyJobFinish(JobId jobId, JobSpecification spec,
            JobStatus jobStatus, List<Exception> exceptions) throws HyracksException {
        PlaqueThresholdCCState.INSTANCE.removeJob(jobId);
    }
}
```

Register this listener during CC startup. In `CCApplication.start()` or `CcApplicationContext` initialization, add:
```java
ccs.addJobLifecycleListener(PlaqueJobLifecycleListener.INSTANCE);
```

Locate the CC application startup in `asterixdb/asterix-app/src/main/java/org/apache/asterix/hyracks/bootstrap/CCApplication.java` and add the registration after the service context is initialized.


### 9. `PlaqueRewriteRule` extension (the biggest change)
**Path:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/optimizer/rules/PlaqueRewriteRule.java`

The M1 rule walks from the local aggregate down through ASSIGN/PROJECT/EXCHANGE to find the insertion point. In M2, the walk must also traverse through join operators, entering the probe side.

The key additions:
1. Walk through `INNERJOIN` / `LEFT_OUTER_JOIN` by entering input 0 (probe side)
2. Verify that the filtered variable originates from the probe side
3. Continue walking through `EXCHANGE` (the hash-partition exchange) below the join
4. Insert the filter below the exchange, above the scan

```java
public class PlaqueRewriteRule implements IAlgebraicRewriteRule {

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return false;
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        if (!context.getPhysicalOptimizationConfig().getPlaqueEnabled()) {
            return false;
        }

        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        if (op.getOperatorTag() != LogicalOperatorTag.AGGREGATE) {
            return false;
        }

        AggregateOperator aggOp = (AggregateOperator) op;
        if (aggOp.getExpressions().size() != 1) {
            return false;
        }

        // Check for agg-local-sql-max or agg-local-sql-min
        ILogicalExpression expr = aggOp.getExpressions().get(0).getValue();
        if (!(expr instanceof AggregateFunctionCallExpression)) {
            return false;
        }
        AggregateFunctionCallExpression aggExpr = (AggregateFunctionCallExpression) expr;
        FunctionIdentifier fid = aggExpr.getFunctionIdentifier();
        boolean isMax;
        if (fid.equals(BuiltinFunctions.LOCAL_SQL_MAX)) {
            isMax = true;
        } else if (fid.equals(BuiltinFunctions.LOCAL_SQL_MIN)) {
            isMax = false;
        } else {
            return false;
        }

        // Check argument is a plain variable reference
        ILogicalExpression arg = aggExpr.getArguments().get(0).getValue();
        if (!(arg instanceof VariableReferenceExpression)) {
            return false;
        }
        LogicalVariable filteredVar = ((VariableReferenceExpression) arg).getVariableReference();

        // Placement walk: find where to insert the filter.
        // Start from the aggregate's input and walk down.
        // We track the "current parent ref" — the Mutable<ILogicalOperator> we will
        // splice the filter into.
        boolean pushThroughJoin = context.getPhysicalOptimizationConfig().getPlaquePushThroughJoin();
        Mutable<ILogicalOperator> insertionPointRef = findInsertionPoint(
                aggOp.getInputs().get(0), filteredVar, pushThroughJoin);

        if (insertionPointRef == null) {
            return false;
        }

        // Generate plan-unique handle
        String handle = "plaque-" + context.newVar();

        // Create SelectOperator as the carrier for PlaqueFilter
        ILogicalExpression trueCond = ConstantExpression.TRUE;
        SelectOperator plaqueSelect = new SelectOperator(new MutableObject<>(trueCond));
        plaqueSelect.setSourceLocation(aggOp.getSourceLocation());
        plaqueSelect.setExecutionMode(
                ((AbstractLogicalOperator) insertionPointRef.getValue()).getExecutionMode());

        // Annotate the filter
        plaqueSelect.getAnnotations().put("plaque-filter", true);
        plaqueSelect.getAnnotations().put("plaque-handle", handle);

        // Read tunable parameters
        PhysicalOptimizationConfig physConf = context.getPhysicalOptimizationConfig();
        boolean propagationEnabled = physConf.getPlaquePropagation();
        int propagationInterval = physConf.getPlaquePropagationInterval();

        // Pre-assign physical operators
        plaqueSelect.setPhysicalOperator(new PlaqueFilterPOperator(filteredVar, isMax, handle));
        aggOp.getAnnotations().put("plaque-aggregate", true);
        aggOp.getAnnotations().put("plaque-handle", handle);
        aggOp.setPhysicalOperator(new PlaqueAggregatePOperator(
                handle, isMax, propagationEnabled, propagationInterval));

        // Wire into the plan
        ILogicalOperator below = insertionPointRef.getValue();
        plaqueSelect.getInputs().add(new MutableObject<>(below));
        insertionPointRef.setValue(plaqueSelect);

        context.computeAndSetTypeEnvironmentForOperator(plaqueSelect);
        context.computeAndSetTypeEnvironmentForOperator(aggOp);
        return true;
    }

    /**
     * Walks down from the given starting ref to find the insertion point for the filter.
     * Handles both the M1 case (no join) and the M2 case (with join).
     *
     * Returns the Mutable<ILogicalOperator> where the filter should be spliced in
     * (i.e., the filter will take the operator at this ref as input, and this ref
     * will be updated to point to the filter).
     *
     * Returns null if no valid insertion point is found (e.g., reached the bottom
     * of the plan without finding a suitable operator).
     */
    private Mutable<ILogicalOperator> findInsertionPoint(
            Mutable<ILogicalOperator> startRef, LogicalVariable filteredVar,
            boolean pushThroughJoin) {

        Mutable<ILogicalOperator> currentRef = startRef;

        while (true) {
            ILogicalOperator current = currentRef.getValue();
            LogicalOperatorTag tag = current.getOperatorTag();

            if (tag == LogicalOperatorTag.ASSIGN) {
                AssignOperator assignOp = (AssignOperator) current;
                if (assignOp.getVariables().contains(filteredVar)) {
                    // The ASSIGN defines the filtered variable — insert above it
                    return currentRef;
                }
                // ASSIGN that doesn't define our variable — continue
            } else if (tag == LogicalOperatorTag.PROJECT
                    || tag == LogicalOperatorTag.EXCHANGE) {
                // Safe to walk through
            } else if ((tag == LogicalOperatorTag.INNERJOIN
                    || tag == LogicalOperatorTag.LEFTOUTERJOIN) && pushThroughJoin) {
                // M2 path: try to enter the join's probe side (input 0).
                // Only when compiler.plaque.push.through.join is true.
                // When false, we treat the join as opaque and stop here (M1-style weak filter).
                //
                // We can only push through the join if the filtered variable comes from
                // the PROBE side. The build side is fully consumed into the hash table
                // before the probe phase begins — by the time the observer starts learning
                // thresholds (during probe), the build-side scan is already done.
                // There is no running build-side scan left to filter.
                //
                // If the variable comes from the build side, we fall back to weak placement
                // above the join. The filter still reduces post-join tuple flow.
                AbstractLogicalOperator joinOp = (AbstractLogicalOperator) current;
                if (!variableProducedBelow(joinOp.getInputs().get(0).getValue(), filteredVar)) {
                    // Variable comes from build side — cannot cross the join.
                    // Stop here: weak filter above the join.
                    return currentRef;
                }
                // Variable comes from probe side — walk down probe side
                currentRef = joinOp.getInputs().get(0);
                continue;
            } else if (tag == LogicalOperatorTag.DATASOURCE_SCAN
                    || tag == LogicalOperatorTag.SELECT) {
                // Stop: insert above this operator
                return currentRef;
            } else {
                // Unknown/unsupported operator — stop and insert above it
                return currentRef;
            }

            if (current.getInputs().isEmpty()) {
                break;
            }
            currentRef = current.getInputs().get(0);
        }

        return null;
    }

    /**
     * Checks whether the given variable is produced (or passed through) by operators
     * in the subtree rooted at the given operator. Used to verify that the filtered
     * variable originates from the probe side of a join.
     *
     * Walks down the operator tree and checks each operator's output variables.
     * Uses VariableUtilities.getProducedVariables() and getLiveVariables().
     */
    private boolean variableProducedBelow(ILogicalOperator op, LogicalVariable var)
            throws AlgebricksException {
        // Check if the variable is live (available) at the output of this subtree
        Set<LogicalVariable> liveVars = new HashSet<>();
        VariableUtilities.getLiveVariables(op, liveVars);
        return liveVars.contains(var);
    }
}
```

**Key differences from M1:**

1. The walk now handles `INNERJOIN` and `LEFTOUTERJOIN`. When the filtered variable comes from the probe side (input 0), the walk enters input 0 and continues below the hash-partition exchange to the scan. When `compiler.plaque.push.through.join` is false, the walk treats joins as opaque and stops.
2. `variableProducedBelow()` verifies the filtered variable comes from the probe side. If it comes from the build side (e.g., `MAX(o.o_totalprice)` where Orders is the build side), the filter cannot cross the join — the build-side scan is already complete by the time the observer starts learning thresholds. The walk stops at the join, giving M1-style weak placement above it.
3. After entering the probe side, the walk continues through `EXCHANGE` (the hash-partition exchange) and then to the scan or any other operator. The filter is inserted below the exchange, above the scan.
4. `VariableUtilities.getLiveVariables()` is an existing Algebricks utility that computes the set of variables available at the output of a subtree. It handles ASSIGN, PROJECT, SCAN, etc.

**Import for `VariableUtilities`:**
```java
import org.apache.hyracks.algebricks.core.algebra.operators.logical.visitors.VariableUtilities;
```
**Path:** `hyracks-fullstack/algebricks/algebricks-core/src/main/java/org/apache/hyracks/algebricks/core/algebra/operators/logical/visitors/VariableUtilities.java`


### 10. `PlaqueAggregatePOperator` changes (propagation trigger)
**Path:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/algebra/operators/physical/PlaqueAggregatePOperator.java`

The M1 `PlaqueAggregatePOperator` produces a standard `AggregateRuntimeFactory`. In M2, we need a custom aggregate runtime that calls `maybePropagateThreshold()` after each frame. Two options:

**Option A (clean):** Create a `PlaqueAggregateRuntimeFactory` that extends `AggregateRuntimeFactory` and produces a `PlaqueAggregatePushRuntime` that overrides `nextFrame()`.

**Option B (minimal):** In the `PlaqueLocalSqlMinMaxAggregateFunction.step()`, count tuples and propagate every N tuples.

We go with **Option A**.

Create `PlaqueAggregateRuntimeFactory`:
**Path:** `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueAggregateRuntimeFactory.java`

```java
package org.apache.asterix.runtime.operators.plaque;

import org.apache.hyracks.algebricks.runtime.operators.aggreg.AggregateRuntimeFactory;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.algebricks.runtime.base.IAggregateEvaluatorFactory;
import org.apache.hyracks.algebricks.runtime.base.IPushRuntime;

/**
 * Extends AggregateRuntimeFactory to produce a PlaqueAggregatePushRuntime
 * that triggers cross-node threshold propagation after each frame.
 */
public class PlaqueAggregateRuntimeFactory extends AggregateRuntimeFactory {
    private static final long serialVersionUID = 1L;

    private final int[] projectionList;

    public PlaqueAggregateRuntimeFactory(IAggregateEvaluatorFactory[] aggregFactories) {
        super(aggregFactories);
        this.projectionList = null;  // no projection
    }

    // Note: We need to override createPushRuntime to return a
    // PlaqueAggregatePushRuntime. However, AggregateRuntimeFactory's
    // createOneOutputPushRuntime creates AggregatePushRuntime directly.
    // We override the parent method.
}
```

This approach requires `AggregateRuntimeFactory.createOneOutputPushRuntime()` to be overridable. Let us check if it is.

The actual approach is simpler: rather than subclassing the runtime, we have the `PlaqueLocalSqlMinMaxAggregateFunction` use a **tuple-count-based propagation trigger** in `step()`:

```java
private int stepCount = 0;
private final int propagationInterval;  // from compiler.plaque.propagation.interval

@Override
public void step(IFrameTupleReference tuple) throws HyracksDataException {
    super.step(tuple);
    if (++stepCount >= propagationInterval) {
        stepCount = 0;
        maybePropagateThreshold();
    }
}
```

And also propagate in `finish()`:
```java
@Override
public void finish(IPointable result) throws HyracksDataException {
    maybePropagateThreshold();
    super.finish(result);
}
```

This is **Option B** and is simpler — no new runtime classes needed. The `PlaqueAggregatePOperator.contributeRuntimeOperator()` continues to produce a standard `AggregateRuntimeFactory`. The propagation happens inside the aggregate function itself.

The interval is controlled by `compiler.plaque.propagation.interval` (default 5000). On a typical frame of 32KB with 100-byte tuples, the default is roughly every 15 frames. At sub-millisecond frame processing times, the propagation frequency is approximately every few milliseconds — sufficient for effective filtering without flooding the CC. Set to 1 for eager propagation (useful for measuring maximum benefit) or MAX_INT for no propagation (measuring intra-node-only benefit).

**Updated `PlaqueLocalSqlMinMaxAggregateFunction` with step-based propagation:**

```java
public class PlaqueLocalSqlMinMaxAggregateFunction extends SqlMinMaxAggregateFunction {

    private final PlaqueThresholdState thresholdState;
    private final JobId jobId;
    private final String handle;
    private INCMessageBroker messageBroker;
    private int stepCount;
    private final int propagationInterval;  // from compiler.plaque.propagation.interval

    PlaqueLocalSqlMinMaxAggregateFunction(IScalarEvaluatorFactory[] args, IEvaluatorContext context,
            boolean isMin, SourceLocation sourceLoc, IAType aggFieldType,
            PlaqueThresholdState thresholdState, JobId jobId, String handle,
            int propagationInterval)
            throws HyracksDataException {
        super(args, context, isMin, Type.LOCAL, sourceLoc, aggFieldType);
        this.thresholdState = thresholdState;
        this.jobId = jobId;
        this.handle = handle;
        this.propagationInterval = propagationInterval;
        this.stepCount = 0;
    }

    @Override
    protected void onMinMaxChanged() throws HyracksDataException {
        thresholdState.update(
                outputVal.getByteArray(),
                outputVal.getStartOffset(),
                outputVal.getLength());
    }

    @Override
    public void step(IFrameTupleReference tuple) throws HyracksDataException {
        super.step(tuple);
        if (++stepCount >= propagationInterval) {
            stepCount = 0;
            maybePropagateThreshold();
        }
    }

    @Override
    public void finish(IPointable result) throws HyracksDataException {
        maybePropagateThreshold();  // flush any remaining threshold update
        super.finish(result);
    }

    private void maybePropagateThreshold() {
        if (!thresholdState.isDirty() || messageBroker == null) {
            return;
        }
        try {
            byte[] snapshot = thresholdState.getThresholdSnapshot();
            if (snapshot == null) {
                return;
            }
            thresholdState.clearDirty();
            PlaqueThresholdUpdateMessage msg = new PlaqueThresholdUpdateMessage(
                    jobId, handle, snapshot, snapshot.length, thresholdState.isMax());
            messageBroker.sendMessageToPrimaryCC(msg);
        } catch (Exception e) {
            // Propagation failure is non-fatal: reduces effectiveness, not correctness
        }
    }

    public void setMessageBroker(INCMessageBroker broker) {
        this.messageBroker = broker;
    }
}
```

**Note on `step()` override:** `SqlMinMaxAggregateFunction` inherits `step()` from `AbstractMinMaxAggregateFunction`. The method is not `final`, so overriding it is safe. The `super.step(tuple)` call processes the tuple and may call `onMinMaxChanged()` if the threshold improves.

**Note on `finish()` override:** `SqlMinMaxAggregateFunction` inherits `finish()` from `AbstractMinMaxAggregateFunction`. Calling `maybePropagateThreshold()` before `super.finish(result)` ensures the final threshold is sent to the CC. This is important for short-running queries where the propagation interval may not be reached.

**Circular dependency concern:** `PlaqueThresholdUpdateMessage` is in `asterix-app`, but `PlaqueLocalSqlMinMaxAggregateFunction` is in `asterix-runtime`. The function cannot directly import the message class. Solution: the function does not create the message directly. Instead, it calls through an interface or delegate:

```java
// In asterix-runtime (no dependency on asterix-app)
@FunctionalInterface
public interface PlaqueThresholdPropagator {
    void propagate(JobId jobId, String handle, byte[] thresholdBytes, int len, boolean isMax) throws Exception;
}
```

The `PlaqueLocalAggregateEvaluatorFactory` creates the propagator lambda at evaluator-creation time:

```java
// In PlaqueLocalAggregateEvaluatorFactory.createAggregateEvaluator():
INCMessageBroker broker = (INCMessageBroker) serviceCtx.getMessageBroker();
PlaqueThresholdPropagator propagator = (jid, h, bytes, len, max) -> {
    PlaqueThresholdUpdateMessage msg = new PlaqueThresholdUpdateMessage(jid, h, bytes, len, max);
    broker.sendMessageToPrimaryCC(msg);
};
agg.setPropagator(propagator);
```

Wait — `PlaqueLocalAggregateEvaluatorFactory` is also in `asterix-runtime` and cannot import `PlaqueThresholdUpdateMessage` from `asterix-app`.

**Resolution:** Move `PlaqueLocalAggregateEvaluatorFactory` to `asterix-app`, or use a different approach:

**Best approach:** Keep the `INCMessageBroker` reference in the aggregate function and have it send a generic message. But the message class must be in a module visible to both the sender (NC runtime) and the receiver (CC app).

Since `ICcAddressedMessage` is in `asterix-common` and is `Serializable`, we can define `PlaqueThresholdUpdateMessage` in `asterix-common` or `asterix-runtime`. The message's `handle()` method needs access to `ICcApplicationContext` (which is in `asterix-common`), so placing it in `asterix-runtime` works if the message delegates CC-side logic to a static method in `asterix-app`.

**Simplest correct solution:** Place `PlaqueThresholdUpdateMessage` in `asterix-runtime/.../runtime/message/` (same package as `ResourceIdRequestMessage`). The `handle()` method accesses `ICcApplicationContext` (from `asterix-common`) to get the message broker and cluster state. The CC-side state (`PlaqueThresholdCCState`) also lives in `asterix-runtime`. The broadcast message (`PlaqueThresholdBroadcastMessage`) also lives in `asterix-runtime`.

This works because `asterix-runtime` depends on `asterix-common` (which defines `ICcApplicationContext`, `ICCMessageBroker`, `IClusterStateManager`, etc.).

**Revised paths:**
- `PlaqueThresholdUpdateMessage`: `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/message/PlaqueThresholdUpdateMessage.java`
- `PlaqueThresholdBroadcastMessage`: `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/message/PlaqueThresholdBroadcastMessage.java`
- `PlaqueThresholdCCState`: `asterixdb/asterix-runtime/src/main/java/org/apache/asterix/runtime/operators/plaque/PlaqueThresholdCCState.java`


### 11. `PlaqueAggregatePOperator` changes (minimal)
**Path:** `asterixdb/asterix-algebra/src/main/java/org/apache/asterix/algebra/operators/physical/PlaqueAggregatePOperator.java`

The `PlaqueAggregatePOperator` needs to read the tunable parameters from the physical optimization config and pass them to the factory. In `contributeRuntimeOperator()`, after constructing `scalarArgs` and `aggFieldType` (same as M1):

```java
// Read tunable parameters from config
boolean propagationEnabled = context.getPhysicalOptimizationConfig().getPlaquePropagation();
int propagationInterval = context.getPhysicalOptimizationConfig().getPlaquePropagationInterval();

aggFactories[i] = new PlaqueLocalAggregateEvaluatorFactory(
        scalarArgs, isMin, aggFun.getSourceLocation(), aggFieldType, handle, isMax,
        propagationEnabled, propagationInterval);
```

The constructor also stores these values:

```java
public class PlaqueAggregatePOperator extends AggregatePOperator {
    private final String handle;
    private final boolean isMax;
    private final boolean propagationEnabled;
    private final int propagationInterval;

    public PlaqueAggregatePOperator(String handle, boolean isMax,
            boolean propagationEnabled, int propagationInterval) {
        this.handle = handle;
        this.isMax = isMax;
        this.propagationEnabled = propagationEnabled;
        this.propagationInterval = propagationInterval;
    }
    // ...
}
```


### 12. CC-side job lifecycle listener registration
**Path:** `asterixdb/asterix-app/src/main/java/org/apache/asterix/hyracks/bootstrap/CCApplication.java`

Find the `start()` method and add after the `ClusterControllerService` is available:

```java
import org.apache.asterix.runtime.operators.plaque.PlaqueThresholdCCState;
// In CCApplication:
// After ccs is initialized, register the PLAQUE lifecycle listener.
// This cleans up PlaqueThresholdCCState entries when jobs complete.
```

Actually, `PlaqueThresholdCCState.removeJob()` can be called directly from the message itself: in `PlaqueThresholdUpdateMessage.handle()`, we could also clean up. But the proper cleanup is on job finish, not on message receipt.

A simpler approach that avoids modifying `CCApplication.java`: since `PlaqueThresholdCCState` is a `ConcurrentHashMap`, stale entries for completed jobs will accumulate but are small (a few bytes each). For a research prototype, this is acceptable. If cleanup is desired, add it later.

For thoroughness, the clean approach is:

In `CCApplication.java`, find where job lifecycle listeners are registered. Look for calls to `ccs.addJobLifecycleListener()` or equivalent:

```java
// In CCApplication.start(), after the ClusterControllerService (ccs) is initialized:
ccs.addJobLifecycleListener(new IJobLifecycleListener() {
    @Override
    public void notifyJobCreation(JobId jobId, JobSpecification spec,
            IJobCapacityController.JobSubmissionStatus status) {}
    @Override
    public void notifyJobStart(JobId jobId, JobSpecification spec) {}
    @Override
    public void notifyJobFinish(JobId jobId, JobSpecification spec,
            JobStatus jobStatus, List<Exception> exceptions) {
        PlaqueThresholdCCState.INSTANCE.removeJob(jobId);
    }
});
```

### 13. NC-side job cleanup for PlaqueThresholdRegistry

The NC-level `PlaqueThresholdRegistry` also needs cleanup. The filter's `close()` method calls `removeJob()` (see component 3 above). But if the filter does not run (e.g., the job is cancelled), entries leak.

For robustness, use the same approach: the NC-side does not have `IJobLifecycleListener`, but the filter and aggregate `close()` methods both call `removeJob()`. Since these are the only producers/consumers, this covers all normal execution paths.


## Correctness argument

1. **The true max always reaches the aggregate.** The filter only drops tuples with `l_extendedprice < threshold`. The threshold is the maximum `l_extendedprice` of tuples that have already survived the join and been processed by the aggregate. The tuple carrying the true maximum has `l_extendedprice >= threshold` (it is either the current threshold or greater), so it always passes the filter.

2. **Dropped tuples cannot affect the answer.** A tuple with `l_extendedprice < threshold` cannot be the global MAX because a higher value has already been observed (it was processed by the aggregate and set the threshold). Even if the dropped tuple would have survived the join, its `l_extendedprice` is less than an already-confirmed survivor.

3. **Cross-node propagation only strengthens the threshold (monotonic refinement).** The `mergeGlobal()` method uses the same monotonic comparison: it only updates the local threshold if the received global threshold is strictly better. A stale or equal threshold is ignored. The threshold can only increase (for MAX) or decrease (for MIN) over time, never regress.

4. **Stale reads are safe.** A filter thread may read a slightly stale threshold (due to `volatile` read lag or network propagation delay). This means it might pass a tuple that could have been filtered — reducing effectiveness — but never drops a tuple that should pass. The correctness invariant (monotonic threshold) is preserved regardless of propagation speed.

5. **Even with zero cross-node propagation, correctness holds.** If Layer 2 (CC-mediated messaging) is disabled or fails entirely, each NC still has its own NC-level threshold that filters based on locally observed values. Correctness is maintained. Cross-node propagation only improves effectiveness (more aggressive filtering across all NCs).

6. **NULL/MISSING handling is unchanged.** The filter passes NULL/MISSING/SYSTEM_NULL tuples unconditionally, as in M1. The aggregate ignores them per SQL semantics.

7. **Join semantics are preserved.** The filter drops tuples BEFORE the hash-partition exchange, before the join. A dropped tuple never enters the join's build or probe side. Since the dropped tuple's `l_extendedprice` is below the threshold, even if it would have found a matching order, the resulting join tuple would have `l_extendedprice < threshold`, which cannot be the MAX.

8. **Build-side completeness.** When the filtered variable comes from the probe side, the filter is placed on the probe side only. The build side (e.g., Orders with the date predicate) is completely unaffected — all qualifying build tuples are still materialized into the hash table. The filter only reduces the number of probe tuples entering the shuffle and the join. When the filtered variable comes from the build side, the filter cannot cross the join (the build-side scan is already done by the time the observer learns thresholds), so it sits above the join as a weaker post-join filter that does not affect either scan.


## Lifecycle

### NC-level state lifecycle
- **Creation:** `PlaqueThresholdRegistry.getOrCreate()` is called during `PlaqueFilterRuntime.open()` (filter side) and `PlaqueLocalAggregateEvaluatorFactory.createAggregateEvaluator()` (observer side). Whichever runs first creates the entry; the second gets the same instance via `computeIfAbsent`.
- **Usage:** Concurrent reads (`passes()`) and writes (`update()`) throughout the query execution.
- **Cleanup:** `PlaqueThresholdRegistry.removeJob()` is called in `PlaqueFilterRuntime.close()`. Multiple partitions may call this concurrently; `ConcurrentHashMap.entrySet().removeIf()` is safe for this.

### CC-level state lifecycle
- **Creation:** `PlaqueThresholdCCState.update()` is called when the first `PlaqueThresholdUpdateMessage` arrives from any NC.
- **Usage:** Receives threshold updates, compares, and broadcasts when the global threshold improves.
- **Cleanup:** `PlaqueThresholdCCState.removeJob()` is called via the `IJobLifecycleListener` when the job finishes. For a prototype, the leak from missing cleanup is negligible (a few bytes per job).


## Testing

```bash
# Target query without PLAQUE
curl -s http://localhost:19002/query/service \
  -d '{"statement":"SELECT MAX(l.l_extendedprice) FROM Lineitem_10 l, Orders_10 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderdate < '\''1995-01-01'\'';"}'

# Same query with PLAQUE enabled
curl -s http://localhost:19002/query/service \
  -d '{"statement":"SET `compiler.plaque.enabled` \"true\"; SELECT MAX(l.l_extendedprice) FROM Lineitem_10 l, Orders_10 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderdate < '\''1995-01-01'\'';"}'

# EXPLAIN to verify filter placement
curl -s http://localhost:19002/query/service \
  -d '{"statement":"SET `compiler.plaque.enabled` \"true\"; EXPLAIN SELECT MAX(l.l_extendedprice) FROM Lineitem_10 l, Orders_10 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderdate < '\''1995-01-01'\'';"}'

# MIN variant
curl -s http://localhost:19002/query/service \
  -d '{"statement":"SET `compiler.plaque.enabled` \"true\"; SELECT MIN(l.l_extendedprice) FROM Lineitem_10 l, Orders_10 o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderdate < '\''1995-01-01'\'';"}'

# M1 single-table query should still work (regression test)
curl -s http://localhost:19002/query/service \
  -d '{"statement":"SET `compiler.plaque.enabled` \"true\"; SELECT MAX(l_shipdate) FROM Lineitem_10;"}'
```

**Success criteria:**
- Identical results with flag on vs off (for both MAX and MIN, with and without joins)
- EXPLAIN shows `PLAQUE_FILTER` operator on the probe side, BELOW the `HASH_PARTITION_EXCHANGE`, ABOVE the `DATASOURCE_SCAN` for the Lineitem table
- EXPLAIN shows `plaque-aggregate` annotation on the local aggregate ABOVE the join
- The filter and aggregate share the same `plaque-handle` annotation value
- M1 single-table queries continue to produce correct results (no regression)
- On multi-node clusters, the filter becomes effective within a few frames (verify by logging threshold updates)

**Multi-node verification:**
On a multi-node cluster, add logging to `PlaqueThresholdUpdateMessage.handle()` and `PlaqueThresholdBroadcastMessage.handle()` to verify messages are flowing:
```java
LOGGER.info("PLAQUE: CC received threshold update for job={}, handle={}", jobId, handle);
LOGGER.info("PLAQUE: NC received global threshold for job={}, handle={}", jobId, handle);
```


## File summary

| Component | Path | Notes |
|---|---|---|
| `PlaqueThresholdState` | `asterixdb/asterix-runtime/.../operators/plaque/PlaqueThresholdState.java` | New. Thread-safe, volatile reads + synchronized writes. Replaces M1's `FilterState`. |
| `PlaqueThresholdRegistry` | `asterixdb/asterix-runtime/.../operators/plaque/PlaqueThresholdRegistry.java` | New. Static singleton. ConcurrentHashMap keyed by `(JobId, handle)`. |
| `PlaqueThresholdPropagator` | `asterixdb/asterix-runtime/.../operators/plaque/PlaqueThresholdPropagator.java` | New. Functional interface to decouple runtime from messaging. |
| `PlaqueFilterRuntime` | `asterixdb/asterix-runtime/.../operators/plaque/PlaqueFilterRuntimeFactory.java` (inner) | Modified. Resolves from NC-level registry. Per-thread comparator for thread safety. |
| `PlaqueFilterRuntimeFactory` | `asterixdb/asterix-runtime/.../operators/plaque/PlaqueFilterRuntimeFactory.java` | Modified (minimal). Remove `FilterStateRegistry` import, use `PlaqueThresholdRegistry`. |
| `PlaqueLocalSqlMinMaxAggregateFunction` | `asterixdb/asterix-runtime/.../aggregates/std/PlaqueLocalSqlMinMaxAggregateFunction.java` | Modified. Uses `PlaqueThresholdState` instead of `FilterState`. Adds step-based propagation trigger. |
| `PlaqueLocalAggregateEvaluatorFactory` | `asterixdb/asterix-runtime/.../operators/plaque/PlaqueLocalAggregateEvaluatorFactory.java` | Modified. Resolves from NC-level registry. Sets message broker on the aggregate function. |
| `PlaqueThresholdUpdateMessage` | `asterixdb/asterix-runtime/.../runtime/message/PlaqueThresholdUpdateMessage.java` | New. NC-to-CC message. Implements `ICcAddressedMessage`. |
| `PlaqueThresholdBroadcastMessage` | `asterixdb/asterix-runtime/.../runtime/message/PlaqueThresholdBroadcastMessage.java` | New. CC-to-NC message. Implements `INcAddressedMessage`. |
| `PlaqueThresholdCCState` | `asterixdb/asterix-runtime/.../operators/plaque/PlaqueThresholdCCState.java` | New. Static singleton on CC. Monotonic merge of per-NC thresholds. |
| `PlaqueRewriteRule` | `asterixdb/asterix-algebra/.../optimizer/rules/PlaqueRewriteRule.java` | Modified. Extended `findInsertionPoint()` to walk through JOIN operators, entering probe side. Variable provenance check via `VariableUtilities.getLiveVariables()`. |
| `PlaqueAggregatePOperator` | `asterixdb/asterix-algebra/.../operators/physical/PlaqueAggregatePOperator.java` | Modified. Now carries `propagationEnabled` and `propagationInterval` from config, passes to factory. |
| `PlaqueFilterPOperator` | `asterixdb/asterix-algebra/.../operators/physical/PlaqueFilterPOperator.java` | Unchanged from M1. |
| `FilterState` | `asterixdb/asterix-runtime/.../operators/plaque/FilterState.java` | Retired. Replaced by `PlaqueThresholdState`. Can be deleted or kept as dead code. |
| `FilterStateRegistry` | `asterixdb/asterix-runtime/.../operators/plaque/FilterStateRegistry.java` | Retired. Replaced by `PlaqueThresholdRegistry`. |
| `PlaqueJobLifecycleListener` | `asterixdb/asterix-app/.../app/message/PlaqueJobLifecycleListener.java` | New. Cleans up CC-side state on job completion. |
| CC lifecycle registration | `asterixdb/asterix-app/.../hyracks/bootstrap/CCApplication.java` | Modified. Register `PlaqueJobLifecycleListener` in `start()`. |
| Config: `AlgebricksConfig` | `hyracks-fullstack/.../config/AlgebricksConfig.java` | Add 3 defaults: `PLAQUE_PROPAGATION_DEFAULT`, `PLAQUE_PROPAGATION_INTERVAL_DEFAULT`, `PLAQUE_PUSH_THROUGH_JOIN_DEFAULT` |
| Config: `CompilerProperties` | `asterixdb/asterix-common/.../config/CompilerProperties.java` | Add 3 option entries + key constants |
| Config: `OptimizationConfUtil` | `asterixdb/asterix-common/.../config/OptimizationConfUtil.java` | Read 3 new params, propagate to `PhysicalOptimizationConfig` |
| Config: `PhysicalOptimizationConfig` | `hyracks-fullstack/.../rewriter/base/PhysicalOptimizationConfig.java` | Add 3 getter/setter pairs |


## What this document does NOT cover (later milestones)

- MAX/MIN with GROUP BY
- Complex aggregate expressions (e.g., `MAX(col * 2)`)
- Multi-join queries (e.g., three-way joins — the filter can only be pushed to the outermost probe side)
- Adaptive propagation frequency (currently tunable via `compiler.plaque.propagation.interval` but static per-query — a future milestone could adapt it at runtime based on threshold improvement rate)
- Build-side strong filtering (when the aggregated variable comes from the build side, the filter currently stays above the join as a weak filter — a future milestone could explore pre-sorting or index-based approaches to exploit the threshold on the build side before its scan completes)
- Index-based probe-side filtering (using a secondary index to skip tuples below the threshold)
