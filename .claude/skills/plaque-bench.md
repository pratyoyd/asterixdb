---
name: plaque-bench
description: Run a PLAQUE benchmark comparing baseline vs PLAQUE-enabled execution of a given query on AsterixDB
user_invocable: true
---

# PLAQUE Benchmark Skill

The user wants to benchmark a query with and without PLAQUE filtering on AsterixDB.

## What to do

1. **Parse the user's request.** They will provide:
   - A SQL++ query (or description of one)
   - Optionally: scale factors (default: SF10), number of warmup runs (default: 1), the AsterixDB endpoint (default: http://localhost:19002/query/service)

2. **Run the benchmark** for each scale factor:
   - **Warmup**: Run the baseline query once (discard results)
   - **Baseline**: Run the query WITHOUT `SET compiler.plaque.enabled "true"` with `profile=timings`
   - **PLAQUE**: Run the query WITH `SET compiler.plaque.enabled "true"` prepended, with `profile=timings`
   - Save raw JSON responses to `/tmp/plaque_bench_baseline_sf{SF}.json` and `/tmp/plaque_bench_plaque_sf{SF}.json`

3. **Parse and compare results.** Extract from each response:
   - `elapsedTime`, `executionTime`, `processedObjects`, `bufferCacheHitRatio`, `bufferCachePageReadCount`
   - Operator-level profiling: sum `run-time` and `cardinality-out` across partitions for each operator (group by operator name after the ` - ` separator)
   - Specifically extract: `Index Search` (scan), `Hybrid Hash Join: Probe & Join` (join), `Hybrid Hash Join: Build` (build)
   - The query result values (verify baseline == PLAQUE)

4. **Present results** in a markdown table:

```
| SF | Baseline | PLAQUE | Speedup | Tuples Joined (B→P) | Join Reduction | Page Reads (B→P) | Processed Objects (B→P) | Correct |
```

5. **If multiple SFs**, also show the scaling trend.

6. **Ask the user** to paste NC logs if they want exchange filter / page filter / build observer statistics.

## AsterixDB JSON response parsing

AsterixDB's JSON has non-standard formatting. Use this Python pattern to fix it before parsing:
```python
raw = re.sub(r'"requestID":\s*"([^"]+)"\s*"signature"', r'"requestID": "\1", "signature"', raw)
raw = re.sub(r'\]\s*\n\s*,', '],', raw)
data = json.loads(raw)
```

## Important notes

- The user rebuilds AsterixDB themselves. Never run `mvn` builds.
- The user observes NC logs themselves. Ask them to paste relevant log sections.
- Always use `profile=timings` to get operator-level profiling data.
- Column datasets are named like `Lineitem_column_10` (SF10), `Lineitem_column_30` (SF30), etc.
- Run warmup queries to ensure hot cache before measuring.
- Use `curl` with `--data-urlencode` for the statement to handle special characters.
