# Iceberg data skipping: review findings and to-do list (2026-11-16)

Review of commit `0be0b5caf5` on `baleen` ("Baleen: runtime data skipping for Iceberg scans (scalar MAX/MIN)").
No wrong result was found, but the work is not finished. This file is the list of what is left.

## To do

| # | Item | Status |
|---|---|---|
| 1 | Plan guard: the rule attaches the scan filter without checking which operators sit between the aggregate and the scan. Allow only ASSIGN, SELECT, PROJECT and inner-join inputs; bail out otherwise. | Explained to Pratyoy; fix waits for his go-ahead |
| 2 | Tests: unit tests for `PlaqueIcebergScanFilter` (missing bound, NaN count, type tag mismatch, MAX/MIN direction, ordering) and a plan-level test for the rule. | Waits for go-ahead |
| 3 | Theta join: the theta-join caller now also enables file skipping on Iceberg. Run a theta-join query off/on on Iceberg and check the threshold meaning. | Waits for go-ahead |
| 4 | Variant-pruned read: the row-group filter is not applied on that path and the "filtered" counter is still incremented. | Explained to Pratyoy; fix waits for his go-ahead |
| 5 | Show the scan filter in the plan (`print()`), and include it in `equals()`. | Waits for go-ahead |
| 6.2 | Restrict the rule to Iceberg scans; today it marks any external scan. | Waits for go-ahead |
| 7 | Strict comparison: use `>` instead of `>=` (MAX) and `<` instead of `<=` (MIN) so ties are dropped, both in the file skip / row-group filter and, where valid, in the tuple filter. Pratyoy's suggestion, 2026-11-16. | Not started; needs a check that the tuple filter can be strict for each query shape |
| 8 | `WHERE` predicate on the Iceberg tables fails at compile time with a NullPointerException, with Baleen off as well. | Time-boxed exploration by a subagent started 2026-11-16 |

Not scheduled: 6.1 (stats requested for all columns when a variant filter is present), 6.3 (log level of the per-reader summary), malformed JSON in the query service response (missing comma after `requestID`), `NOT IN` failure on the merged build.

## Checks run during the review (Baleen off vs on, `lineitem_by_suppkey`)

| Query | Result |
|---|---|
| `MAX` and `MIN` in one block | Same result; Baleen does not fire |
| `MAX` and `COUNT(*)` in one block | Same result; Baleen does not fire |
| Two subqueries, `MAX` and `COUNT` | Same result |
| Two subqueries, `MAX` and `MIN` | Same result; 35.4 s off, 4.8 s on |
| `MAX` over `ORDER BY l_orderkey LIMIT 1000` subquery | Same result (99939), but the scan filter was attached below the ORDER/LIMIT; see item 1 |
| `orders LEFT OUTER JOIN lineitem_by_suppkey`, `MAX(l_suppkey)` | Same result; no skipping |
| `MAX(l_suppkey) ... WHERE l_quantity < 3` | Fails with Baleen off and on; item 8 |
