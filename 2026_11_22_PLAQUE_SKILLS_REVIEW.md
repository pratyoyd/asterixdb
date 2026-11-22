# Review of the skills and the reviewer agent, R1a (2026-11-22)

Two reviews, given in chat: one after the R1a benchmark (morning), one after the scan flush (afternoon). No
skill was edited (user: "do not edit the skills without asking"). Everything below is a proposal.

## Morning review (after the R1a benchmark)

**hld**
- Helped: the ten questions, and talking through the key decisions first, which brought out D1-D8 and caught
  the DISTINCT narrowing.
- Unclear: it pushes for a long document; the user found it too complicated. Question 1 asks when the
  information arrives, but not whether the new piece itself changes how fast it arrives - exactly what was
  missed in R1a.
- Proposed: add "does the new piece change how often state is updated (fewer rows, fewer frames)?" to
  question 1. Require a plain summary of at most 5 lines up front, saying how the new piece relates to what
  already runs.

**implement**
- Helped: the table separating real limits from shortcuts; "fallbacks must be visible" gave the Iceberg log
  line for strings; the correctness check before the benchmark caught the compiler hang.
- Unclear: how a general go-ahead ("you do what needs to be done") relates to "report reviewer findings before
  fixing".
- Proposed: a step to check the plan on the running system for every query shape, with and without a WHERE,
  before the correctness run.

**karpathy-guidelines**
- Helped: kept the diffs small.
- Unclear: "simplicity first" pulls against implement's "build the general case".
- Proposed: one line saying a narrowing is not a simplification.

**code-reviewer agent**
- Helped: found B1 (wrong result) and D-b (measurement confound).
- Missed: timing and feedback effects (so did I).
- Proposed: add "does the change alter when shared state is updated or read?" to its checklist.

**benchmark**
- Helped: append-only CSV and counters from the log made the slowdown visible; the summary's exit check.
- Unclear: the skill says to put `plan.json` in the output folder, but the runner fails when it is there.
- Proposed: fix the text or the script; an optional second baseline in the summary, so the speedup over
  another configuration is computed too.

**interpret-results**
- Helped: keeping measured, explained and guessed apart; asking before any new run.
- Proposed: check the design's own "the design is wrong if" criteria one by one.

## Afternoon review (after the scan flush)

**benchmark**
- Worked: every run recorded, including the mistaken flush-on re-check, which was kept, reported and re-run
  correctly; the detached runner survived the session's context reset.
- Failed: "defaults that bite" - the morning's configuration name did not set the new setting, so after the new
  default it meant something else. The skill warns about a setting's default, not about a new setting changing
  what an old configuration name means.
- Gap: the old-queries plan had only one configuration, so it was its own baseline; `bench_summary.py`
  reported "no differing runs" and 1.00x, which proves nothing. The comparison with the morning's Baleen off was
  done by a separate script.
- Gap: H4 and H5 could not show flush harm (level 4 drops nothing on a column that is not
  dictionary-encoded). Nothing in the skill makes me state, per query before running, what it can show.

**interpret-results**
- Worked: the H2 claim stays honest (no gain shown by the counter; cost cause labelled a guess).
- Failed: labelled as a guess something basic reasoning settles (`l_partkey`, about 2 M distinct values, is not
  dictionary-encoded). The user pointed this out. The skill rewards hedging over reasoning from known facts.

**implement**
- Worked: the visible fallback rule - the "early sends" counter showed H2's cost and the flush firing uselessly
  on H4/H5.
- Failed: the code-reviewer was not run on the scan-flush change, although the skill puts review before the
  benchmark. The skill does not say that amendments to an agreed design go through the same gate.

**karpathy-guidelines**: no problems; the change stayed small.

## Proposed skill edits (afternoon)

1. **benchmark:** per query, before running, state what it can show and why; drop it or ask if nothing. Every
   configuration sets every setting explicitly, including newly added ones. `bench_summary.py` takes an
   external baseline and warns when there is no real one.
2. **interpret-results:** before labelling a cause a guess, check whether known facts (schema, cardinality,
   format defaults) already settle it; a guess that basic reasoning settles counts as an error, not caution.
3. **implement:** review by the code-reviewer applies to amendments too, not only the first build.

## Proposed LLD skill format

1. **Header.** Links to the HLD and its decisions, with dates.
2. **HLD piece to component map.** For each HLD piece: the classes, files and operators touched.
3. **Interfaces.** New or changed signatures, and who calls them.
4. **State.** Layout; who creates, updates and reads it; which threads; when it is reset or dropped; how often
   it is updated.
5. **Plan rules.** Which rules change, and their order relative to the other rules on the same operators.
6. **Per storage format and read path.** What is attached where.
7. **Fallbacks.** Each with its visible signal: a counter, a log line or the plan output.
8. **Settings.** Names and defaults, each default checked in the code.
9. **Tests.** Mapped to the HLD's correctness rules and limits.
10. **Hazards.** Build, run and storage hazards.
11. **Deviations log.** Dated amendments.
12. **Stated but not verified.**

Plus one rule: any narrowing found while writing the LLD goes back to the user before the code is written.
