# PLAQUE DISTINCT filter: seen-set propagation between nodes (2026-11-17)

## 1. What was built

The DISTINCT seen-set now travels between nodes over the same route the MAX/MIN and GROUP BY filters use: a node controller (NC) sends a message to the cluster controller (CC), and the CC broadcasts to every participant node.

- The observer keeps the values it has newly recorded since its last send (BIGINT values in a Roaring bitmap, `Roaring64NavigableMap`; values of other types as byte arrays).
- After every `compiler.plaque.propagation.interval` rows (default 5000) and at close, it sends them to the CC as a `PlaqueDistinctUpdateMessage`, through `INCMessageBroker.sendMessageToPrimaryCC`, the same call the existing filters use.
- The CC (`PlaqueThresholdCCState.updateDistinct`) keeps what it has already seen for the job and handle, and broadcasts only the values it had not seen as a `PlaqueDistinctBroadcastMessage`, through `ICCMessageBroker.sendApplicationMessageToNC`.
- Each NC merges the broadcast into its seen-set (`PlaqueThresholdRegistry.mergeGlobalDistinct`). Merged values are not sent back.
- It is controlled by the existing `compiler.plaque.propagation` (default true), so it is on by default.

## 2. Check (one run per row, correctness only, not a benchmark)

Server: 4 NCs in one JVM, so the four NCs share one seen-set and every broadcast is counted four times in `mergedDeltas` and `mergedValues`. Level 2, flush `fj` (default). Queries: `SELECT DISTINCT l.<column> FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';` in dataverse `baleen_ice`.

Raw output (the three numbers per observer are deltas sent, deltas merged, values merged, read from the observer's log line at close):

```
l_suppkey off       174.7 s, rows 100000, md5 fe689b6606, skipped 0, observers(deltasSent/mergedDeltas/mergedValues) [], exception lines 0
l_suppkey roaring prop true 14.8 s, rows 100000, md5 fe689b6606, skipped 26, observers(deltasSent/mergedDeltas/mergedValues) [('5', '80', '375044'), ('5', '80', '375044'), ('5', '80', '375044'), ('6', '80', '375044')], exception lines 0
l_suppkey hash prop true 43.4 s, rows 100000, md5 fe689b6606, skipped 0, observers(deltasSent/mergedDeltas/mergedValues) [('5', '80', '381768'), ('5', '80', '381768'), ('6', '80', '381768'), ('5', '80', '381768')], exception lines 0
l_suppkey roaring prop false 13.8 s, rows 100000, md5 fe689b6606, skipped 26, observers(deltasSent/mergedDeltas/mergedValues) [('0', '0', '0'), ('0', '0', '0'), ('0', '0', '0'), ('0', '0', '0')], exception lines 0
l_suppkey hash prop false 44.3 s, rows 100000, md5 fe689b6606, skipped 0, observers(deltasSent/mergedDeltas/mergedValues) [('0', '0', '0'), ('0', '0', '0'), ('0', '0', '0'), ('0', '0', '0')], exception lines 0
l_partkey off       137.2 s, rows 1999998, md5 1169248e70, skipped 0, observers(deltasSent/mergedDeltas/mergedValues) [], exception lines 0
l_partkey roaring prop true 47.9 s, rows 1999998, md5 1169248e70, skipped 0, observers(deltasSent/mergedDeltas/mergedValues) [('99', '1592', '7994980'), ('100', '1592', '7994980'), ('99', '1592', '7994980'), ('101', '1592', '7994980')], exception lines 0
l_partkey hash prop true 132.4 s, rows 1999998, md5 1169248e70, skipped 0, observers(deltasSent/mergedDeltas/mergedValues) [('49', '788', '4000000'), ('49', '788', '4000000'), ('49', '788', '4000000'), ('50', '788', '4000000')], exception lines 0
l_partkey roaring prop false 46.7 s, rows 1999998, md5 1169248e70, skipped 0, observers(deltasSent/mergedDeltas/mergedValues) [('0', '0', '0'), ('0', '0', '0'), ('0', '0', '0'), ('0', '0', '0')], exception lines 0
l_partkey hash prop false 124.0 s, rows 1999998, md5 1169248e70, skipped 0, observers(deltasSent/mergedDeltas/mergedValues) [('0', '0', '0'), ('0', '0', '0'), ('0', '0', '0'), ('0', '0', '0')], exception lines 0
l_shipmode off       211.7 s, rows 7, md5 86ca9c0b89, skipped 0, observers(deltasSent/mergedDeltas/mergedValues) [], exception lines 0
l_shipmode roaring prop true 37.4 s, rows 7, md5 86ca9c0b89, skipped 0, observers(deltasSent/mergedDeltas/mergedValues) [('0', '0', '0'), ('1', '0', '0'), ('0', '0', '0'), ('0', '0', '0')], exception lines 0
l_shipmode hash prop true 37.2 s, rows 7, md5 86ca9c0b89, skipped 0, observers(deltasSent/mergedDeltas/mergedValues) [('1', '0', '0'), ('0', '0', '0'), ('0', '0', '0'), ('0', '0', '0')], exception lines 0
l_shipmode roaring prop false 37.0 s, rows 7, md5 86ca9c0b89, skipped 0, observers(deltasSent/mergedDeltas/mergedValues) [('0', '0', '0'), ('0', '0', '0'), ('0', '0', '0'), ('0', '0', '0')], exception lines 0
l_shipmode hash prop false 38.9 s, rows 7, md5 86ca9c0b89, skipped 0, observers(deltasSent/mergedDeltas/mergedValues) [('0', '0', '0'), ('0', '0', '0'), ('0', '0', '0'), ('0', '0', '0')], exception lines 0
```

- Every run returned the same result as Baleen off (same md5), with no exception lines in the server log.
- Q2 `l_suppkey`: the 4 observers sent 21 deltas; 80 merges were counted (20 broadcasts x 4 NCs).
- Q3 `l_partkey`, roaring: 399 deltas sent, 1,592 merges (398 x 4), 7,994,980 values (about 4 x 1,998,745 of the 1,999,998 distinct values).
- Q3 `l_partkey`, hash: 197 deltas sent, 788 merges, exactly 4,000,000 values = 4 x the hash cap of 1,000,000.
- Q1 `l_shipmode`: one delta sent (at close). The log line is written before that delta comes back, so its receipt is not shown.
- With propagation off, no deltas are sent or merged, and Q2 still skips 26 of 30 files (the shared registry of the single JVM does that).

Single-run times with propagation on / off: Q2 roaring 14.8 / 13.8 s, Q2 hash 43.4 / 44.3 s, Q3 roaring 47.9 / 46.7 s, Q3 hash 132.4 / 124.0 s, Q1 roaring 37.4 / 37.0 s, Q1 hash 37.2 / 38.9 s. One run each, so these do not measure the cost.

## 3. Known gaps

- The CC never clears its copy of the seen-set when a job ends: no caller of `PlaqueThresholdCCState.removeJob` was found. The same holds for the existing filters.
- The benefit of propagation cannot be shown on this server, because the 4 NCs already share one seen-set. It needs NCs in separate JVMs.
- The last delta of each observer is in flight when the log line is written, so the counted merges are one broadcast short.

## 4. Bugs found

None.

## 5. Decisions I made without asking

- Added `mergedDeltas` and `mergedValues` to the observer's statistics line.
- Reused `compiler.plaque.propagation` and its interval instead of adding DISTINCT-specific settings.
- Flat seen-set not included in this check.

## 6. Stated but not verified

- That the Q1 delta is received.
- That propagation gives the same file skips across separate JVMs.
- The cost of propagation (single runs only).
