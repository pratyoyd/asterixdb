# R1a: row test inside the scan for MAX / MIN, and DISTINCT with a WHERE (HLD, 2026-11-21)

Status: accepted by the user 2026-11-21 ("yes do D8 and LLD and everything"), nothing built yet. Code tree `/scratch/asterixdb_eightynode/asterixdb_v3`, branch `baleen`.
Plan item R1a in `2026_11_21_PLAQUE_LONG_TERM_FILTER_PLAN.md`. Builds on `2026_11_20_PLAQUE_ROW_LEVEL_SKIPPING.md`
(section 9) and `2026_11_20_PLAQUE_DISTINCT_NATIVE_COLUMNAR.md`.

## 1. Summary

- **What is added.** For `MAX(x)` and `MIN(x)`, the scan itself drops a row whose `x` cannot beat the best value
  found so far. Native columnar and Iceberg.
- **Page skipping stays as it is and still runs first.** A page (native) or a file or row group (Iceberg) that
  cannot beat the best value is not read at all, exactly as today. The row test is a second layer: it only acts
  on the pages that page skipping had to read, and drops the rows in them that cannot win.
- **The idea.** Baleen already does this test one step above the scan (the "tuple filter"). We move the same
  test into the scan, so a row that will be thrown away is never built.
- **The fix that comes with it.** On native, the scan has one slot for a row test, and the query's own WHERE
  takes it. Today Baleen then gives up (this is why DISTINCT gets no row drop when the query has a WHERE on the
  same table). The new piece fills the slot with *both* tests: a row is kept only if it passes the WHERE **and**
  the Baleen test. DISTINCT uses the same piece, so its row drop now works with a WHERE.
- **No new state.** The test reads the same "best value so far" that the tuple filter and the page skip read.
- **Expected gain.** Largest where pages cannot be skipped (data not sorted on `x`): there nearly every row
  is below the best value, yet every row is built today.

## 2. The problem, on one example

```sql
SELECT MAX(l.l_extendedprice) AS m FROM Lineitem_column_10 l WHERE l.l_shipmode = "AIR";   -- native
SELECT MAX(l.l_extendedprice) AS m FROM baleen_ice.lineitem l WHERE l.l_shipmode = "AIR";  -- Iceberg
```

What the server does today (plans taken from the running server on 2026-11-21):

```
aggregate MAX                        <- learns the best value so far
  Baleen tuple filter                <- drops rows with value <= best so far
    SELECT l_shipmode = "AIR"        <- the WHERE
      scan  Baleen page skip: pages (native) / files and row groups (Iceberg)
            that cannot beat the best value are not read
            (native: WHERE also runs inside the scan as its row test;
             Iceberg: WHERE only used to skip row groups)
```

Page skipping decides per page: a page is skipped only if *no* row on it can win. `l_extendedprice` is not
sorted, so nearly every page holds at least one large value and has to be read, although most of its rows are
below the best value. Every "AIR" row on such a page is built into a full record, passed up, and then dropped by
the tuple filter. That building and passing is the wasted work the row test removes. On a column that is sorted,
page skipping already removes most pages and the row test has little left to do; both layers stay on in every
case.

## 3. The idea

Two layers in the scan, both asking "can this beat the best value so far?":

| Layer | Unit | State |
|---|---|---|
| Page skip | Whole page (native), file or row group (Iceberg); not read at all | Exists, unchanged |
| Row test | One row on a page that was read; not built | New |

For the row test, the scan asks for each row on a page it reads: "does this row's `x` beat the best value so
far?" If not, the row is dropped there.

- **Native.** The scan's single row-test slot gets a combined test: the WHERE first, then the Baleen test. Both
  must pass. The same combined test carries the DISTINCT test ("value already seen?") when the query is a
  DISTINCT. This is ordinary predicate pushdown into the scan; what is Baleen's is that one part of the test
  tightens while the scan runs.
- **Iceberg.** Iceberg has no row test inside the reader today. After Iceberg reads a row, and before AsterixDB
  turns it into its own record, the reader looks at `x` and drops the row if it cannot win. The WHERE stays where
  it is today.

## 4. Why the answer stays correct

| Rule | Why |
|---|---|
| The scan test is the tuple filter's own test: same best value, same comparison, an equal value is dropped | An equal value cannot change MAX or MIN; the tuple filter already drops it |
| It is attached only to the scan right under the tuple filter, with only row-by-row steps in between (assign, select, project, exchange) | Then dropping in the scan removes exactly the rows the tuple filter would remove, only earlier |
| WHERE and Baleen test are combined with AND | Each only removes rows; a row survives only if both keep it, whichever is asked first |
| Null and missing values are always kept | MAX/MIN ignore them; the tuple filter keeps them too |
| Before any best value exists, every row is kept | Nothing is known yet |
| The best value only ever gets better | A row dropped earlier could never have won later either |
| Several nodes: each node's best value is a value some node has already counted | Same argument as the existing tuple filter |
| A native page skip always comes with a row test | Found 2026-11-20: a skipped leaf with no row test hands out empty records. For MAX this did not change the answer (empty values are ignored) but wasted work; now it cannot happen |
| Where the test cannot be built (section 9), nothing changes | The scan runs exactly as today; the tuple filter above still applies |

## 5. The pieces and where they sit

```
aggregate MAX ──writes best value──┐
  tuple filter (unchanged) ◄──reads┤
    SELECT (WHERE, unchanged)      │
      scan                         │
        page / file / row-group skip (unchanged) ◄── reads
          then, on each page that is read:
        [NEW] combined row test ◄──┘ reads   native: WHERE AND Baleen, one test per row
        [NEW] Iceberg row check ◄──┘ reads   Iceberg: after the row is read, before it is converted
```

**When each layer runs.** The page skip runs first, once per page, with the best value it has when the page
starts. On a page that is read, the row test runs once per row, before the row is built, and reads the best
value again for every row.

| Format | 1. Before the unit is read | 2. On each row of a unit that is read |
|---|---|---|
| Native | Leaf (page) skipped if its stored max (min) cannot win, with the best value at that moment | Row dropped if it cannot win, with the latest best value |
| Iceberg | File skipped by its bound; row groups skipped by a filter built from the best value at file open | Row dropped right after Iceberg reads it, with the latest best value |

The best value improves while a page is read, so a page that passes step 1 can still lose most of its rows in
step 2. On Iceberg the row-group filter keeps the value from file open for all row groups of the file; the row
test uses the newer one.

**When does the best value arrive?** The aggregate updates it as rows reach it, on the same node, through the
existing shared state. Between the scan and the aggregate the only buffering is the frame; the tuple filter
already sends rows up after every frame (early send), so the scan sees an improving value while it runs. Across
nodes the existing propagation is used.

**Corrected 2026-11-22 after running it.** The paragraph above was wrong in one place. The *scan* also holds
rows in a frame and sent it only when full. With the row test dropping about 399 of 400 rows, a frame filled
only near the end of a partition's scan, so the best value stayed at what the first frame had brought, and page
skip, file skip and the row test all used that old value. Measured: with the row test on, rows past page skip
on N1 rose from about 40 K to 1.9 M-9.1 M, and I1 skipped 1-22 of 30 files instead of 26, making MAX/MIN
queries slower than page skip alone (`2026_11_22_PLAQUE_R1A_RESULTS.md`). Fix agreed with the user the same
day: a scan whose row filter drops rows sends what it kept before each leaf (native) or file task (Iceberg),
setting `compiler.plaque.scanflush`, default on (LLD section 10). After the fix the row test matches or beats
page skip on every MAX/MIN query measured; measured cost at most +9 % (H2, DISTINCT on a high-cardinality
column with a join) (`2026_11_22_PLAQUE_SCAN_FLUSH_RESULTS.md`). The general lesson: a filter inside the scan
can slow down its own feedback, because it removes the rows that would improve the value it reads.

## 6. What exists and what is new

| Piece | Status |
|---|---|
| Best value so far, its sharing between operators and nodes | Reused as is |
| Tuple filter, page / file / row-group skip | Unchanged |
| Native row-test slot in the columnar reader | Reused; it now gets a combined test |
| Combined row test (WHERE AND list of Baleen tests) | New |
| Baleen MAX/MIN row test, native | New |
| Iceberg row check | New |
| DISTINCT native row drop | Moved into the combined test; same test as before |
| Setting to switch the MAX/MIN row test off | New |

Other rules on the same operators: the WHERE pushdown runs before the Baleen page-filter rule and is not
changed; the Baleen rule only adds to what it left.

## 7. Decisions

| # | Decision | Options | Chosen | Status |
|---|---|---|---|---|
| D1 | How WHERE and Baleen share the native slot | One combined test (WHERE first, then Baleen) / a second test stage in the reader / Baleen first | One combined test, WHERE first, so the Baleen counter shows rows the WHERE would have kept | Decided by user 2026-11-21 |
| D2 | Which columns, native | Top-level fields of any type, declared or not, nullable or not / also nested fields | Top-level now; nested later | Decided by user 2026-11-21 |
| D3 | Iceberg: where the check goes | (a) after the row is read, before conversion / (b) also run the WHERE in the reader / (c) look at the column before the row is built | (a): works on every Iceberg read path and cannot produce a wrong answer from a WHERE mismatch; (b) would also speed up the Baleen-off baseline and blur the measurement; (c) saves little for numbers | Chosen by me 2026-11-21 after the user found the options too detailed; user may override |
| D4 | Iceberg column types | Basic types / all comparable types | Basic types first: long, int, double, float | Decided by user 2026-11-21 |
| D5 | DISTINCT | Fix the WHERE case only / also lift its other limits | WHERE case only | Decided by user 2026-11-21 |
| D6 | Switch for measurement | New on/off setting / levels like DISTINCT | On/off setting, default on | Decided by user 2026-11-21 |
| D8 | Overhead when the row test drops little | Measure first, with a worst-case query in the benchmark / build a self switch-off now (stop testing for a while on a page that drops almost nothing, like the DISTINCT reader) | Measure first; the switch-off saves only the comparison on native, not the second column read, unless the reader is changed further | Decided by user 2026-11-21: measure first. Measured 2026-11-22: N4 (WHERE leaves few rows) and I4 (sorted) show no clear cost; the slowdowns found had a different cause (section 5 correction). No switch-off needed so far |
| D7 | Shape of the combined test | A list of Baleen tests / one test only | A list, so later row tests (R1b to R1d) plug in | Decided by user 2026-11-21 |

## 8. Expected effect and how it will be tested

Expectations, not measurements:

- **Helps:** data not sorted on `x` (page skip does little), most rows below the best value. Native should gain
  most (building a row is expensive there; DISTINCT level 4 went 41.7 s to 21.7 s on such a column). Iceberg
  should gain less: the row is still read and built by Iceberg, only AsterixDB's conversion and the steps above
  are saved.
- **With a WHERE on the same table:** today no row test from Baleen at all; after the change the same gain on
  the rows the WHERE keeps. For DISTINCT with a WHERE: today level 4 does nothing; after, it should behave like
  DISTINCT without a WHERE.
- **No difference:** data sorted on `x` (page skip already removes almost everything); a WHERE that already
  removes nearly all rows.
- **Could be slower** (corrected 2026-11-21 after the user asked about overhead; the first version said "close
  to zero", which was wrong). The row test costs something on every row, also when it drops nothing:
  - native, query without a WHERE: today the scan has no row test; with R1a the reader reads the MAX column a
    second time through the row test's own column reader (separate from the one that builds the row; read in
    the code), plus one comparison and the skip bookkeeping per row. Largest case; size unknown;
  - native, query with a WHERE: one more column reader and one comparison;
  - Iceberg: one field lookup and one comparison per row;
  - all: rows that are kept are still compared again by the tuple filter.
  It is wasted where little is dropped: before a best value exists, on data where new maxima keep coming, and
  when page skipping already removed almost everything.
- **Configurations:** Baleen off; Baleen on with the row test off (page / file skip only); Baleen on with the
  row test on. Each query with and without a WHERE on the same table. Native and Iceberg. Counters: rows
  dropped in the scan, rows reaching the tuple filter.
- **Worst case in the benchmark:** a query where the row test drops little (for example data sorted on `x`,
  where page skipping leaves only rows that can win), row test on versus off, native without a WHERE.
- **The design is wrong if:** results differ from Baleen off; the row test is on but rows dropped in the scan
  stay near zero on unsorted data; or Baleen on with the row test is slower than with it off on the queries it
  should help. On the worst-case query, a clear slowdown means D8 must be revisited.

## 9. Limits and risks

| Limit | Kind | What happens | Example that loses the benefit |
|---|---|---|---|
| Nested field | Choice, user said later | Not attached; tuple filter still works | `MAX(o.addr.zip)` |
| Iceberg column not long, int, double or float | Choice, user said basic types first | Not attached; shown in the plan and log | `MAX(l_shipdate)` if stored as a date or string |
| Column inside an array | Real: the row has no single value | Not attached | `MAX(x)` where `x` comes from unnesting an array |
| MAX/MIN of an expression across a join (`a ± b`) | Other plan item (R1b) | Unchanged | `MAX(l_extendedprice - ps_supplycost)` |
| DISTINCT's own limits (declared, not nullable, BIGINT or string, top-level) | Kept by user decision | Unchanged | `SELECT DISTINCT l_comment2` on an undeclared field |
| Test setup | Risk | Nodes on one machine share state; multi-node behaviour relies on existing propagation | — |

## 10. Not in this design

Row tests for cross-table, theta join and GROUP BY filters (R1b to R1d); the WHERE checked inside the Iceberg
reader; Iceberg types beyond the basic four (G8).

## 11. What the LLD must settle

Where exactly the combined test is assembled; how the counters are reported; the setting's name and where it is
read; how the Iceberg check is applied on each read path (standard, with deletes, variant-pruned, DISTINCT
level 4); how a declined case shows up in the plan and log; the unit tests.

## The ten questions of the hld skill

| # | Question | Answer |
|---|---|---|
| 1 | When does the information arrive where it is used? | Section 5: page skip once per page, row test once per row with the latest value; only the frame buffers between scan and aggregate, flushed per frame. **Wrong for the scan's own frame; corrected 2026-11-22, see section 5** |
| 2 | Is the plan what we think? | Both plans taken from the running server on 2026-11-21 (section 2) |
| 3 | What else rewrites this part of the plan? | WHERE pushdown runs before the Baleen page-filter rule and is not changed. Whether any later rule (for example one that clones the plan) strips the scan's Baleen fields was not checked: listed below as not verified |
| 4 | On which data does it help? | Unsorted on `x`: helps. Sorted: page skip already does the work (section 8) |
| 5 | How is the gain told apart from side effects? | Baleen on with the row test off versus on; same plan otherwise (D6) |
| 6 | Every format? | Native: leaf min/max, row test binding (row removed). Iceberg: file bounds and row-group statistics, row test binding. Both in scope |
| 7 | What does the test setup hide? | Nodes on one machine share the best value; multi-node relies on existing propagation (section 9) |
| 8 | Limits? | No caps: the state is one value. Unsupported column: test not attached, everything as today |
| 9 | Which decision rests on an estimate? | D3: that on Iceberg AsterixDB's conversion is the main cost per row. The benchmark measures it; if Iceberg gains little, option (c) is reconsidered |
| 10 | Source of the idea? | User's R1 item, 2026-11-21; `2026_11_20_PLAQUE_ROW_LEVEL_SKIPPING.md` section 9 |

## Decisions made without asking

- D3 option (a), after the user said the options were too detailed.
- Name of this file.

## Stated but not verified

- That the native MAX page skip without a WHERE hands out empty records: read in the code, not run.
- How AsterixDB maps Iceberg `int` and `float` columns: not checked.
- The type of `l_shipdate` on Iceberg: not checked.
- The expected gains in section 8 are carried over from the DISTINCT numbers.
- That no later optimizer rule removes or copies away the Baleen fields set on the scan (question 3).
