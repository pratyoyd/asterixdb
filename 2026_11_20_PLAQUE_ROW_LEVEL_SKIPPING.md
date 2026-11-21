# PLAQUE: row-level skipping inside the scan (level 4), explained (2026-11-20)

This note records the explanations given in discussion on 2026-11-20: what level 4 is in relation to page
skipping, where the idea sits among known database techniques, and whether it applies to the earlier filters.
It contains no new measurement; numbers are quoted from `2026_11_20_PLAQUE_DISTINCT_LEVEL4.md` and
`2026_11_20_PLAQUE_DISTINCT_NATIVE_COLUMNAR.md`.

## 1. In one sentence

Level 4 asks "is this row's value already in the answer?" inside the scan, so a row that is going to be thrown
away never becomes a row at all.

## 2. Relation to page skipping

It is the same idea as page skipping, applied to one row instead of one page.

- Page skipping: "No value on this page can be a new answer, so do not read the page."
- Level 4: "This row's value cannot be a new answer, so do not build the row."

Example. The seen-set holds AIR and MAIL. One page holds:

```
AIR  MAIL  AIR  AIR  FOB  MAIL  AIR  MAIL  AIR  FOB
```

- Page skipping tests the whole page. FOB is not seen, so the page is read: all 10 rows are built, and the
  tuple filter above the scan throws 8 away.
- Level 4 applies the same test row by row inside that page and builds only the 2 FOB rows.

Page skipping is all or nothing. Level 4 handles the page that is almost skippable.

| | Page skipping (levels 2, 3) | Level 4 |
|---|---|---|
| Unit | A whole page, leaf, row group or file | One row |
| Test | Every value in it is already seen | This row's value is already seen |
| How it knows without reading | A summary stored with the page: min and max, or the dictionary | It cannot; there is no summary per row |
| What it must read | Nothing of the page | That one column's value for the row |
| What it saves | Reading the page at all | Building the row and passing it on |

Limit: a page skip avoids the read; level 4 has to look at the one column to decide. The data is still read
from storage and decompressed.

## 3. Where each level asks its question

| Level | Where the question is asked | What has already been paid for the row |
|---|---|---|
| 0 | At the observer, above the join | Read, built, sent through the join |
| 1 | At the tuple filter, just above the scan | Read and built into a full row |
| 2, 3 | Before a whole file, leaf or row group is opened | Nothing, but only when everything in it is seen |
| 4 | Inside the scan, row by row | Only the one column's value was looked at |

## 4. The two storage formats

| | Iceberg (Parquet) | Native columnar |
|---|---|---|
| What the scan looks at | The row's dictionary entry | The row's value in the DISTINCT column |
| How often the seen-set is asked | Once per dictionary entry, again every 1,024 rows while the answer is "not seen" | Once per row |
| Column types | Strings | BIGINT and strings (declared, not nullable) |
| What a dropped row skips | Decoding the string, and becoming an AsterixDB row | Assembling the row's other columns, and becoming an AsterixDB row |
| Page-level step that goes with it | Row group skipped when every dictionary entry is seen (level 3); file skipped by bounds (level 2, BIGINT) | Leaf skipped by min and max (level 2, BIGINT). No level 3: a leaf has no dictionary |

## 5. Why it gave the largest step on Q1 and Q4

The first file of each partition is opened while the seen-set is empty, so no page-level test can skip it.
After a few thousand rows all 7 ship modes are seen, and level 4 then drops the rest of that file row by row.

| Q4 on Iceberg, 6-run medians | Rows leaving the scan | Time |
|---|---|---|
| Level 3 | 8,000,000 | 2.9 s |
| Level 4 | 12,347 | 0.4 s |

On native there is no level 3, so level 4 does all of the work inside the scan (single runs): Q4 27.5 s to
8.6 s, with 59,974,492 of 59,986,052 rows dropped in the reader; Q3 41.7 s to 21.7 s with no leaf skipped.

## 6. Is this something databases do?

Written from memory; no literature search was done for this note.

| Known technique | What it is | How level 4 relates |
|---|---|---|
| Late materialization (C-Store, Vertica) | Apply the test on one column first; fetch the other columns only for rows that pass | What level 4 does inside the scan |
| Predicate pushdown into the scan | The scan evaluates the WHERE condition instead of an operator above it | Same place; AsterixDB does this for WHERE on native columnar, and level 4 on native reuses that hook |
| Evaluating on dictionary codes | Test each dictionary entry once, then filter rows by code without decoding | The Parquet variant of level 4 |
| Runtime (dynamic) filters | A filter built while the query runs is pushed into a scan (a Bloom filter from a join's build side; the current K-th value for Top-K) | Same family: the test is not known when the query starts |

Not seen described, to my knowledge: the filter being the query's own partial DISTINCT answer, growing while the
scan runs, and the scan re-asking it row by row. This is not a novelty claim; it needs a related-work search.

## 7. Where the idea came from

Row 4 of the user's level table: "Code bitmap inside a row group: for row groups not skipped, mark seen
dictionary entries once, then drop rows by their code before decoding values." Before building it I estimated
that it would gain little on Q1 and Q4; that estimate was wrong. Implementation choices made while building it:

- The identity of Parquet's dictionary entry object stands in for the numeric code, which Iceberg's row reader
  does not expose.
- An entry that is "not seen" is asked about again every 1,024 rows. This is what makes level 4 act on files
  opened before anything is seen.
- On native, the existing row filter hook of the columnar reader is used.

## 8. Is the gain special to AsterixDB?

Reasoning, not measured against another system.

- AsterixDB reads columns, assembles each row into a full record, copies it into a frame and passes it operator
  by operator. A row dropped by the tuple filter has paid for all of that; level 4 avoids the whole chain.
- A vectorized engine keeps data as column batches above the scan and marks dropped rows in a selection list
  without building rows, so a filter just above the scan is already cheap there. Pushing the test into the scan
  would still save decoding other columns, but the step would be smaller.

Fair claim: row-level filtering inside the scan is a known idea; what Baleen adds is feeding it with a runtime
filter that keeps tightening during the scan.

## 9. Does it apply to the earlier filters?

Yes in principle; only the test changes. None of the earlier filters does it today: they have the page skip
and the tuple filter above the scan.

| Filter | Row test inside the scan | State |
|---|---|---|
| DISTINCT | Value already seen: drop | Built (Iceberg strings; native BIGINT and strings) |
| MAX | Value below the current maximum: drop | Not built |
| MIN | Value above the current minimum: drop | Not built |
| Top-K | Value cannot beat the current K-th value: drop | Not built |
| GROUP BY MAX/MIN | Value cannot beat the current bound of the row's group: drop (two columns per row) | Not built |

Where it would pay for MAX: when pages cannot be skipped. On a column that is not clustered every page spans a
wide range, so the page skip does little, yet nearly every row is below the threshold. That is the situation of
Q3 on native: 0 of 37,100 leaves skipped, 55.7 million rows dropped in the reader, 41.7 s to 21.7 s (single
runs). Where the data is clustered and the page skip already removes most pages, little is left for it.

Expected work: small on native (one evaluator comparing the value with the threshold, plus the wiring in the
rule); on Iceberg a reader like level 4's, simpler because a numeric comparison needs no dictionary.

Not scheduled. The user has not said whether to put it on the to-do list.

## Stated but not verified

- Section 6 is from memory.
- Section 8 is reasoning; no other engine was measured.
- The gain for MAX, MIN, Top-K and GROUP BY in section 9 is an expectation from the DISTINCT numbers.
