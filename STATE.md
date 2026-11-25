# Baleen: current state

Last updated: 2026-11-24.

**Two halves.** Sections 1 to 8 are the implementation half, written by Claude Code from the code on branch
`baleen` and the dated notes in this folder. Section 9 is the ideation half (theory, paper, framing, decisions
made in chat) and is to be written by the desktop Project. Each side writes only what it knows and marks what
it is unsure of; neither side rewrites the other's half without the user saying so.

**This file is the shared reference for every Claude surface** (Claude Code on sensoria, the Claude desktop
Project). If a stored memory or an older note disagrees with this file, this file wins. Anything durable (a
decision, a result, a terminology change) is written here or in a dated note next to it, not only in one
surface's memory.

Items marked **(to confirm)** are my reading and have not been confirmed by the user.

## 1. What Baleen is

Baleen is a set of filters that a query learns while it runs. Example: while `SELECT MAX(x)` runs, the best
value of `x` found so far is a bound; any row, page, Parquet row group or data file that cannot beat the bound
is dropped or left unread.

- Built in a fork of Apache AsterixDB (`pratyoyd/asterixdb`, branch `baleen`), for two storage formats: native
  columnar datasets and Apache Iceberg tables (Parquet).
- **Baleen** is the project name. **PLAQUE** (Predicate Learning at Query Time) is the older internal name; it
  stays in class names (`PlaqueRewriteRule`), settings (`compiler.plaque.enabled`) and file names.
- **SmartRabbit** (interactive query processing) lives in the same fork but is a separate project. Do not bring
  it up unless the user does.

## 2. Terms

From the theory notes (`2026_11_21_PLAQUE_BALEEN_THEORY.pdf`, glossary in appendix A):

| Term | Meaning |
|---|---|
| Advancing bound | The value a group's answer is already known to reach; it only tightens during the query |
| Skip point | A place in the plan where a tuple or granule is tested against the bound |
| Granule | A physical unit that can be dropped at once: row, page (mega leaf node), row group, file |
| Envelope | What is known about a granule without reading it (min/max, dictionary) |
| Ceiling | The best score a tuple or granule could reach, computed from its envelope |
| Vestigial | An input that can no longer change the answer. Pruned is the action; invalidated (cannot beat the bound) and saturated (the group is already complete) are the two reasons |
| Saturation | A group whose answer no longer depends on unread input (DISTINCT, EXISTS, LIMIT) |

The implementation notes use plainer words for the same things: "best value so far" (the bound), "page skip",
"file skip", "row test", "tuple filter".

The writing rules for these terms (for example: always "advancing bound", never "order" in TPC-H or
grouping text) are in section 9.1.

## 3. What is built

Filter ids are those of `2026_11_21_PLAQUE_LONG_TERM_FILTER_PLAN.md`.

| Id | Filter | What the rule accepts | Native columnar | Iceberg |
|---|---|---|---|---|
| E1 | Scalar MAX / MIN | One aggregate, one column; crosses inner and left outer joins | Page skip, row test in the scan, scan flush | File and row-group skip, row test, scan flush |
| E2 | MAX / MIN of `a ± b` across a join | Binary add or subtract | Page and exchange filter | Tuple filter only (1.25x) |
| E3 | Top-K of `a ± b` across a join | DESC only, one sort key | Built; not rerun since the upstream merge | Not built |
| E4 | Theta join | One comparison between two columns | Benchmarked | Benchmarked |
| E5 | GROUP BY MAX / MIN | One group key | Tuple filter; page skip only on clustered layout | Tuple filter only |
| E6 | DISTINCT | One expression, probe side | Levels 1, 2, 4 | Levels 0 to 4 |
| R1a | Row test inside the scan for scalar MAX / MIN | see E1 | Any top-level field, any type | int, long, float, double |

Settings (all per query, sent as `SET` statements): `compiler.plaque.enabled`, `compiler.plaque.rowfilter`
(MAX/MIN row test), `compiler.plaque.scanflush` (scan sends kept rows before deciding on the next leaf or
file). All default to true. "Baleen off" always means the same build with `compiler.plaque.enabled` false.

Plain Top-K on one column is **not** matched today (read from the rule, not run).

## 4. Headline results

TPC-H scale factor 10, one server, 1 cluster controller + 4 node controllers in one JVM. Medians of 6 runs,
seconds. Every Baleen-on run returned the same result as Baleen off.

Native columnar and Iceberg, 2026-11-22 (`2026_11_22_PLAQUE_R1A_RESULTS.md`, `2026_11_22_PLAQUE_SCAN_FLUSH_RESULTS.md`):

| Query | Baleen off | Page skip only | Row test, no flush | Row test + flush |
|---|---|---|---|---|
| N1 native MAX, no WHERE | 23.5 | 0.11 | 0.61 | 0.10 |
| N2 native MAX, WHERE | 15.8 | 0.17 | 2.82 | 0.12 |
| N3 native MIN string, WHERE | 31.7 | 35.1 | 12.3 | 11.9 |
| N6 native DISTINCT, WHERE | 245.4 | 31.7 | 11.3 | 10.4 |
| I1 Iceberg MAX, no WHERE | 19.1 | 2.77 | 3.08 | 0.59 |
| I2 Iceberg MAX, WHERE | 30.3 | 4.12 | 5.05 | 1.08 |
| I5 Iceberg MAX over a join | 38.7 | 13.5 | 12.0 | 9.9 |
| I6 Iceberg DISTINCT, WHERE | 336.2 | 4.75 | 0.70 | 0.69 |

Iceberg, all experiments of 15 to 22 November (`2026_11_23_PLAQUE_ICEBERG_EXPERIMENTS_SUMMARY.md`, also as PDF):

| Query shape | Baleen off | Baleen on | Speedup |
|---|---|---|---|
| `MAX(l_suppkey)`, single table | 19.1 | 2.5 | 7.76x |
| `MAX(l_suppkey)` over a join | 36.1 | 11.1 | 3.27x |
| `GROUP BY` with `MAX` | 56.9 | 30.0 | 1.90x |
| Theta join, `COUNT(*)` | 22.4 | 3.8 | 5.89x |
| `MAX(l_extendedprice)` | 19.1 | 0.59 | 32.4x |
| `DISTINCT l_shipmode`, join | 203.8 | 9.4 | 21.65x |
| `DISTINCT l_shipmode`, no join | 402.5 | 0.4 | 919x |
| `MAX(a - b)` across a join (3 runs) | 57.0 | 45.6 | 1.25x |
| `MIN` of a string column with `WHERE` | 32.0 | 30.1 | 1.06x |

The last two rows are where Baleen does little on Iceberg today.

## 5. Defects found in query shapes not run before

Every result in section 4 and in the dated notes is valid: each Baleen-on run was checked against Baleen off.
The items below were found on 2026-11-23 by code review and by trying shapes and data that no benchmark had
used (negative doubles, `a + b`, `b - a`, null group keys, a second aggregate). They limit which new queries
can be run today, not what was measured.

| Item | State |
|---|---|
| GROUP BY MAX/MIN next to another aggregate (`MAX(v), COUNT(*)`): the other aggregate sees filtered rows | Wrong result for the other aggregate; not fixed. Not a shape that was benchmarked |
| Cross-table MIN can track a maximum (shared state created with `isMax = true`) | Results correct, filtering lost; cause read from code only |
| Cross-table bound: rounding in the last digit, bound taken over only some build partitions, Long extremes | Open from the review |
| Double columns compared by bit pattern in the page filters (wrong skip on negatives) | Fixed, committed `c3cf0e088b` |
| `MAX(a + b)`, `MAX(b - a)` bounds, GROUP BY string keys, null group keys, ghost rows after a skipped last leaf | Fixed 2026-11-23 evening, **not committed**, no 6-run benchmark, not reviewed |

Details: `2026_11_23_PLAQUE_DOUBLE_PAGE_FILTER_FIX.md`, `2026_11_23_PLAQUE_WRONG_SKIP_FIXES.md`.

The 2026-11-23 fixes narrow GROUP BY page skipping: string group keys longer than 3 characters and group
fields that are optional or undeclared no longer get it (the row filter still applies).

## 6. Roadmap

Order set by the user on 2026-11-21; full table in `2026_11_21_PLAQUE_LONG_TERM_FILTER_PLAN.md`.

1. R1a row test for scalar MAX / MIN: **done 2026-11-22**
2. **G1** plain Top-K (DESC and ASC, with and without a join): **next**
3. **G2** Top-K with OFFSET
4. **G3** cross-table MAX / MIN and Top-K of `a ± b` on Iceberg
5. **I1** groups ranked by their key, **I3** rank window with a cutoff, **I2** DISTINCT ORDER BY LIMIT
6. The S block: EXISTS / IN, LIMIT, DISTINCT LIMIT, `HAVING COUNT`, `COUNT(DISTINCT)` (order inside the block
   is a proposal **(to confirm)**)
7. After most single filters exist: several filters in one query, then helper structures and plan changes

Scope for now: no helper structure carried from the build side to the probe, no plan change. Every filter is
built for native columnar and Iceberg and pushed as far down as it can go.

Target benchmark queries: TPC-DS Q37/Q82, Q16/Q94/Q95, Q10/Q35/Q69, Q14 `cross_items`, Q7/Q15/Q26/Q27; TPC-H
Q18; TPC-DS Q84; LDBC IC9/IC2; H2O q8. Most reach their full gain only with a helper structure or a plan change.

Waiting for the user:

- Scan flush cost of +9 % on one high-cardinality DISTINCT query (H2): leave, or send early only when few rows
  were kept
- Left outer joins crossed on both sides by the row test: confirm
- Where R1b, R1c, R1d (row test for cross-table, theta, GROUP BY) go in the order
- Commit of the 2026-11-23 evening fixes

## 7. How the work is done

Per filter: read what the code already has, high-level design, low-level design (both discussed with the
user), implementation and unit tests, experiment plan approved before running, benchmark, dated write-up.

- **Benchmarks:** 6 runs per configuration including Baleen off; full query text with `SET` statements; every
  run recorded; raw tables before interpretation.
- **Reports** end with two lists: "Decisions I made without asking" and "Stated but not verified".
- **No narrowing shortcuts:** a filter must not silently switch off in a common query shape (a WHERE on the same
  table, a second aggregate, a nullable column). Build the general case or ask first with an example query.
- **Write-ups:** one per working day in this folder, named `<YYYY_MM_DD>_PLAQUE_<TOPIC>.md`.

## 8. Where things are

| What | Where |
|---|---|
| Code | sensoria-2, `/scratch/asterixdb_eightynode/asterixdb_v3`, branch `baleen` (remote `origin` = `pratyoyd/asterixdb`) |
| Design notes and results | `plaque/` in that tree: a worktree of the orphan branch `plaque-docs`, ignored on the code branch |
| Theory notes | `2026_11_21_PLAQUE_BALEEN_THEORY.pdf` (sections 16 to 18: plan changes, vestigiality, saturation) |
| Benchmark-target report | `2026_11_21_PLAQUE_WHERE_BALEEN_WINS.pdf` |
| Iceberg summary for the paper | `2026_11_23_PLAQUE_ICEBERG_EXPERIMENTS_SUMMARY.md` / `.pdf` |
| Latest day notes | `2026_11_22_PLAQUE_R1A_PROGRESS.md`, the two `2026_11_23_*` fix notes |
| Summer milestones (M1 to M3, theta join, clustered layout) | undated `PLAQUE_*.md` files; older than the upstream merge |

## 9. Ideation half (written by the desktop Project, 2026-11-24)

Sources: the desktop Project's stored notes, the project docs (`baleen_theory.pdf`, the theory `.tex` sections,
`TPCH_PREDICATE_GAP.md`, the "Where BALEEN wins" report, the BALEEN keynote deck) and chats from October and
November. Anything marked **(to confirm)** may be out of date. Implementation numbers from my notes are
dropped; sections 3 to 5 are the reference for those.

Two sentences were reworded by Claude Code on the user's instruction (2026-11-24), in 9.2 "Safety" and 9.6
item 4: the measured results are valid, and the defects in section 5 are in query shapes that had not been run.

### 9.1 Terms and writing rules

Section 2 matches what I have. Additions and rules the user set in chat:

| Term or rule | Meaning |
|---|---|
| Advancing bound | Always this phrase. Never "order bound" |
| Saturation, saturated | The only word for a group or answer that can no longer change. Not "settled" or "complete" |
| Invalidated | The other reason an input is vestigial: it cannot beat the bound |
| Floor τ | The advancing bound itself; τ_g per group |
| Static ceiling source | Zone maps and build-side per-key extrema. Fixed once the build finishes; only τ moves |
| Granule tree | Granules nest (tuple, page, superpage, component, file, relation) |
| T(Γ) | The set of outputs granule Γ can contribute to |
| L_q, U_q | Rank-q bounds computed by walking the envelopes |
| Observer / filter | Observer is where the bound is learned; filter is the skip point that uses it. The observer is not always at the pipeline root (counterexample: LIMIT under a filter) |
| INLJ | The per-row lookup in the rewritten Q18 plan. Not "probe" |

Writing rules: never use "order" in TPC-H or grouping text (it can mean the `orders` table, a group or a sort);
say "group", "l_orderkey value", "sorted by". Draw plans as trees with tables at the leaves, hash join build on
the right. Use real attribute names (`cs_item_sk`, not "the item key"). Benchmarks: median of warm runs.

### 9.2 Theory framing

- **One idea:** an input is *vestigial* when it can no longer change the answer. That happens in two ways,
  invalidation (cannot beat τ) and saturation (its group is already fixed). Every Baleen filter is one of the two.
- **General theorem (in progress, to confirm current form):** top-k over a monotone SPJ expression, lineage sets
  T(r), per-group floor τ_g, page test against the minimum τ_g over the groups a page can touch. `MAX(R)` =
  `MAX` over pages of `MAX(page)` is the base case. Rank join (Ilyas, HRJN / NRA-RJ) is this page test on a
  fully sorted layout, where skipping becomes stopping.
- **Classification used in the advisor deck (late October):** reason (saturation vs. invalidation), target
  (within groups, on group keys, on the aggregate of groups), placement (where the filter can sit, including
  whether a plan change is needed). A 12-axis version was rejected as unworkable.
- **Helper structure** was argued down as a separate formal concept: "reach" and "ceiling" already cover it
  **(to confirm)**. Section 6 still uses the word for the implementation scope, which is fine as plain language.
- **SIP from finished pipelines** (the advisor's objection): resolved by keeping the existing "pipeline answer"
  definition; outputs of finished pipelines count as inputs the current pipeline has already observed.
- **Safety:** stale bounds and loose ceilings only under-prune, never wrongly prune; no synchronization beyond
  eventual delivery. Section 5 lists the defects found so far in query shapes outside the benchmarks.
- **Governing variables:** how extreme the threshold is within the column, and how well the layout lines up with
  it. For MAX, a layout sorted ascending on the aggregate column is the worst case.
- **Set aside:** exact quantiles via two-pass zone maps (late October). Still wanted in the formalism later:
  hierarchical zone maps, GROUP BY through a join (key on build side, aggregate on probe side).

### 9.3 Paper state

- **Venue:** full paper, PVLDB, Feb 1, 2027 deadline **(to confirm)**. DOLAP 2027 short paper is dropped; the
  advisor abandoned it.
- **Framing (October):** Baleen as a way for the optimizer to reason about plans in terms of ranking and
  saturation opportunities, and to cost them. Planned sections: filters by kind of ranking, cost functions for
  ranking in the optimizer, new rewrite rules. Earlier framing (advancing bounds, broken feedback loops in
  parallel block-oriented engines, distributed propagation) is now background, not the lead.
- **Motivating examples** (R small ⋈ S large, filter on S): (1) GROUP BY key + ORDER BY key LIMIT, needs a plan
  change to an R-driven INLJ; (2) ORDER BY SUM over R's column, eager aggregation as a ceiling source;
  (3) saturation, DISTINCT finishing early instead of at the end. Each with the conditions under which the Baleen
  plan wins.
- **Section status, source location:** I don't know. The plan and sections are provisional and change with the
  advisor; the latest Overleaf is the reference. **The user should fill in where the source lives.**
- **Strongest claims so far (from chat):** cross-table `a ± b` bound reduced to a one-column predicate; keyed
  per-group bounds through the build side driving page skips (no prior work found that combines all four parts);
  saturation stopping a fact scan, which no engine documents.
- **Not to be used as a headline:** the 21.8x theta-join page skip (a ceiling case). Any summer number from before
  the upstream merge should be rerun before it is quoted.

### 9.4 DuckDB experiments

- **Stopping scan, TPC-DS Q37 and Q82 (theory notes section 18):** DuckDB 2.0, 2 threads, warm median of runs 2
  to 4. The stopping scan is *emulated*: the query gets only the first N fact rows in stored order, where N is
  where the last candidate item first matches; the answer equals DuckDB's. Results: 1 row group read vs. 13 to
  235; 1.3x to 2.9x over DuckDB with predicate transfer on; the margin grows with scale factor. There is no real
  operator; the harness ran in a chat sandbox and **is not in any repo that I know of (to confirm)**.
- **DISTINCT over a join (same section):** 1,000 groups saturated by row 23,410 of 20 million (0.12%), matching
  the coupon-collector estimate.
- **TPC-H predicate gap (`TPCH_PREDICATE_GAP.md`, DuckDB 1.5.6, SF1 and SF10):** DuckDB does 6.5 to 7.6x the
  minimum work. 10 of 22 queries have a gap only an operator-induced bound can close (Q2, Q3, Q10, Q11, Q15, Q17,
  Q18, Q20, Q21, Q22), and top-k gaps grow about 10x from SF1 to SF10. Code: `ladder.py` plus one module per
  query, delivered as a zip in chat, not in the repo.
- **Does DuckDB work continue?** Open. In early November the user was weighing AsterixDB against DuckDB as the
  engine. Findings then: DuckDB can take injected plans (`OptimizerExtension`, `LogicalExtensionOperator`) but
  still picks join algorithms itself; `PhysicalTopN` already does zone-map dynamic filtering for one sort key;
  no INLJ operator. AsterixDB accepts hand-built job specs and has join hints. **No decision recorded.**

### 9.5 Decisions and ideas from chat not in any file

Kept:
- Q18 is the main TPC-H case: groupjoin / INLJ rewrite starting from `orders`, zone-map floor on `o_totalprice`
  skips 3,407 of 3,663 `orders` pages. Q3 and Q10 are weak: Q3's gain is join work only, not page I/O, because
  its group finishes only after full aggregation. Q15 and Q21 were left to check.
- Top-k through a dimension sort key (TPC-DS Q7, `i_item_id`): apply Baleen after predicate transfer has run, as
  a further Yannakakis-style pass. The user thinks this is unproposed and wants to state it as a theorem.
- Positioning: Snowflake's pruning (Zimmerer et al.) is equivalent on overlapping query classes; use it as
  evidence of production value, not as something Baleen beats. Baleen's edge is per-group and through-join bounds
  and saturation. Oracle's duplicate-elimination patent is the narrower case of saturation (it needs post-join
  references to be join keys). Rank join needs sorted inputs; Baleen doesn't, but it still visits every page's
  metadata.
- Showcase beyond TPC: semantic / LLM operators (SemBench `DISTINCT city ... WHERE AI.IF(...)`), where saturation
  cuts LLM calls, and remote Iceberg, measured in bytes and dollars.
- Patent: the user considered filing on saturation (DISTINCT over hash join) before publishing **(to confirm)**.

Rejected:
- DOLAP short paper (advisor dropped it); an Iceberg + DISTINCT experiments paper (too narrow).
- Helper structure as a separate formal concept (see 9.2).
- Framing Baleen as generally beating Snowflake.
- Leading with the theta-join 21.8x number.

### 9.6 Where this disagrees with sections 1 to 8

1. **Scope vs. paper.** Section 6 says no plan changes and no helper structures for now. The paper's distinctive
   claims (Q18 INLJ rewrite, the cost model, the saturation stopping scan on Q37/Q82) all need a plan change or a
   probe that stops. With the deadline on Feb 1, either the paper leads with what is built (E1 to E6 on native and
   Iceberg), or one plan change gets built before then. This needs a decision.
2. **Engine.** My notes say paper experiments stay on AsterixDB, but the theory notes' saturation evidence is
   DuckDB-only and emulated. The paper can't mix the two without saying so.
3. **Theta-join and summer results.** My notes had 3x NLJ tuple reduction and 9.3x at SF100 (cross-node
   propagation). Section 8 says those predate the upstream merge. Treat them as not current until rerun.
4. **GROUP BY.** My notes had 6.3x clustered / 1.4x normal from the summer (before the upstream merge, see
   item 3). The measured GROUP BY results are valid. A second aggregate next to MAX/MIN (`MAX(v), COUNT(*)`) is
   not supported yet (section 5). The 2026-11-23 change removes page skipping for string group keys longer than
   3 characters, so the clustered number needs a rerun if its group key is such a string.
5. **Distributed propagation.** It was the lead of the old framing; every result in section 4 is one machine,
   4 NCs in one JVM. If the paper still claims anything about propagation, it needs a multi-node run.

## 10. Keeping this file current

- Claude Code updates sections 3 to 6 and 8 together with each day's write-up.
- After an ideation session in the desktop Project, save a short decision note in this folder
  (`<YYYY_MM_DD>_PLAQUE_IDEAS_<TOPIC>.md`: what was decided, why, what was rejected) and, if it changes the
  roadmap or the terms, update sections 2 and 6 here.
