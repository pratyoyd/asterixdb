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

**(to confirm)** The desktop Project mentioned terminology rules ("advancing bound", "saturation", avoid
"order"). I could not check these from the repo. The user should correct this section once.

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

## 5. Known wrong results and open defects

| Item | State |
|---|---|
| GROUP BY MAX/MIN next to another aggregate (`MAX(v), COUNT(*)`): the other aggregate sees filtered rows | **Confirmed wrong result, not fixed** |
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

## 9. Ideation half (to be written by the desktop Project)

Empty so far. Claude Code has no source for any of this:

- Theory: current framing and terms, and what changed since the theory notes of 2026-11-21 (correct section 2
  if it is out of date)
- Paper: venue, framing, section status, schedule, where the source lives
- The DuckDB stopping-scan experiments in section 18 of the theory notes (TPC-DS Q37, Q82): where that code is
  and whether it continues
- Ideas and decisions from chat that never reached a file in this folder, including ones that were rejected
- Anything in sections 1 to 8 that contradicts what the Project knows: list it here, do not edit it there

## 10. Keeping this file current

- Claude Code updates sections 3 to 6 and 8 together with each day's write-up.
- After an ideation session in the desktop Project, save a short decision note in this folder
  (`<YYYY_MM_DD>_PLAQUE_IDEAS_<TOPIC>.md`: what was decided, why, what was rejected) and, if it changes the
  roadmap or the terms, update sections 2 and 6 here.
