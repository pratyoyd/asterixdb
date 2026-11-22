# Baleen: long-term plan for the remaining filters (2026-11-21)

Order set by the user on 2026-11-21. Nothing in this document has been built or run yet.

Sources: `BALEEN_keynote.pdf` (115 slides, read in full), `2026_11_21_PLAQUE_BALEEN_THEORY.pdf`,
`2026_11_21_PLAQUE_WHERE_BALEEN_WINS.pdf`, and the code on branch `baleen` (`PlaqueRewriteRule`,
`PlaquePageFilterRule`) with the write-ups up to 2026-11-20.

## Scope

- Filters that need no helper structure (no build-side structure carried to the probe) and no plan change.
- Every filter is built for native columnar and for Iceberg.
- Every filter is pushed as far down as it can go: tuple filter, below the join, page / file / row group,
  rows inside the reader.
- After most single filters exist: several filters in one query (`MAX(a), MAX(b)`) and cross filters.
- Helper structures and plan changes come after that.

## How each filter is done

1. Read what the code already has for it and report that.
2. High-level design, discussed with the user.
3. Low-level design, discussed with the user.
4. Implementation and unit tests.
5. Experiment plan (full matrix, run count, limits in the code), approved by the user before running.
6. Benchmark, 6 runs per configuration including Baleen off, native and Iceberg, full query text recorded.
7. Dated write-up in this folder.

## Order

| Step | Id | Filter | Example |
|---|---|---|---|
| 1 | G1 | Plain Top-K, DESC and ASC, with and without a join | `SELECT * FROM lineitem ORDER BY l_extendedprice DESC LIMIT 10` |
| 2 | G2 | Top-K with OFFSET (ranks a through b) | `... ORDER BY x DESC LIMIT 10 OFFSET 20` |
| 3 | G3 | Cross-table MAX / MIN and Top-K of `a ± b` on Iceberg | `MAX(l_extendedprice - ps_supplycost)` |
| 4 | I1 | Groups ranked by their key | `GROUP BY a ORDER BY a LIMIT k` |
| 5 | I3 | Rank window with a cutoff | `ROW_NUMBER() OVER (PARTITION BY g ORDER BY x DESC) <= k` |
| 6 | I2 | DISTINCT ranked by its value | `SELECT DISTINCT x ... ORDER BY x LIMIT k` |
| 7 | S2 | EXISTS / IN / semi-join | seen-set on the join key of the inner side |
| 8 | S1 | LIMIT n without ORDER BY | stop after n answer rows |
| 9 | S5 | DISTINCT with LIMIT n | stop after n distinct values |
| 10 | S3 | `HAVING COUNT(*) >= k`, key-only output | group saturated after k rows |
| 11 | S4 | `COUNT(DISTINCT x)`, ungrouped and per group | pair (g, x) seen |

The order inside the S block (steps 7 to 11) is a proposal; the user said "then all the S ones possibly".

## Row-level test inside the scan for every filter (R1, added by the user 2026-11-21)

User: "all existing filters can benefit from the row wise test on the columns like distinct on native
columnar". Marked "also important"; its position in the order above is not set yet.

What exists: only DISTINCT drops rows inside the reader (level 4: Iceberg strings through
`PlaqueDistinctDictionaryReader`; native BIGINT and strings through `PlaqueDistinctIterableFilterEvaluatorFactory`).
Every other filter has the page / file skip and a tuple filter above the scan. See
`2026_11_20_PLAQUE_ROW_LEVEL_SKIPPING.md`, section 9.

| Id | Filter | Row test inside the scan | State |
|---|---|---|---|
| R1a | Scalar MAX / MIN (E1) | Value cannot beat the bound: drop | Not built |
| R1b | Cross-table `a ± b` (E2, E3) | `a` cannot beat bound adjusted by the build-side extreme: drop | Not built |
| R1c | Theta join (E4) | Value fails against the learned extreme: drop | Not built |
| R1d | GROUP BY MAX / MIN (E5) | Value cannot beat the bound of the row's group (two columns per row): drop | Not built |
| R1e | DISTINCT (E6) | Value already seen: drop | Built; Iceberg covers strings only |

Every new filter in the order above (G1 to S4) gets its row-level test as part of its own work, under "pushed
as far down as it can go".

Known limits carried over from the DISTINCT implementation: native needs the column declared and not
nullable; the row drop is not attached when the query has its own filter pushed into the same scan; a leaf
skip must always come with a row filter (wrong result found and fixed on 2026-11-20).

Position: R1a first, before G1 (confirmed by the user 2026-11-21). Still proposed, not confirmed: R1b with G3; R1c and R1d after I2. Reason for R1a first: because plain Top-K reuses the scalar bound and its
scan filter and would get the row test for free.

Not ordered yet:

| Id | Filter |
|---|---|
| G4 | GROUP BY skipping on Iceberg by dictionary |
| G5 | GROUP BY with more than one key |
| G6 | DISTINCT with more than one expression |
| G7 | Theta join with extra conjuncts, band, or equi-key plus inequality |
| G8 | Column types beyond long and double for Iceberg skipping |
| I4 | Median / quantile, single table, no predicate |
| D1 | Top-K over a grouped MAX (`GROUP BY g ORDER BY MAX(x) DESC LIMIT k`) |
| D2 | `RANK` / `DENSE_RANK` cutoffs, `NTH_VALUE`, `FIRST_VALUE` |
| D3 | `HAVING MAX(x) > c` / `HAVING MIN(x) < c`, key-only output |
| D4 | `HAVING SUM(x) > c` with x >= 0, key-only output |
| D5 | NOT EXISTS / NOT IN / anti-join: duplicate inner rows dropped |
| D6 | UNION, INTERSECT, EXCEPT |
| D7 | Multi-key ORDER BY with LIMIT |
| D8 | `x > ALL (subquery)`, `x > ANY (subquery)` |

Then: combinations of filters, then helper structures and plan changes (top-k on SUM, keys on the other
table, index nested-loop rewrites).

## What exists today (read from the code, 2026-11-21)

| Id | Filter | What the rule accepts | Native columnar | Iceberg |
|---|---|---|---|---|
| E1 | Scalar MAX / MIN | One aggregate, one column; crosses inner and left outer joins on the probe side | Page skip, 6-run benchmark | File and row-group skip, 6-run benchmark |
| E2 | MAX / MIN of `a ± b` across a join | Binary add or subtract | Page and exchange filter | Not handed to the reader (1.25x) |
| E3 | Top-K of `a ± b` across a join | DESC only, one sort key | Built; not rerun since the upstream merge | Not built |
| E4 | Theta join | One comparison between two columns | 6-run benchmark | 6-run benchmark |
| E5 | GROUP BY MAX / MIN | One group key, first MAX/MIN found | Tuple filter; page skip only on clustered layout | Tuple filter only |
| E6 | DISTINCT | One expression, probe side | Levels 1, 2, 4 | Levels 0 to 4 |

Plain Top-K on one column is not matched: `rewriteTopKCrossTableExpression` returns unless the sort key is a
binary add or subtract across a join. Read from the rule, not confirmed by running a query.

## Benchmark queries this work is meant to reach

From `2026_11_21_PLAQUE_WHERE_BALEEN_WINS.pdf` and the user's note of 2026-11-21.

| Class | Queries | Filter here | Reachable without a helper structure or plan change? |
|---|---|---|---|
| 1. Saturation over joins | TPC-DS Q37, Q82 | S2 / E6 over a join | Partly. The group columns are on the build side (item) and the fact table is the probe. Stopping the fact scan needs the live-key set built with the hash table (theory, section 18.5), which is a helper structure. |
| 1 | TPC-DS Q16, Q94, Q95; Q10, Q35, Q69 | S2 | To be checked per query. Where the group is the join key itself, a seen-set on the probe key is enough. |
| 1 | TPC-DS Q14a/b `cross_items` | D6 (INTERSECT) | To be checked. |
| 1 | DISTINCT-over-join micro-test | E6 | Done for the probe-side column; the build-side column needs the helper structure. |
| 2. Ranking on a key reached through joins | TPC-H Q18, TPC-DS Q84, LDBC IC9, IC2, IC8 | G1 plus a plan change | No. These need the index nested-loop plan. G1 gives the same-pipeline baseline the report asks for as an ablation. |
| 3. Ranking on a grouping key on a dimension table | TPC-DS Q7, Q15, Q21/Q40, Q25/Q29, Q26, Q27, Q62/Q99 | I1 | Partly. The key is on the build side, so without a helper structure the skip point sits after the join and saves aggregation only. |
| 4. Showcases | H2O db-benchmark q8 | I3 | Yes, single table. |
| 4 | SemBench; Q7/Q26/Q27/Q84 on Iceberg in S3 | G1, I1, S block | Later; needs semantic operators or real object storage. |

Set aside by the user: TPC-DS rank windows over aggregates (Q44, Q49, Q67, Q70), sorts by an aggregate (Q3,
Q19, Q42, Q52, Q55), ClickBench, SSB, LDBC BI, TPC-H Q3 and Q10. TPC-H Q2, Q3, Q21 lost in the replay. TPC-H Q15
has no recorded result.

## Open points

- Classes 1 to 3 reach their full gain only with the build-side structure or a plan change, both of which are
  outside the current scope. Steps 1 to 11 prepare the observers, bounds and scan filters those later steps
  reuse.
- How AsterixDB plans semi-joins, anti-joins, window functions and set operations has not been read yet; it
  decides how hard S2, I3, D5 and D6 are.
- Whether AsterixDB already stops a scan early for LIMIT without ORDER BY has not been checked.

## Decisions made without asking

- Proposed order inside the S block.
- File names of the two saved PDFs follow the date-first convention with today's date.

## Stated but not verified

- Plain Top-K not being matched (read from the rule only).
- The "reachable" column of the benchmark table is argued from the query text in the report and the theory
  notes; no TPC-DS query was planned or run on AsterixDB.
- D1, D4 and D5 are argued only (nulls, ties and partial aggregates across nodes not worked through).
