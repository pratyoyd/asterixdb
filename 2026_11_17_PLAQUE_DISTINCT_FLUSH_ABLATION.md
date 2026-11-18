# PLAQUE DISTINCT filter: flush ablation (2026-11-17)

This note records the full ablation of the three early-sending switches of the DISTINCT filter and the choice of default that follows from it. It closes the flush investigation.

## 1. What was measured

The setting `compiler.plaque.distinct.flush` is a string of letters. Each letter turns on one place where a partly filled frame is sent on instead of waiting until it is full:

- `f`: the filter above the scan sends its output after every input frame.
- `j`: the hash join sends its probe output after every probe input frame.
- `o`: the observer passes on the early sends it receives.

Matrix (identical for every query): Baleen off, plus seen-set {hash, flat, roaring} x flush {none, `f`, `j`, `fj`, `jo`, `fjo`}, all at level 2. That is 19 configurations per query, 6 runs each, taken round-robin within a query, with one discarded warm-up run (roaring, `fj`) per query. 342 measured runs in total. Table: `lineitem` (Iceberg, 30 files), dataverse `baleen_ice`, 4 node controllers in one JVM.

Queries, in full:

```
Q1: SELECT DISTINCT l.l_shipmode FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
Q2: SELECT DISTINCT l.l_suppkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
Q3: SELECT DISTINCT l.l_partkey FROM lineitem l, orders o WHERE l.l_orderkey = o.o_orderkey AND o.o_orderstatus = 'F';
```

Settings prefix for a Baleen run: `SET `compiler.plaque.enabled` "true"; SET `compiler.plaque.distinct.set` "<hash|flat|roaring>"; SET `compiler.plaque.distinct.level` "2"; SET `compiler.plaque.distinct.flush` "<letters>";`. Baleen off: `SET `compiler.plaque.enabled` "false";`.

Limits built into the code that affect these numbers:

- Hash seen-set: capped at 1,000,000 values; it can skip a file only when the file's lower and upper bound are equal.
- Flat seen-set: value range at most 16,000,000.
- File skip (level 2): BIGINT column, null count 0, every value between the file's bounds already seen.
- Level 2 only. Levels 1 and 3 were not part of this ablation.
- Counters in the tables come from the run with the upper-median time of each configuration.

Every one of the 342 runs returned the same result as the first Baleen-off run of its query.

## 2. Median times (seconds, 6 runs)

Baleen off: Q1 212.8, Q2 160.9, Q3 143.0.

### Q1 l_shipmode
| Flush | Hash | Flat | Roaring |
|---|---|---|---|
| none | 36.3 | 35.6 | 35.7 |
| `f` | 35.8 | 36.5 | 36.1 |
| `j` | 35.2 | 35.4 | 34.6 |
| `fj` | 35.8 | 36.0 | 34.7 |
| `jo` | 35.7 | 34.9 | 34.9 |
| `fjo` | 34.6 | 35.1 | 35.8 |

### Q2 l_suppkey
| Flush | Hash | Flat | Roaring |
|---|---|---|---|
| none | 43.2 | 29.2 | 20.1 |
| `f` | 42.3 | 29.7 | 16.5 |
| `j` | 41.7 | 17.1 | 15.5 |
| `fj` | 40.5 | 12.6 | 12.2 |
| `jo` | 42.0 | 26.1 | 22.6 |
| `fjo` | 42.2 | 12.6 | 12.8 |

### Q3 l_partkey
| Flush | Hash | Flat | Roaring |
|---|---|---|---|
| none | 104.2 | 41.3 | 42.8 |
| `f` | 121.9 | 43.5 | 45.3 |
| `j` | 104.9 | 41.6 | 43.3 |
| `fj` | 120.2 | 43.1 | 45.1 |
| `jo` | 101.4 | 43.8 | 43.4 |
| `fjo` | 116.4 | 46.9 | 48.1 |

## 3. Files skipped in each of the 6 runs (out of 30)

### Q1 l_shipmode
| Flush | Hash | Flat | Roaring |
|---|---|---|---|
| none | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 0 0 0 0 0 0 |
| `f` | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 0 0 0 0 0 0 |
| `j` | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 0 0 0 0 0 0 |
| `fj` | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 0 0 0 0 0 0 |
| `jo` | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 0 0 0 0 0 0 |
| `fjo` | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 0 0 0 0 0 0 |

### Q2 l_suppkey
| Flush | Hash | Flat | Roaring |
|---|---|---|---|
| none | 0 0 0 0 0 0 | 18 4 6 0 0 14 | 22 2 14 19 16 18 |
| `f` | 0 0 0 0 0 0 | 0 6 14 0 2 10 | 22 22 22 26 0 0 |
| `j` | 0 0 0 0 0 0 | 3 18 22 22 17 22 | 10 23 23 26 26 25 |
| `fj` | 0 0 0 0 0 0 | 26 26 26 26 26 26 | 26 26 26 26 26 26 |
| `jo` | 0 0 0 0 0 0 | 22 22 1 14 1 1 | 26 1 2 22 2 22 |
| `fjo` | 0 0 0 0 0 0 | 26 26 26 26 26 26 | 26 26 26 26 26 26 |

### Q3 l_partkey
| Flush | Hash | Flat | Roaring |
|---|---|---|---|
| none | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 0 0 0 0 0 0 |
| `f` | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 0 0 0 0 0 0 |
| `j` | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 0 0 0 0 0 0 |
| `fj` | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 0 0 0 0 0 0 |
| `jo` | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 0 0 0 0 0 0 |
| `fjo` | 0 0 0 0 0 0 | 0 0 0 0 0 0 | 0 0 0 0 0 0 |

## 4. Full summary table (all runs, speedups, counters)

| Query | Variant | Flush | Runs (s) | Median (s) | Speedup | Result = off | Files skipped per run | Files skipped / total | Rows out of scan | Dropped by tuple filter | Filter frames out | Filter early sends | Join probe frames in | Rows into observer | Dropped by observer | Observer frames in | Early sends reaching observer | Observer frames out | Observer early sends |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Q1 l_shipmode, join | Baleen off |  | 212.6 215.1 215.1 210.7 211.6 213.0 | 212.8 | 1.00x | yes |  | ||||||||||||
| Q1 l_shipmode, join | hash, level 2 | "" | 37.6 36.5 36.0 35.3 36.3 36.3 | 36.3 | 5.86x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59948843 | 28 | 0 | 37 | 17859 | 17852 | 9 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "f" | 36.1 34.9 35.4 34.9 37.6 36.1 | 35.8 | 5.95x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59966841 | 16 | 16 | 64 | 9240 | 9233 | 6 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "j" | 35.3 35.4 34.9 37.0 35.2 34.1 | 35.2 | 6.04x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59964442 | 14 | 0 | 29 | 10367 | 10360 | 32 | 32 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "fj" | 35.2 34.9 36.4 33.8 41.8 40.2 | 35.8 | 5.94x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59983650 | 2 | 2 | 8 | 1120 | 1113 | 12 | 12 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "jo" | 36.8 34.7 35.4 34.8 36.8 36.0 | 35.7 | 5.96x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59966843 | 12 | 0 | 24 | 9230 | 9223 | 28 | 28 | 28 | 1 |
| Q1 l_shipmode, join | hash, level 2 | "fjo" | 34.4 34.6 35.2 34.4 34.7 35.9 | 34.6 | 6.14x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59984850 | 1 | 1 | 4 | 582 | 575 | 8 | 8 | 8 | 1 |
| Q1 l_shipmode, join | flat, level 2 | "" | 35.1 35.5 33.9 38.5 35.6 36.5 | 35.6 | 5.99x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59953636 | 26 | 0 | 36 | 15698 | 15691 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "f" | 36.7 36.6 35.3 34.2 36.4 37.9 | 36.5 | 5.83x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59965642 | 17 | 17 | 68 | 9813 | 9806 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "j" | 37.7 35.3 38.2 34.7 35.4 35.0 | 35.4 | 6.02x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59963783 | 15 | 0 | 30 | 10737 | 10730 | 33 | 33 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "fj" | 38.5 35.9 36.2 35.4 36.1 35.2 | 36.0 | 5.92x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59983864 | 2 | 2 | 8 | 1057 | 1050 | 12 | 12 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "jo" | 34.9 36.6 35.9 34.7 34.8 34.5 | 34.9 | 6.10x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59964444 | 14 | 0 | 28 | 10405 | 10398 | 32 | 32 | 32 | 1 |
| Q1 l_shipmode, join | flat, level 2 | "fjo" | 38.7 34.9 35.6 35.3 33.7 34.3 | 35.1 | 6.06x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59984854 | 1 | 1 | 4 | 574 | 567 | 8 | 8 | 8 | 1 |
| Q1 l_shipmode, join | roaring, level 2 | "" | 37.4 35.3 35.9 35.4 37.1 34.7 | 35.7 | 5.97x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59953645 | 24 | 0 | 37 | 15645 | 15638 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "f" | 36.0 36.2 35.3 40.8 37.7 35.5 | 36.1 | 5.89x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59964447 | 18 | 18 | 72 | 10380 | 10373 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "j" | 34.4 35.7 34.5 34.7 36.9 33.8 | 34.6 | 6.15x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59964749 | 14 | 0 | 24 | 10191 | 10184 | 28 | 28 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "fj" | 34.3 35.6 33.9 34.9 37.9 34.5 | 34.7 | 6.13x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59983652 | 2 | 2 | 8 | 1156 | 1149 | 12 | 12 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "jo" | 35.7 38.1 33.9 35.4 33.9 34.4 | 34.9 | 6.10x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59968048 | 11 | 0 | 22 | 8615 | 8608 | 26 | 26 | 26 | 1 |
| Q1 l_shipmode, join | roaring, level 2 | "fjo" | 35.9 36.1 35.0 34.4 35.6 36.6 | 35.8 | 5.95x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59984850 | 1 | 1 | 4 | 582 | 575 | 8 | 8 | 8 | 1 |
| Q2 l_suppkey, join | Baleen off |  | 201.7 161.7 164.5 160.1 158.8 159.4 | 160.9 | 1.00x | yes |  | ||||||||||||
| Q2 l_suppkey, join | hash, level 2 | "" | 42.8 42.8 42.1 43.5 43.8 44.8 | 43.2 | 3.73x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59666208 | 291 | 0 | 300 | 154430 | 54430 | 82 | 0 | 51 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "f" | 43.3 40.6 42.3 42.2 42.2 43.8 | 42.3 | 3.81x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59710445 | 9509 | 9509 | 38036 | 133218 | 33218 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "j" | 43.0 41.7 43.4 41.3 41.8 41.5 | 41.7 | 3.85x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59718137 | 244 | 0 | 255 | 129455 | 29455 | 259 | 259 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "fj" | 41.6 40.4 40.2 40.0 42.4 40.7 | 40.5 | 3.97x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59776910 | 1434 | 1434 | 5736 | 101159 | 1159 | 4113 | 4113 | 49 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "jo" | 42.1 40.6 42.3 41.3 41.8 43.1 | 42.0 | 3.83x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59699697 | 261 | 0 | 271 | 138250 | 38250 | 275 | 275 | 275 | 255 |
| Q2 l_suppkey, join | hash, level 2 | "fjo" | 41.3 41.7 42.3 42.8 42.1 44.7 | 42.2 | 3.81x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 59771405 | 1429 | 1429 | 5716 | 103854 | 3854 | 4113 | 4113 | 4113 | 4103 |
| Q2 l_suppkey, join | flat, level 2 | "" | 19.0 31.2 27.3 31.7 32.8 20.8 | 29.2 | 5.50x | yes | 18 4 6 0 0 14 | 4 / 30 | 52000000 | 51678775 | 292 | 0 | 302 | 155238 | 55238 | 83 | 0 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "f" | 35.7 29.0 21.5 34.2 30.4 24.8 | 29.7 | 5.42x | yes | 0 6 14 0 2 10 | 2 / 30 | 56000000 | 55724404 | 15879 | 15879 | 63516 | 133162 | 33162 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "j" | 31.7 18.3 15.4 16.0 22.8 15.6 | 17.1 | 9.41x | yes | 3 18 22 22 17 22 | 18 / 30 | 24000000 | 23735040 | 241 | 0 | 252 | 127927 | 27927 | 256 | 256 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "fj" | 12.5 12.6 12.6 12.6 12.8 12.7 | 12.6 | 12.80x | yes | 26 26 26 26 26 26 | 26 / 30 | 8000000 | 7789388 | 1436 | 1436 | 5744 | 101832 | 1832 | 4111 | 4111 | 51 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "jo" | 15.0 15.1 31.8 20.7 31.8 31.5 | 26.1 | 6.16x | yes | 22 22 1 14 1 1 | 1 / 30 | 58000000 | 57713031 | 261 | 0 | 269 | 138549 | 38549 | 273 | 273 | 273 | 253 |
| Q2 l_suppkey, join | flat, level 2 | "fjo" | 12.7 12.0 12.7 12.5 12.4 12.8 | 12.6 | 12.78x | yes | 26 26 26 26 26 26 | 26 / 30 | 8000000 | 7783293 | 1426 | 1426 | 5704 | 104861 | 4861 | 4128 | 4128 | 4128 | 4106 |
| Q2 l_suppkey, join | roaring, level 2 | "" | 15.3 31.8 23.6 18.8 21.2 19.0 | 20.1 | 8.01x | yes | 22 2 14 19 16 18 | 16 / 30 | 28000000 | 27671229 | 300 | 0 | 310 | 158902 | 58902 | 84 | 0 | 49 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "f" | 15.8 16.1 16.8 12.5 37.6 32.7 | 16.5 | 9.76x | yes | 22 22 22 26 0 0 | 22 / 30 | 16000000 | 15721116 | 5535 | 5535 | 22140 | 134836 | 34836 | 72 | 0 | 49 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "j" | 25.3 15.8 15.6 11.9 13.7 15.4 | 15.5 | 10.38x | yes | 10 23 23 26 26 25 | 23 / 30 | 14000000 | 13725214 | 250 | 0 | 259 | 132842 | 32842 | 263 | 263 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "fj" | 12.1 13.1 11.9 11.9 12.3 12.6 | 12.2 | 13.15x | yes | 26 26 26 26 26 26 | 26 / 30 | 8000000 | 7782557 | 1450 | 1450 | 5800 | 105096 | 5096 | 4226 | 4226 | 51 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "jo" | 12.4 33.6 30.6 15.0 30.2 15.1 | 22.6 | 7.11x | yes | 26 1 2 22 2 22 | 2 / 30 | 56000000 | 55690202 | 282 | 0 | 291 | 149807 | 49807 | 295 | 295 | 295 | 275 |
| Q2 l_suppkey, join | roaring, level 2 | "fjo" | 12.9 12.8 12.6 12.9 12.7 12.9 | 12.8 | 12.52x | yes | 26 26 26 26 26 26 | 26 / 30 | 8000000 | 7779824 | 1453 | 1453 | 5812 | 106519 | 6519 | 4222 | 4222 | 4222 | 4106 |
| Q3 l_partkey, join | Baleen off |  | 127.9 143.1 141.6 142.9 146.4 146.0 | 143.0 | 1.00x | yes |  | ||||||||||||
| Q3 l_partkey, join | hash, level 2 | "" | 103.5 102.8 102.5 108.3 106.6 104.9 | 104.2 | 1.37x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 29302364 | 28123 | 0 | 28132 | 14859989 | 16997 | 7714 | 0 | 7700 | 0 |
| Q3 l_partkey, join | hash, level 2 | "f" | 117.2 120.5 122.9 122.3 121.5 125.5 | 121.9 | 1.17x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 29324370 | 54985 | 54985 | 219940 | 14849784 | 5846 | 7708 | 0 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "j" | 105.8 104.7 99.6 106.7 100.2 105.1 | 104.9 | 1.36x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 29313730 | 28113 | 0 | 28121 | 14854232 | 11091 | 28125 | 28125 | 7700 | 0 |
| Q3 l_partkey, join | hash, level 2 | "fj" | 119.6 126.0 119.0 124.8 119.4 120.7 | 120.2 | 1.19x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 29333603 | 54985 | 54985 | 219940 | 14844629 | 1292 | 219944 | 219944 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "jo" | 94.7 104.3 102.8 98.6 102.1 100.8 | 101.4 | 1.41x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 29310208 | 28116 | 0 | 28124 | 14856336 | 12910 | 28128 | 28128 | 28128 | 28124 |
| Q3 l_partkey, join | hash, level 2 | "fjo" | 118.0 114.7 111.0 120.4 112.6 119.5 | 116.4 | 1.23x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 29331333 | 54985 | 54985 | 219940 | 14845361 | 2585 | 219944 | 219944 | 219944 | 219940 |
| Q3 l_partkey, join | flat, level 2 | "" | 39.9 43.4 41.2 40.7 41.5 41.6 | 41.3 | 3.46x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 55664163 | 3959 | 0 | 3969 | 2090553 | 90555 | 1087 | 0 | 1035 | 0 |
| Q3 l_partkey, join | flat, level 2 | "f" | 42.7 46.8 44.3 42.6 42.7 45.9 | 43.5 | 3.29x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 55763088 | 40198 | 40198 | 160792 | 2042576 | 42578 | 1062 | 0 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "j" | 41.7 41.1 41.6 41.4 42.0 44.8 | 41.6 | 3.44x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 55726692 | 3902 | 0 | 3911 | 2060219 | 60221 | 3915 | 3915 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "fj" | 43.0 43.1 43.2 42.8 46.1 43.7 | 43.1 | 3.32x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 55846994 | 28588 | 28588 | 114352 | 2001869 | 1871 | 82397 | 82397 | 1035 | 0 |
| Q3 l_partkey, join | flat, level 2 | "jo" | 40.3 45.9 44.6 42.9 45.6 40.7 | 43.8 | 3.27x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 55708124 | 3919 | 0 | 3928 | 2069497 | 69499 | 3932 | 3932 | 3932 | 3924 |
| Q3 l_partkey, join | flat, level 2 | "fjo" | 46.4 47.2 47.2 46.7 49.5 46.5 | 46.9 | 3.05x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 55839292 | 28563 | 28563 | 114252 | 2005693 | 5695 | 82366 | 82366 | 82366 | 82355 |
| Q3 l_partkey, join | roaring, level 2 | "" | 43.5 43.0 42.9 42.5 42.4 42.8 | 42.8 | 3.34x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 55610830 | 4008 | 0 | 4019 | 2116411 | 116413 | 1100 | 0 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "f" | 45.1 46.3 45.0 45.5 44.6 45.8 | 45.3 | 3.15x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 55718911 | 40022 | 40022 | 160088 | 2064124 | 64126 | 1073 | 0 | 1037 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "j" | 42.8 47.1 43.1 42.4 43.6 45.8 | 43.3 | 3.30x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 55680671 | 3944 | 0 | 3954 | 2082478 | 82480 | 3958 | 3958 | 1035 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "fj" | 45.2 43.9 44.2 45.1 45.6 45.0 | 45.1 | 3.17x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 55827074 | 28606 | 28606 | 114424 | 2011452 | 11454 | 82490 | 82490 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "jo" | 42.1 44.0 43.5 43.2 43.6 42.1 | 43.4 | 3.30x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 55666282 | 3957 | 0 | 3967 | 2089424 | 89426 | 3971 | 3971 | 3971 | 3964 |
| Q3 l_partkey, join | roaring, level 2 | "fjo" | 48.5 47.7 49.8 46.7 47.4 49.4 | 48.1 | 2.97x | yes | 0 0 0 0 0 0 | 0 / 30 | 59986052 | 55828827 | 28590 | 28590 | 114360 | 2010756 | 10758 | 82441 | 82441 | 82441 | 82339 |

## 5. What the tables show

- Q2 with flat or roaring: only `fj` and `fjo` skip 26 of 30 files in all six runs. Times are 12.6 s (flat `fj`), 12.2 s (roaring `fj`), 12.6 s (flat `fjo`), 12.8 s (roaring `fjo`) against 160.9 s with Baleen off, which is 12.5x to 13.2x. With any other setting the number of skipped files changes from run to run (0 to 26) and the median is 15.5 s to 29.7 s.
- Q2 needs both `f` and `j`. `f` alone or `j` alone does not make the skip count stable.
- `o` adds nothing on Q2: `fjo` skips the same 26 files as `fj`.
- Q3 (2,000,000 distinct values, no file can be skipped): `fj` costs 1.8 s on flat (41.3 to 43.1), 2.3 s on roaring (42.8 to 45.1) and 16.0 s on hash (104.2 to 120.2) compared with no early sending. `fjo` costs 5.6 s on flat, 5.3 s on roaring and 12.2 s on hash.
- The Q3 cost follows the number of frames: with `f` the filter sends 28,563 to 54,985 frames where it sends 3,902 to 28,123 without, and each becomes one frame to each of the 4 join partitions (114,252 to 219,940 probe frames).
- Q1 (7 distinct values): all 18 Baleen configurations are within 34.6 s to 36.5 s, 5.8x to 6.2x faster than Baleen off (212.8 s). Flush setting makes no difference.
- Hash never skips a file on any of the three queries, at any flush setting.

## 6. Decision

The rule agreed before the run: among the settings where Q2 skips 26 of 30 files in all six runs for both flat and roaring, take the one with the lowest Q3 time. The candidates are `fj` and `fjo`. On Q3, `fj` is faster than `fjo` for flat (43.1 s against 46.9 s) and roaring (45.1 s against 48.1 s); for hash `fjo` is faster (116.4 s against 120.2 s).

Pick: `fj`. It is already the default in the code (`AlgebricksConfig.PLAQUE_DISTINCT_FLUSH_DEFAULT`), so no code change is needed. The `o` switch stays available but off.

With `fj` and the roaring seen-set the three queries run at 34.7 s, 12.2 s and 45.1 s against 212.8 s, 160.9 s and 143.0 s with Baleen off: 6.1x, 13.2x and 3.2x.

## 7. Bugs found

None in this run.

## 8. Decisions I made without asking

- Counters shown per configuration are those of the upper-median run; the per-run table in the appendix has all of them.
- The first Baleen-off run of Q2 (201.7 s) and of Q3 (127.9 s) differ from the other five; they are kept in the medians.

## 9. Stated but not verified

- That the Q3 cost of `f` is caused by the extra frames. The frame counts and the times move together, but no profile was taken.
- `o` alone and `fo` were not run; nothing is claimed about them.

## Appendix: every run

| Query | Variant | Flush | Run | Time (s) | Result = off | Files skipped / total | Rows out of scan | Dropped by tuple filter | Filter frames out | Filter early sends | Join probe frames in | Rows into observer | Dropped by observer | Observer frames in | Early sends reaching observer | Observer frames out | Observer early sends |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Q1 l_shipmode, join | warm-up (roaring, level 2), discarded | "fj" | 0 | 36.2 | | 0 / 30 | 59986052 | 59981251 | 4 | 4 | 16 | 2266 | 2259 | 20 | 20 | 0 | 0 |
| Q1 l_shipmode, join | Baleen off |  | 1 | 212.6 | yes | ||||||||||||
| Q1 l_shipmode, join | hash, level 2 | "" | 1 | 37.6 | yes | 0 / 30 | 59986052 | 59956043 | 21 | 0 | 36 | 14414 | 14407 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "f" | 1 | 36.1 | yes | 0 / 30 | 59986052 | 59966841 | 16 | 16 | 64 | 9240 | 9233 | 6 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "j" | 1 | 35.3 | yes | 0 / 30 | 59986052 | 59964442 | 14 | 0 | 29 | 10367 | 10360 | 32 | 32 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "fj" | 1 | 35.2 | yes | 0 / 30 | 59986052 | 59984851 | 1 | 1 | 4 | 572 | 565 | 8 | 8 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "jo" | 1 | 36.8 | yes | 0 / 30 | 59986052 | 59965639 | 13 | 0 | 26 | 9809 | 9802 | 30 | 30 | 30 | 1 |
| Q1 l_shipmode, join | hash, level 2 | "fjo" | 1 | 34.4 | yes | 0 / 30 | 59986052 | 59982451 | 3 | 3 | 12 | 1728 | 1721 | 16 | 16 | 16 | 2 |
| Q1 l_shipmode, join | flat, level 2 | "" | 1 | 35.1 | yes | 0 / 30 | 59986052 | 59952809 | 25 | 0 | 36 | 15941 | 15934 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "f" | 1 | 36.7 | yes | 0 / 30 | 59986052 | 59964443 | 18 | 18 | 72 | 10401 | 10394 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "j" | 1 | 37.7 | yes | 0 / 30 | 59986052 | 59963803 | 15 | 0 | 30 | 10666 | 10659 | 34 | 34 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "fj" | 1 | 38.5 | yes | 0 / 30 | 59986052 | 59984851 | 1 | 1 | 4 | 572 | 565 | 8 | 8 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "jo" | 1 | 34.9 | yes | 0 / 30 | 59986052 | 59964444 | 14 | 0 | 28 | 10405 | 10398 | 32 | 32 | 32 | 1 |
| Q1 l_shipmode, join | flat, level 2 | "fjo" | 1 | 38.7 | yes | 0 / 30 | 59986052 | 59983652 | 2 | 2 | 8 | 1156 | 1149 | 12 | 12 | 12 | 1 |
| Q1 l_shipmode, join | roaring, level 2 | "" | 1 | 37.4 | yes | 0 / 30 | 59986052 | 59954840 | 22 | 0 | 36 | 14949 | 14942 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "f" | 1 | 36.0 | yes | 0 / 30 | 59986052 | 59965650 | 17 | 17 | 68 | 9691 | 9684 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "j" | 1 | 34.4 | yes | 0 / 30 | 59986052 | 59971648 | 9 | 0 | 20 | 6974 | 6967 | 24 | 24 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "fj" | 1 | 34.3 | yes | 0 / 30 | 59986052 | 59984854 | 1 | 1 | 4 | 574 | 567 | 8 | 8 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "jo" | 1 | 35.7 | yes | 0 / 30 | 59986052 | 59974050 | 6 | 0 | 20 | 5844 | 5837 | 24 | 24 | 24 | 1 |
| Q1 l_shipmode, join | roaring, level 2 | "fjo" | 1 | 35.9 | yes | 0 / 30 | 59986052 | 59984850 | 1 | 1 | 4 | 582 | 575 | 8 | 8 | 8 | 1 |
| Q1 l_shipmode, join | Baleen off |  | 2 | 215.1 | yes | ||||||||||||
| Q1 l_shipmode, join | hash, level 2 | "" | 2 | 36.5 | yes | 0 / 30 | 59986052 | 59949416 | 27 | 0 | 36 | 17660 | 17653 | 9 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "f" | 2 | 34.9 | yes | 0 / 30 | 59986052 | 59965641 | 17 | 17 | 68 | 9823 | 9816 | 7 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "j" | 2 | 35.4 | yes | 0 / 30 | 59986052 | 59965641 | 13 | 0 | 28 | 9823 | 9816 | 32 | 32 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "fj" | 2 | 34.9 | yes | 0 / 30 | 59986052 | 59982453 | 3 | 3 | 12 | 1684 | 1677 | 16 | 16 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "jo" | 2 | 34.7 | yes | 0 / 30 | 59986052 | 59966842 | 13 | 0 | 25 | 9210 | 9203 | 28 | 28 | 28 | 1 |
| Q1 l_shipmode, join | hash, level 2 | "fjo" | 2 | 34.6 | yes | 0 / 30 | 59986052 | 59984854 | 1 | 1 | 4 | 574 | 567 | 8 | 8 | 8 | 1 |
| Q1 l_shipmode, join | flat, level 2 | "" | 2 | 35.5 | yes | 0 / 30 | 59986052 | 59946444 | 29 | 0 | 42 | 19020 | 19013 | 12 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "f" | 2 | 36.6 | yes | 0 / 30 | 59986052 | 59965642 | 17 | 17 | 68 | 9813 | 9806 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "j" | 2 | 35.3 | yes | 0 / 30 | 59986052 | 59970864 | 9 | 0 | 20 | 7318 | 7311 | 24 | 24 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "fj" | 2 | 35.9 | yes | 0 / 30 | 59986052 | 59984851 | 1 | 1 | 4 | 572 | 565 | 8 | 8 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "jo" | 2 | 36.6 | yes | 0 / 30 | 59986052 | 59966847 | 13 | 0 | 24 | 9129 | 9122 | 28 | 28 | 28 | 1 |
| Q1 l_shipmode, join | flat, level 2 | "fjo" | 2 | 34.9 | yes | 0 / 30 | 59986052 | 59983652 | 2 | 2 | 8 | 1156 | 1149 | 12 | 12 | 12 | 2 |
| Q1 l_shipmode, join | roaring, level 2 | "" | 2 | 35.3 | yes | 0 / 30 | 59986052 | 59951242 | 26 | 0 | 37 | 16678 | 16671 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "f" | 2 | 36.2 | yes | 0 / 30 | 59986052 | 59964447 | 18 | 18 | 72 | 10380 | 10373 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "j" | 2 | 35.7 | yes | 0 / 30 | 59986052 | 59975250 | 5 | 0 | 20 | 5191 | 5184 | 24 | 24 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "fj" | 2 | 35.6 | yes | 0 / 30 | 59986052 | 59984850 | 1 | 1 | 4 | 582 | 575 | 8 | 8 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "jo" | 2 | 38.1 | yes | 0 / 30 | 59986052 | 59975250 | 6 | 0 | 20 | 5214 | 5207 | 24 | 24 | 24 | 1 |
| Q1 l_shipmode, join | roaring, level 2 | "fjo" | 2 | 36.1 | yes | 0 / 30 | 59986052 | 59982451 | 3 | 3 | 12 | 1728 | 1721 | 16 | 16 | 16 | 1 |
| Q1 l_shipmode, join | Baleen off |  | 3 | 215.1 | yes | ||||||||||||
| Q1 l_shipmode, join | hash, level 2 | "" | 3 | 36.0 | yes | 0 / 30 | 59986052 | 59948827 | 28 | 0 | 40 | 17874 | 17867 | 9 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "f" | 3 | 35.4 | yes | 0 / 30 | 59986052 | 59965647 | 17 | 17 | 68 | 9667 | 9660 | 7 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "j" | 3 | 34.9 | yes | 0 / 30 | 59986052 | 59974044 | 7 | 0 | 20 | 5713 | 5706 | 24 | 24 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "fj" | 3 | 36.4 | yes | 0 / 30 | 59986052 | 59983650 | 2 | 2 | 8 | 1120 | 1113 | 12 | 12 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "jo" | 3 | 35.4 | yes | 0 / 30 | 59986052 | 59970992 | 9 | 0 | 22 | 7164 | 7157 | 26 | 26 | 26 | 1 |
| Q1 l_shipmode, join | hash, level 2 | "fjo" | 3 | 35.2 | yes | 0 / 30 | 59986052 | 59983652 | 2 | 2 | 8 | 1156 | 1149 | 12 | 12 | 12 | 1 |
| Q1 l_shipmode, join | flat, level 2 | "" | 3 | 33.9 | yes | 0 / 30 | 59986052 | 59950044 | 28 | 0 | 38 | 17291 | 17284 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "f" | 3 | 35.3 | yes | 0 / 30 | 59986052 | 59965646 | 17 | 17 | 68 | 9558 | 9551 | 7 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "j" | 3 | 38.2 | yes | 0 / 30 | 59986052 | 59970445 | 10 | 0 | 23 | 7499 | 7492 | 26 | 26 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "fj" | 3 | 36.2 | yes | 0 / 30 | 59986052 | 59984850 | 1 | 1 | 4 | 582 | 575 | 8 | 8 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "jo" | 3 | 35.9 | yes | 0 / 30 | 59986052 | 59964443 | 14 | 0 | 28 | 10336 | 10329 | 32 | 32 | 32 | 1 |
| Q1 l_shipmode, join | flat, level 2 | "fjo" | 3 | 35.6 | yes | 0 / 30 | 59986052 | 59983654 | 2 | 2 | 8 | 1112 | 1105 | 12 | 12 | 12 | 1 |
| Q1 l_shipmode, join | roaring, level 2 | "" | 3 | 35.9 | yes | 0 / 30 | 59986052 | 59953645 | 24 | 0 | 37 | 15645 | 15638 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "f" | 3 | 35.3 | yes | 0 / 30 | 59986052 | 59964450 | 18 | 18 | 72 | 10495 | 10488 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "j" | 3 | 34.5 | yes | 0 / 30 | 59986052 | 59968048 | 12 | 0 | 26 | 8682 | 8675 | 30 | 30 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "fj" | 3 | 33.9 | yes | 0 / 30 | 59986052 | 59982451 | 3 | 3 | 12 | 1728 | 1721 | 16 | 16 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "jo" | 3 | 33.9 | yes | 0 / 30 | 59986052 | 59964442 | 14 | 0 | 29 | 10367 | 10360 | 32 | 32 | 32 | 1 |
| Q1 l_shipmode, join | roaring, level 2 | "fjo" | 3 | 35.0 | yes | 0 / 30 | 59986052 | 59984851 | 1 | 1 | 4 | 572 | 565 | 8 | 8 | 8 | 2 |
| Q1 l_shipmode, join | Baleen off |  | 4 | 210.7 | yes | ||||||||||||
| Q1 l_shipmode, join | hash, level 2 | "" | 4 | 35.3 | yes | 0 / 30 | 59986052 | 59948850 | 27 | 0 | 39 | 17985 | 17978 | 9 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "f" | 4 | 34.9 | yes | 0 / 30 | 59986052 | 59965641 | 17 | 17 | 68 | 9763 | 9756 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "j" | 4 | 37.0 | yes | 0 / 30 | 59986052 | 59963244 | 15 | 0 | 30 | 10934 | 10927 | 34 | 34 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "fj" | 4 | 33.8 | yes | 0 / 30 | 59986052 | 59984850 | 1 | 1 | 4 | 582 | 575 | 8 | 8 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "jo" | 4 | 34.8 | yes | 0 / 30 | 59986052 | 59968041 | 12 | 0 | 26 | 8637 | 8630 | 30 | 30 | 30 | 1 |
| Q1 l_shipmode, join | hash, level 2 | "fjo" | 4 | 34.4 | yes | 0 / 30 | 59986052 | 59984852 | 1 | 1 | 4 | 538 | 531 | 8 | 8 | 8 | 1 |
| Q1 l_shipmode, join | flat, level 2 | "" | 4 | 38.5 | yes | 0 / 30 | 59986052 | 59954843 | 22 | 0 | 36 | 14897 | 14890 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "f" | 4 | 34.2 | yes | 0 / 30 | 59986052 | 59964444 | 18 | 18 | 72 | 10405 | 10398 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "j" | 4 | 34.7 | yes | 0 / 30 | 59986052 | 59965646 | 13 | 0 | 26 | 9753 | 9746 | 30 | 30 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "fj" | 4 | 35.4 | yes | 0 / 30 | 59986052 | 59982451 | 3 | 3 | 12 | 1728 | 1721 | 16 | 16 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "jo" | 4 | 34.7 | yes | 0 / 30 | 59986052 | 59971704 | 8 | 0 | 20 | 6924 | 6917 | 24 | 24 | 24 | 1 |
| Q1 l_shipmode, join | flat, level 2 | "fjo" | 4 | 35.3 | yes | 0 / 30 | 59986052 | 59984854 | 1 | 1 | 4 | 574 | 567 | 8 | 8 | 8 | 1 |
| Q1 l_shipmode, join | roaring, level 2 | "" | 4 | 35.4 | yes | 0 / 30 | 59986052 | 59952442 | 24 | 0 | 38 | 16072 | 16065 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "f" | 4 | 40.8 | yes | 0 / 30 | 59986052 | 59966192 | 17 | 17 | 68 | 9483 | 9476 | 6 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "j" | 4 | 34.7 | yes | 0 / 30 | 59986052 | 59964749 | 14 | 0 | 24 | 10191 | 10184 | 28 | 28 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "fj" | 4 | 34.9 | yes | 0 / 30 | 59986052 | 59983652 | 2 | 2 | 8 | 1156 | 1149 | 12 | 12 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "jo" | 4 | 35.4 | yes | 0 / 30 | 59986052 | 59968048 | 11 | 0 | 22 | 8615 | 8608 | 26 | 26 | 26 | 1 |
| Q1 l_shipmode, join | roaring, level 2 | "fjo" | 4 | 34.4 | yes | 0 / 30 | 59986052 | 59983651 | 2 | 2 | 8 | 1110 | 1103 | 12 | 12 | 12 | 1 |
| Q1 l_shipmode, join | Baleen off |  | 5 | 211.6 | yes | ||||||||||||
| Q1 l_shipmode, join | hash, level 2 | "" | 5 | 36.3 | yes | 0 / 30 | 59986052 | 59950043 | 27 | 0 | 40 | 17288 | 17281 | 9 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "f" | 5 | 37.6 | yes | 0 / 30 | 59986052 | 59965651 | 17 | 17 | 68 | 9800 | 9793 | 7 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "j" | 5 | 35.2 | yes | 0 / 30 | 59986052 | 59964445 | 14 | 0 | 28 | 10386 | 10379 | 32 | 32 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "fj" | 5 | 41.8 | yes | 0 / 30 | 59986052 | 59984852 | 1 | 1 | 4 | 538 | 531 | 8 | 8 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "jo" | 5 | 36.8 | yes | 0 / 30 | 59986052 | 59966847 | 13 | 0 | 24 | 9210 | 9203 | 28 | 28 | 28 | 1 |
| Q1 l_shipmode, join | hash, level 2 | "fjo" | 5 | 34.7 | yes | 0 / 30 | 59986052 | 59984850 | 1 | 1 | 4 | 582 | 575 | 8 | 8 | 8 | 1 |
| Q1 l_shipmode, join | flat, level 2 | "" | 5 | 35.6 | yes | 0 / 30 | 59986052 | 59953636 | 26 | 0 | 36 | 15698 | 15691 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "f" | 5 | 36.4 | yes | 0 / 30 | 59986052 | 59964452 | 18 | 18 | 72 | 10482 | 10475 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "j" | 5 | 35.4 | yes | 0 / 30 | 59986052 | 59963783 | 15 | 0 | 30 | 10737 | 10730 | 33 | 33 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "fj" | 5 | 36.1 | yes | 0 / 30 | 59986052 | 59983864 | 2 | 2 | 8 | 1057 | 1050 | 12 | 12 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "jo" | 5 | 34.8 | yes | 0 / 30 | 59986052 | 59965647 | 13 | 0 | 28 | 9852 | 9845 | 32 | 32 | 32 | 1 |
| Q1 l_shipmode, join | flat, level 2 | "fjo" | 5 | 33.7 | yes | 0 / 30 | 59986052 | 59983649 | 2 | 2 | 8 | 1154 | 1147 | 12 | 12 | 12 | 1 |
| Q1 l_shipmode, join | roaring, level 2 | "" | 5 | 37.1 | yes | 0 / 30 | 59986052 | 59950042 | 26 | 0 | 38 | 17347 | 17340 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "f" | 5 | 37.7 | yes | 0 / 30 | 59986052 | 59966678 | 17 | 17 | 68 | 9239 | 9232 | 6 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "j" | 5 | 36.9 | yes | 0 / 30 | 59986052 | 59971643 | 9 | 0 | 24 | 6949 | 6942 | 28 | 28 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "fj" | 5 | 37.9 | yes | 0 / 30 | 59986052 | 59984851 | 1 | 1 | 4 | 572 | 565 | 8 | 8 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "jo" | 5 | 33.9 | yes | 0 / 30 | 59986052 | 59962400 | 16 | 0 | 30 | 11386 | 11379 | 34 | 34 | 34 | 1 |
| Q1 l_shipmode, join | roaring, level 2 | "fjo" | 5 | 35.6 | yes | 0 / 30 | 59986052 | 59984852 | 1 | 1 | 4 | 538 | 531 | 8 | 8 | 8 | 1 |
| Q1 l_shipmode, join | Baleen off |  | 6 | 213.0 | yes | ||||||||||||
| Q1 l_shipmode, join | hash, level 2 | "" | 6 | 36.3 | yes | 0 / 30 | 59986052 | 59948843 | 28 | 0 | 37 | 17859 | 17852 | 9 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "f" | 6 | 36.1 | yes | 0 / 30 | 59986052 | 59965641 | 17 | 17 | 68 | 9769 | 9762 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "j" | 6 | 34.1 | yes | 0 / 30 | 59986052 | 59963244 | 15 | 0 | 30 | 10999 | 10992 | 34 | 34 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "fj" | 6 | 40.2 | yes | 0 / 30 | 59986052 | 59984850 | 1 | 1 | 4 | 582 | 575 | 8 | 8 | 0 | 0 |
| Q1 l_shipmode, join | hash, level 2 | "jo" | 6 | 36.0 | yes | 0 / 30 | 59986052 | 59966843 | 12 | 0 | 24 | 9230 | 9223 | 28 | 28 | 28 | 1 |
| Q1 l_shipmode, join | hash, level 2 | "fjo" | 6 | 35.9 | yes | 0 / 30 | 59986052 | 59984851 | 1 | 1 | 4 | 572 | 565 | 8 | 8 | 8 | 2 |
| Q1 l_shipmode, join | flat, level 2 | "" | 6 | 36.5 | yes | 0 / 30 | 59986052 | 59950162 | 27 | 0 | 38 | 17232 | 17225 | 9 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "f" | 6 | 37.9 | yes | 0 / 30 | 59986052 | 59965650 | 17 | 17 | 68 | 9740 | 9733 | 7 | 0 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "j" | 6 | 35.0 | yes | 0 / 30 | 59986052 | 59965643 | 13 | 0 | 27 | 9738 | 9731 | 30 | 30 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "fj" | 6 | 35.2 | yes | 0 / 30 | 59986052 | 59983650 | 2 | 2 | 8 | 1120 | 1113 | 12 | 12 | 0 | 0 |
| Q1 l_shipmode, join | flat, level 2 | "jo" | 6 | 34.5 | yes | 0 / 30 | 59986052 | 59969251 | 12 | 0 | 24 | 8095 | 8088 | 28 | 28 | 28 | 1 |
| Q1 l_shipmode, join | flat, level 2 | "fjo" | 6 | 34.3 | yes | 0 / 30 | 59986052 | 59982451 | 3 | 3 | 12 | 1728 | 1721 | 16 | 16 | 16 | 1 |
| Q1 l_shipmode, join | roaring, level 2 | "" | 6 | 34.7 | yes | 0 / 30 | 59986052 | 59951593 | 26 | 0 | 38 | 16668 | 16661 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "f" | 6 | 35.5 | yes | 0 / 30 | 59986052 | 59964440 | 18 | 18 | 72 | 10407 | 10400 | 8 | 0 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "j" | 6 | 33.8 | yes | 0 / 30 | 59986052 | 59978851 | 4 | 0 | 12 | 3590 | 3583 | 16 | 16 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "fj" | 6 | 34.5 | yes | 0 / 30 | 59986052 | 59982449 | 3 | 3 | 12 | 1692 | 1685 | 16 | 16 | 0 | 0 |
| Q1 l_shipmode, join | roaring, level 2 | "jo" | 6 | 34.4 | yes | 0 / 30 | 59986052 | 59976445 | 4 | 0 | 20 | 4514 | 4507 | 24 | 24 | 24 | 1 |
| Q1 l_shipmode, join | roaring, level 2 | "fjo" | 6 | 36.6 | yes | 0 / 30 | 59986052 | 59984851 | 1 | 1 | 4 | 572 | 565 | 8 | 8 | 8 | 1 |
| Q2 l_suppkey, join | warm-up (roaring, level 2), discarded | "fj" | 0 | 12.5 | | 26 / 30 | 8000000 | 7779839 | 1469 | 1469 | 5876 | 106487 | 6487 | 4234 | 4234 | 49 | 0 |
| Q2 l_suppkey, join | Baleen off |  | 1 | 201.7 | yes | ||||||||||||
| Q2 l_suppkey, join | hash, level 2 | "" | 1 | 42.8 | yes | 0 / 30 | 59986052 | 59635367 | 320 | 0 | 329 | 169552 | 69552 | 90 | 0 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "f" | 1 | 43.3 | yes | 0 / 30 | 59986052 | 59711033 | 11794 | 11794 | 47176 | 133138 | 33138 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "j" | 1 | 43.0 | yes | 0 / 30 | 59986052 | 59715882 | 246 | 0 | 256 | 130617 | 30617 | 260 | 260 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "fj" | 1 | 41.6 | yes | 0 / 30 | 59986052 | 59775865 | 1432 | 1432 | 5728 | 101719 | 1719 | 4114 | 4114 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "jo" | 1 | 42.1 | yes | 0 / 30 | 59986052 | 59699697 | 261 | 0 | 271 | 138250 | 38250 | 275 | 275 | 275 | 255 |
| Q2 l_suppkey, join | hash, level 2 | "fjo" | 1 | 41.3 | yes | 0 / 30 | 59986052 | 59767821 | 1436 | 1436 | 5744 | 105575 | 5575 | 4110 | 4110 | 4110 | 4105 |
| Q2 l_suppkey, join | flat, level 2 | "" | 1 | 19.0 | yes | 18 / 30 | 24000000 | 23683905 | 288 | 0 | 299 | 153036 | 53036 | 80 | 0 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "f" | 1 | 35.7 | yes | 0 / 30 | 59986052 | 59715121 | 12614 | 12614 | 50456 | 130829 | 30829 | 70 | 0 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "j" | 1 | 31.7 | yes | 3 / 30 | 54000000 | 53732419 | 243 | 0 | 251 | 129394 | 29394 | 255 | 255 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "fj" | 1 | 12.5 | yes | 26 / 30 | 8000000 | 7791166 | 1439 | 1439 | 5756 | 100879 | 879 | 4112 | 4112 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "jo" | 1 | 15.0 | yes | 22 / 30 | 16000000 | 15708135 | 266 | 0 | 276 | 141151 | 41151 | 280 | 280 | 280 | 258 |
| Q2 l_suppkey, join | flat, level 2 | "fjo" | 1 | 12.7 | yes | 26 / 30 | 8000000 | 7783293 | 1426 | 1426 | 5704 | 104861 | 4861 | 4128 | 4128 | 4128 | 4106 |
| Q2 l_suppkey, join | roaring, level 2 | "" | 1 | 15.3 | yes | 22 / 30 | 16000000 | 15677394 | 293 | 0 | 303 | 155834 | 55834 | 84 | 0 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "f" | 1 | 15.8 | yes | 22 / 30 | 16000000 | 15722906 | 4998 | 4998 | 19992 | 133887 | 33887 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "j" | 1 | 25.3 | yes | 10 / 30 | 40000000 | 39716268 | 259 | 0 | 267 | 137035 | 37035 | 271 | 271 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "fj" | 1 | 12.1 | yes | 26 / 30 | 8000000 | 7786014 | 1463 | 1463 | 5852 | 103458 | 3458 | 4171 | 4171 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "jo" | 1 | 12.4 | yes | 26 / 30 | 8000000 | 7688639 | 285 | 0 | 295 | 150599 | 50599 | 299 | 299 | 299 | 277 |
| Q2 l_suppkey, join | roaring, level 2 | "fjo" | 1 | 12.9 | yes | 26 / 30 | 8000000 | 7779824 | 1453 | 1453 | 5812 | 106519 | 6519 | 4222 | 4222 | 4222 | 4106 |
| Q2 l_suppkey, join | Baleen off |  | 2 | 161.7 | yes | ||||||||||||
| Q2 l_suppkey, join | hash, level 2 | "" | 2 | 42.8 | yes | 0 / 30 | 59986052 | 59672098 | 286 | 0 | 294 | 151858 | 51858 | 80 | 0 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "f" | 2 | 40.6 | yes | 0 / 30 | 59986052 | 59711470 | 9113 | 9113 | 36452 | 132909 | 32909 | 72 | 0 | 49 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "j" | 2 | 41.7 | yes | 0 / 30 | 59986052 | 59717304 | 244 | 0 | 254 | 130043 | 30043 | 258 | 258 | 49 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "fj" | 2 | 40.4 | yes | 0 / 30 | 59986052 | 59776194 | 1429 | 1429 | 5716 | 101610 | 1610 | 4109 | 4109 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "jo" | 2 | 40.6 | yes | 0 / 30 | 59986052 | 59714902 | 248 | 0 | 256 | 131085 | 31085 | 260 | 260 | 260 | 241 |
| Q2 l_suppkey, join | hash, level 2 | "fjo" | 2 | 41.7 | yes | 0 / 30 | 59986052 | 59767299 | 1432 | 1432 | 5728 | 105793 | 5793 | 4125 | 4125 | 4125 | 4114 |
| Q2 l_suppkey, join | flat, level 2 | "" | 2 | 31.2 | yes | 4 / 30 | 52000000 | 51678775 | 292 | 0 | 302 | 155238 | 55238 | 83 | 0 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "f" | 2 | 29.0 | yes | 6 / 30 | 48000000 | 47725007 | 15018 | 15018 | 60072 | 132958 | 32958 | 72 | 0 | 49 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "j" | 2 | 18.3 | yes | 18 / 30 | 24000000 | 23735040 | 241 | 0 | 252 | 127927 | 27927 | 256 | 256 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "fj" | 2 | 12.6 | yes | 26 / 30 | 8000000 | 7791013 | 1437 | 1437 | 5748 | 101033 | 1033 | 4118 | 4118 | 49 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "jo" | 2 | 15.1 | yes | 22 / 30 | 16000000 | 15685248 | 287 | 0 | 297 | 152098 | 52098 | 301 | 301 | 301 | 279 |
| Q2 l_suppkey, join | flat, level 2 | "fjo" | 2 | 12.0 | yes | 26 / 30 | 8000000 | 7782921 | 1424 | 1424 | 5696 | 105028 | 5028 | 4123 | 4123 | 4123 | 4103 |
| Q2 l_suppkey, join | roaring, level 2 | "" | 2 | 31.8 | yes | 2 / 30 | 56000000 | 55654806 | 314 | 0 | 326 | 166863 | 66863 | 88 | 0 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "f" | 2 | 16.1 | yes | 22 / 30 | 16000000 | 15720928 | 5426 | 5426 | 21704 | 134992 | 34992 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "j" | 2 | 15.8 | yes | 23 / 30 | 14000000 | 13726806 | 248 | 0 | 260 | 132093 | 32093 | 264 | 264 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "fj" | 2 | 13.1 | yes | 26 / 30 | 8000000 | 7782975 | 1451 | 1451 | 5804 | 104883 | 4883 | 4227 | 4227 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "jo" | 2 | 33.6 | yes | 1 / 30 | 57986052 | 57652508 | 304 | 0 | 313 | 161060 | 61060 | 317 | 317 | 317 | 298 |
| Q2 l_suppkey, join | roaring, level 2 | "fjo" | 2 | 12.8 | yes | 26 / 30 | 8000000 | 7777272 | 1461 | 1461 | 5844 | 107731 | 7731 | 4206 | 4206 | 4206 | 4105 |
| Q2 l_suppkey, join | Baleen off |  | 3 | 164.5 | yes | ||||||||||||
| Q2 l_suppkey, join | hash, level 2 | "" | 3 | 42.1 | yes | 0 / 30 | 59986052 | 59667854 | 290 | 0 | 301 | 153873 | 53873 | 82 | 0 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "f" | 3 | 42.3 | yes | 0 / 30 | 59986052 | 59710445 | 9509 | 9509 | 38036 | 133218 | 33218 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "j" | 3 | 43.4 | yes | 0 / 30 | 59986052 | 59717687 | 245 | 0 | 255 | 129735 | 29735 | 259 | 259 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "fj" | 3 | 40.2 | yes | 0 / 30 | 59986052 | 59773750 | 1414 | 1414 | 5656 | 102722 | 2722 | 4125 | 4125 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "jo" | 3 | 42.3 | yes | 0 / 30 | 59986052 | 59709717 | 251 | 0 | 262 | 133741 | 33741 | 266 | 266 | 266 | 245 |
| Q2 l_suppkey, join | hash, level 2 | "fjo" | 3 | 42.3 | yes | 0 / 30 | 59986052 | 59771405 | 1429 | 1429 | 5716 | 103854 | 3854 | 4113 | 4113 | 4113 | 4103 |
| Q2 l_suppkey, join | flat, level 2 | "" | 3 | 27.3 | yes | 6 / 30 | 48000000 | 47683467 | 288 | 0 | 299 | 153118 | 53118 | 81 | 0 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "f" | 3 | 21.5 | yes | 14 / 30 | 32000000 | 31724712 | 13386 | 13386 | 53544 | 133157 | 33157 | 72 | 0 | 51 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "j" | 3 | 15.4 | yes | 22 / 30 | 16000000 | 15732027 | 244 | 0 | 252 | 129650 | 29650 | 256 | 256 | 49 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "fj" | 3 | 12.6 | yes | 26 / 30 | 8000000 | 7789388 | 1436 | 1436 | 5744 | 101832 | 1832 | 4111 | 4111 | 51 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "jo" | 3 | 31.8 | yes | 1 / 30 | 57986052 | 57678026 | 280 | 0 | 292 | 148945 | 48945 | 296 | 296 | 296 | 275 |
| Q2 l_suppkey, join | flat, level 2 | "fjo" | 3 | 12.7 | yes | 26 / 30 | 8000000 | 7779334 | 1423 | 1423 | 5692 | 106790 | 6790 | 4125 | 4125 | 4125 | 4109 |
| Q2 l_suppkey, join | roaring, level 2 | "" | 3 | 23.6 | yes | 14 / 30 | 32000000 | 31669502 | 302 | 0 | 310 | 159902 | 59902 | 84 | 0 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "f" | 3 | 16.8 | yes | 22 / 30 | 16000000 | 15721116 | 5535 | 5535 | 22140 | 134836 | 34836 | 72 | 0 | 49 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "j" | 3 | 15.6 | yes | 23 / 30 | 14000000 | 13725214 | 250 | 0 | 259 | 132842 | 32842 | 263 | 263 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "fj" | 3 | 11.9 | yes | 26 / 30 | 8000000 | 7785569 | 1446 | 1446 | 5784 | 103742 | 3742 | 4172 | 4172 | 49 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "jo" | 3 | 30.6 | yes | 2 / 30 | 56000000 | 55691029 | 281 | 0 | 289 | 149191 | 49191 | 293 | 293 | 293 | 274 |
| Q2 l_suppkey, join | roaring, level 2 | "fjo" | 3 | 12.6 | yes | 26 / 30 | 8000000 | 7778408 | 1440 | 1440 | 5760 | 107155 | 7155 | 4205 | 4205 | 4205 | 4110 |
| Q2 l_suppkey, join | Baleen off |  | 4 | 160.1 | yes | ||||||||||||
| Q2 l_suppkey, join | hash, level 2 | "" | 4 | 43.5 | yes | 0 / 30 | 59986052 | 59666208 | 291 | 0 | 300 | 154430 | 54430 | 82 | 0 | 51 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "f" | 4 | 42.2 | yes | 0 / 30 | 59986052 | 59710816 | 11309 | 11309 | 45236 | 132965 | 32965 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "j" | 4 | 41.3 | yes | 0 / 30 | 59986052 | 59714692 | 247 | 0 | 255 | 131215 | 31215 | 259 | 259 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "fj" | 4 | 40.0 | yes | 0 / 30 | 59986052 | 59776242 | 1425 | 1425 | 5700 | 101368 | 1368 | 4136 | 4136 | 49 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "jo" | 4 | 41.3 | yes | 0 / 30 | 59986052 | 59710948 | 250 | 0 | 260 | 132906 | 32906 | 264 | 264 | 264 | 243 |
| Q2 l_suppkey, join | hash, level 2 | "fjo" | 4 | 42.8 | yes | 0 / 30 | 59986052 | 59766574 | 1434 | 1434 | 5736 | 106220 | 6220 | 4122 | 4122 | 4122 | 4117 |
| Q2 l_suppkey, join | flat, level 2 | "" | 4 | 31.7 | yes | 0 / 30 | 59986052 | 59666342 | 292 | 0 | 302 | 154625 | 54625 | 82 | 0 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "f" | 4 | 34.2 | yes | 0 / 30 | 59986052 | 59713741 | 14052 | 14052 | 56208 | 131603 | 31603 | 71 | 0 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "j" | 4 | 16.0 | yes | 22 / 30 | 16000000 | 15731757 | 245 | 0 | 253 | 129695 | 29695 | 257 | 257 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "fj" | 4 | 12.6 | yes | 26 / 30 | 8000000 | 7791098 | 1433 | 1433 | 5732 | 101029 | 1029 | 4098 | 4098 | 49 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "jo" | 4 | 20.7 | yes | 14 / 30 | 32000000 | 31695678 | 278 | 0 | 287 | 146940 | 46940 | 291 | 291 | 291 | 269 |
| Q2 l_suppkey, join | flat, level 2 | "fjo" | 4 | 12.5 | yes | 26 / 30 | 8000000 | 7784502 | 1425 | 1425 | 5700 | 104235 | 4235 | 4136 | 4136 | 4136 | 4120 |
| Q2 l_suppkey, join | roaring, level 2 | "" | 4 | 18.8 | yes | 19 / 30 | 22000000 | 21669830 | 301 | 0 | 310 | 159719 | 59719 | 84 | 0 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "f" | 4 | 12.5 | yes | 26 / 30 | 8000000 | 7721173 | 4065 | 4065 | 16260 | 134790 | 34790 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "j" | 4 | 11.9 | yes | 26 / 30 | 8000000 | 7723489 | 253 | 0 | 262 | 133758 | 33758 | 266 | 266 | 49 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "fj" | 4 | 11.9 | yes | 26 / 30 | 8000000 | 7783464 | 1454 | 1454 | 5816 | 104802 | 4802 | 4221 | 4221 | 51 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "jo" | 4 | 15.0 | yes | 22 / 30 | 16000000 | 15687907 | 285 | 0 | 293 | 150835 | 50835 | 297 | 297 | 297 | 277 |
| Q2 l_suppkey, join | roaring, level 2 | "fjo" | 4 | 12.9 | yes | 26 / 30 | 8000000 | 7777144 | 1480 | 1480 | 5920 | 107740 | 7740 | 4201 | 4201 | 4201 | 4094 |
| Q2 l_suppkey, join | Baleen off |  | 5 | 158.8 | yes | ||||||||||||
| Q2 l_suppkey, join | hash, level 2 | "" | 5 | 43.8 | yes | 0 / 30 | 59986052 | 59664843 | 292 | 0 | 303 | 155340 | 55340 | 83 | 0 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "f" | 5 | 42.2 | yes | 0 / 30 | 59986052 | 59710649 | 14622 | 14622 | 58488 | 132977 | 32977 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "j" | 5 | 41.8 | yes | 0 / 30 | 59986052 | 59718137 | 244 | 0 | 255 | 129455 | 29455 | 259 | 259 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "fj" | 5 | 42.4 | yes | 0 / 30 | 59986052 | 59775598 | 1436 | 1436 | 5744 | 101770 | 1770 | 4113 | 4113 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "jo" | 5 | 41.8 | yes | 0 / 30 | 59986052 | 59709825 | 252 | 0 | 261 | 133480 | 33480 | 265 | 265 | 265 | 245 |
| Q2 l_suppkey, join | hash, level 2 | "fjo" | 5 | 42.1 | yes | 0 / 30 | 59986052 | 59770337 | 1425 | 1425 | 5700 | 104405 | 4405 | 4105 | 4105 | 4105 | 4100 |
| Q2 l_suppkey, join | flat, level 2 | "" | 5 | 32.8 | yes | 0 / 30 | 59986052 | 59674416 | 283 | 0 | 291 | 150597 | 50597 | 80 | 0 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "f" | 5 | 30.4 | yes | 2 / 30 | 56000000 | 55724404 | 15879 | 15879 | 63516 | 133162 | 33162 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "j" | 5 | 22.8 | yes | 17 / 30 | 26000000 | 25733122 | 243 | 0 | 256 | 129037 | 29037 | 260 | 260 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "fj" | 5 | 12.8 | yes | 26 / 30 | 8000000 | 7786857 | 1421 | 1421 | 5684 | 103099 | 3099 | 4112 | 4112 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "jo" | 5 | 31.8 | yes | 1 / 30 | 58000000 | 57714410 | 259 | 0 | 271 | 137937 | 37937 | 275 | 275 | 275 | 255 |
| Q2 l_suppkey, join | flat, level 2 | "fjo" | 5 | 12.4 | yes | 26 / 30 | 8000000 | 7782207 | 1434 | 1434 | 5736 | 105351 | 5351 | 4138 | 4138 | 4138 | 4119 |
| Q2 l_suppkey, join | roaring, level 2 | "" | 5 | 21.2 | yes | 16 / 30 | 28000000 | 27671229 | 300 | 0 | 310 | 158902 | 58902 | 84 | 0 | 49 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "f" | 5 | 37.6 | yes | 0 / 30 | 59986052 | 59704737 | 6441 | 6441 | 25764 | 136021 | 36021 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "j" | 5 | 13.7 | yes | 26 / 30 | 8000000 | 7726306 | 250 | 0 | 258 | 132376 | 32376 | 262 | 262 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "fj" | 5 | 12.3 | yes | 26 / 30 | 8000000 | 7782557 | 1450 | 1450 | 5800 | 105096 | 5096 | 4226 | 4226 | 51 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "jo" | 5 | 30.2 | yes | 2 / 30 | 56000000 | 55690202 | 282 | 0 | 291 | 149807 | 49807 | 295 | 295 | 295 | 275 |
| Q2 l_suppkey, join | roaring, level 2 | "fjo" | 5 | 12.7 | yes | 26 / 30 | 8000000 | 7779278 | 1452 | 1452 | 5808 | 106787 | 6787 | 4218 | 4218 | 4218 | 4107 |
| Q2 l_suppkey, join | Baleen off |  | 6 | 159.4 | yes | ||||||||||||
| Q2 l_suppkey, join | hash, level 2 | "" | 6 | 44.8 | yes | 0 / 30 | 59986052 | 59671992 | 287 | 0 | 296 | 151904 | 51904 | 80 | 0 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "f" | 6 | 43.8 | yes | 0 / 30 | 59986052 | 59710982 | 11491 | 11491 | 45964 | 132861 | 32861 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "j" | 6 | 41.5 | yes | 0 / 30 | 59986052 | 59719533 | 242 | 0 | 253 | 128847 | 28847 | 257 | 257 | 50 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "fj" | 6 | 40.7 | yes | 0 / 30 | 59986052 | 59776910 | 1434 | 1434 | 5736 | 101159 | 1159 | 4113 | 4113 | 49 | 0 |
| Q2 l_suppkey, join | hash, level 2 | "jo" | 6 | 43.1 | yes | 0 / 30 | 59986052 | 59708721 | 252 | 0 | 261 | 134078 | 34078 | 265 | 265 | 265 | 244 |
| Q2 l_suppkey, join | hash, level 2 | "fjo" | 6 | 44.7 | yes | 0 / 30 | 59986052 | 59769506 | 1428 | 1428 | 5712 | 104828 | 4828 | 4119 | 4119 | 4119 | 4111 |
| Q2 l_suppkey, join | flat, level 2 | "" | 6 | 20.8 | yes | 14 / 30 | 32000000 | 31683826 | 288 | 0 | 299 | 152855 | 52855 | 80 | 0 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "f" | 6 | 24.8 | yes | 10 / 30 | 40000000 | 39724325 | 14439 | 14439 | 57756 | 133185 | 33185 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "j" | 6 | 15.6 | yes | 22 / 30 | 16000000 | 15732869 | 243 | 0 | 253 | 129207 | 29207 | 257 | 257 | 50 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "fj" | 6 | 12.7 | yes | 26 / 30 | 8000000 | 7791154 | 1420 | 1420 | 5680 | 100985 | 985 | 4145 | 4145 | 49 | 0 |
| Q2 l_suppkey, join | flat, level 2 | "jo" | 6 | 31.5 | yes | 1 / 30 | 58000000 | 57713031 | 261 | 0 | 269 | 138549 | 38549 | 273 | 273 | 273 | 253 |
| Q2 l_suppkey, join | flat, level 2 | "fjo" | 6 | 12.8 | yes | 26 / 30 | 8000000 | 7782538 | 1426 | 1426 | 5704 | 105208 | 5208 | 4126 | 4126 | 4126 | 4103 |
| Q2 l_suppkey, join | roaring, level 2 | "" | 6 | 19.0 | yes | 18 / 30 | 24000000 | 23675378 | 296 | 0 | 307 | 156997 | 56997 | 84 | 0 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "f" | 6 | 32.7 | yes | 0 / 30 | 59986052 | 59704629 | 6099 | 6099 | 24396 | 136087 | 36087 | 72 | 0 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "j" | 6 | 15.4 | yes | 25 / 30 | 10000000 | 9723379 | 253 | 0 | 260 | 133622 | 33622 | 264 | 264 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "fj" | 6 | 12.6 | yes | 26 / 30 | 8000000 | 7783507 | 1471 | 1471 | 5884 | 104785 | 4785 | 4196 | 4196 | 50 | 0 |
| Q2 l_suppkey, join | roaring, level 2 | "jo" | 6 | 15.1 | yes | 22 / 30 | 16000000 | 15700118 | 273 | 0 | 284 | 144870 | 44870 | 288 | 288 | 288 | 268 |
| Q2 l_suppkey, join | roaring, level 2 | "fjo" | 6 | 12.9 | yes | 26 / 30 | 8000000 | 7776760 | 1455 | 1455 | 5820 | 107963 | 7963 | 4226 | 4226 | 4226 | 4105 |
| Q3 l_partkey, join | warm-up (roaring, level 2), discarded | "fj" | 0 | 43.1 | | 0 / 30 | 59986052 | 55825531 | 28613 | 28613 | 114452 | 2012235 | 12237 | 82489 | 82489 | 1035 | 0 |
| Q3 l_partkey, join | Baleen off |  | 1 | 127.9 | yes | ||||||||||||
| Q3 l_partkey, join | hash, level 2 | "" | 1 | 103.5 | yes | 0 / 30 | 59986052 | 29302228 | 28122 | 0 | 28132 | 14860531 | 16523 | 7713 | 0 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "f" | 1 | 117.2 | yes | 0 / 30 | 59986052 | 29323894 | 54985 | 54985 | 219940 | 14849525 | 5778 | 7708 | 0 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "j" | 1 | 105.8 | yes | 0 / 30 | 59986052 | 29312285 | 28113 | 0 | 28124 | 14855373 | 12272 | 28128 | 28128 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "fj" | 1 | 119.6 | yes | 0 / 30 | 59986052 | 29334622 | 54985 | 54985 | 219940 | 14844644 | 1065 | 219944 | 219944 | 7700 | 0 |
| Q3 l_partkey, join | hash, level 2 | "jo" | 1 | 94.7 | yes | 0 / 30 | 59986052 | 29289802 | 28135 | 0 | 28145 | 14866158 | 22812 | 28149 | 28149 | 28149 | 28145 |
| Q3 l_partkey, join | hash, level 2 | "fjo" | 1 | 118.0 | yes | 0 / 30 | 59986052 | 29331333 | 54985 | 54985 | 219940 | 14845361 | 2585 | 219944 | 219944 | 219944 | 219940 |
| Q3 l_partkey, join | flat, level 2 | "" | 1 | 39.9 | yes | 0 / 30 | 59986052 | 55663270 | 3959 | 0 | 3970 | 2091240 | 91242 | 1087 | 0 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "f" | 1 | 42.7 | yes | 0 / 30 | 59986052 | 55762125 | 40127 | 40127 | 160508 | 2042978 | 42980 | 1063 | 0 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "j" | 1 | 41.7 | yes | 0 / 30 | 59986052 | 55726692 | 3902 | 0 | 3911 | 2060219 | 60221 | 3915 | 3915 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "fj" | 1 | 43.0 | yes | 0 / 30 | 59986052 | 55848739 | 28576 | 28576 | 114304 | 2001365 | 1367 | 82362 | 82362 | 1035 | 0 |
| Q3 l_partkey, join | flat, level 2 | "jo" | 1 | 40.3 | yes | 0 / 30 | 59986052 | 55705116 | 3922 | 0 | 3931 | 2070689 | 70691 | 3935 | 3935 | 3935 | 3927 |
| Q3 l_partkey, join | flat, level 2 | "fjo" | 1 | 46.4 | yes | 0 / 30 | 59986052 | 55840735 | 28564 | 28564 | 114256 | 2005006 | 5008 | 82392 | 82392 | 82392 | 82381 |
| Q3 l_partkey, join | roaring, level 2 | "" | 1 | 43.5 | yes | 0 / 30 | 59986052 | 55617343 | 4003 | 0 | 4013 | 2113363 | 113365 | 1099 | 0 | 1035 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "f" | 1 | 45.1 | yes | 0 / 30 | 59986052 | 55719344 | 41251 | 41251 | 165004 | 2064056 | 64058 | 1074 | 0 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "j" | 1 | 42.8 | yes | 0 / 30 | 59986052 | 55691138 | 3934 | 0 | 3946 | 2077335 | 77337 | 3950 | 3950 | 1037 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "fj" | 1 | 45.2 | yes | 0 / 30 | 59986052 | 55829026 | 28552 | 28552 | 114208 | 2010650 | 10652 | 82442 | 82442 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "jo" | 1 | 42.1 | yes | 0 / 30 | 59986052 | 55675400 | 3949 | 0 | 3956 | 2084945 | 84947 | 3960 | 3960 | 3960 | 3954 |
| Q3 l_partkey, join | roaring, level 2 | "fjo" | 1 | 48.5 | yes | 0 / 30 | 59986052 | 55828827 | 28590 | 28590 | 114360 | 2010756 | 10758 | 82441 | 82441 | 82441 | 82339 |
| Q3 l_partkey, join | Baleen off |  | 2 | 143.1 | yes | ||||||||||||
| Q3 l_partkey, join | hash, level 2 | "" | 2 | 102.8 | yes | 0 / 30 | 59986052 | 29304067 | 28121 | 0 | 28130 | 14859022 | 15597 | 7713 | 0 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "f" | 2 | 120.5 | yes | 0 / 30 | 59986052 | 29325072 | 54985 | 54985 | 219940 | 14849277 | 5799 | 7708 | 0 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "j" | 2 | 104.7 | yes | 0 / 30 | 59986052 | 29310695 | 28115 | 0 | 28127 | 14855720 | 12448 | 28131 | 28131 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "fj" | 2 | 126.0 | yes | 0 / 30 | 59986052 | 29334286 | 54985 | 54985 | 219940 | 14844611 | 1278 | 219944 | 219944 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "jo" | 2 | 104.3 | yes | 0 / 30 | 59986052 | 29295735 | 28129 | 0 | 28139 | 14863205 | 19902 | 28143 | 28143 | 28143 | 28139 |
| Q3 l_partkey, join | hash, level 2 | "fjo" | 2 | 114.7 | yes | 0 / 30 | 59986052 | 29330893 | 54985 | 54985 | 219940 | 14845969 | 2732 | 219944 | 219944 | 219944 | 219940 |
| Q3 l_partkey, join | flat, level 2 | "" | 2 | 43.4 | yes | 0 / 30 | 59986052 | 55663454 | 3959 | 0 | 3970 | 2091015 | 91017 | 1087 | 0 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "f" | 2 | 46.8 | yes | 0 / 30 | 59986052 | 55762903 | 39922 | 39922 | 159688 | 2042906 | 42908 | 1063 | 0 | 1037 | 0 |
| Q3 l_partkey, join | flat, level 2 | "j" | 2 | 41.1 | yes | 0 / 30 | 59986052 | 55726890 | 3902 | 0 | 3912 | 2060101 | 60103 | 3916 | 3916 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "fj" | 2 | 43.1 | yes | 0 / 30 | 59986052 | 55847990 | 28558 | 28558 | 114232 | 2001635 | 1637 | 82413 | 82413 | 1035 | 0 |
| Q3 l_partkey, join | flat, level 2 | "jo" | 2 | 45.9 | yes | 0 / 30 | 59986052 | 55706891 | 3920 | 0 | 3930 | 2069795 | 69797 | 3933 | 3933 | 3933 | 3924 |
| Q3 l_partkey, join | flat, level 2 | "fjo" | 2 | 47.2 | yes | 0 / 30 | 59986052 | 55839292 | 28563 | 28563 | 114252 | 2005693 | 5695 | 82366 | 82366 | 82366 | 82355 |
| Q3 l_partkey, join | roaring, level 2 | "" | 2 | 43.0 | yes | 0 / 30 | 59986052 | 55606482 | 4013 | 0 | 4020 | 2118509 | 118511 | 1101 | 0 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "f" | 2 | 46.3 | yes | 0 / 30 | 59986052 | 55725979 | 40580 | 40580 | 162320 | 2060963 | 60965 | 1071 | 0 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "j" | 2 | 47.1 | yes | 0 / 30 | 59986052 | 55685165 | 3941 | 0 | 3950 | 2080342 | 80344 | 3954 | 3954 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "fj" | 2 | 43.9 | yes | 0 / 30 | 59986052 | 55826806 | 28607 | 28607 | 114428 | 2011647 | 11649 | 82448 | 82448 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "jo" | 2 | 44.0 | yes | 0 / 30 | 59986052 | 55656318 | 3967 | 0 | 3975 | 2094493 | 94495 | 3979 | 3979 | 3979 | 3973 |
| Q3 l_partkey, join | roaring, level 2 | "fjo" | 2 | 47.7 | yes | 0 / 30 | 59986052 | 55828071 | 28625 | 28625 | 114500 | 2011200 | 11202 | 82476 | 82476 | 82476 | 82369 |
| Q3 l_partkey, join | Baleen off |  | 3 | 141.6 | yes | ||||||||||||
| Q3 l_partkey, join | hash, level 2 | "" | 3 | 102.5 | yes | 0 / 30 | 59986052 | 29307000 | 28119 | 0 | 28129 | 14858007 | 14631 | 7712 | 0 | 7702 | 0 |
| Q3 l_partkey, join | hash, level 2 | "f" | 3 | 122.9 | yes | 0 / 30 | 59986052 | 29324978 | 54985 | 54985 | 219940 | 14849333 | 5845 | 7708 | 0 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "j" | 3 | 99.6 | yes | 0 / 30 | 59986052 | 29318341 | 28108 | 0 | 28117 | 14852114 | 9023 | 28121 | 28121 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "fj" | 3 | 119.0 | yes | 0 / 30 | 59986052 | 29333280 | 54985 | 54985 | 219940 | 14845117 | 1379 | 219944 | 219944 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "jo" | 3 | 102.8 | yes | 0 / 30 | 59986052 | 29292672 | 28131 | 0 | 28140 | 14864481 | 21307 | 28144 | 28144 | 28144 | 28140 |
| Q3 l_partkey, join | hash, level 2 | "fjo" | 3 | 111.0 | yes | 0 / 30 | 59986052 | 29331651 | 54985 | 54985 | 219940 | 14845943 | 2479 | 219944 | 219944 | 219944 | 219940 |
| Q3 l_partkey, join | flat, level 2 | "" | 3 | 41.2 | yes | 0 / 30 | 59986052 | 55662942 | 3961 | 0 | 3973 | 2091416 | 91418 | 1087 | 0 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "f" | 3 | 44.3 | yes | 0 / 30 | 59986052 | 55763088 | 40198 | 40198 | 160792 | 2042576 | 42578 | 1062 | 0 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "j" | 3 | 41.6 | yes | 0 / 30 | 59986052 | 55724408 | 3904 | 0 | 3915 | 2061196 | 61198 | 3919 | 3919 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "fj" | 3 | 43.2 | yes | 0 / 30 | 59986052 | 55846994 | 28588 | 28588 | 114352 | 2001869 | 1871 | 82397 | 82397 | 1035 | 0 |
| Q3 l_partkey, join | flat, level 2 | "jo" | 3 | 44.6 | yes | 0 / 30 | 59986052 | 55708124 | 3919 | 0 | 3928 | 2069497 | 69499 | 3932 | 3932 | 3932 | 3924 |
| Q3 l_partkey, join | flat, level 2 | "fjo" | 3 | 47.2 | yes | 0 / 30 | 59986052 | 55842012 | 28555 | 28555 | 114220 | 2004380 | 4382 | 82357 | 82357 | 82357 | 82344 |
| Q3 l_partkey, join | roaring, level 2 | "" | 3 | 42.9 | yes | 0 / 30 | 59986052 | 55610830 | 4008 | 0 | 4019 | 2116411 | 116413 | 1100 | 0 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "f" | 3 | 45.0 | yes | 0 / 30 | 59986052 | 55722595 | 41172 | 41172 | 164688 | 2062165 | 62167 | 1072 | 0 | 1035 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "j" | 3 | 43.1 | yes | 0 / 30 | 59986052 | 55674074 | 3949 | 0 | 3960 | 2086011 | 86013 | 3964 | 3964 | 1037 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "fj" | 3 | 44.2 | yes | 0 / 30 | 59986052 | 55828498 | 28564 | 28564 | 114256 | 2011126 | 11128 | 82479 | 82479 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "jo" | 3 | 43.5 | yes | 0 / 30 | 59986052 | 55666282 | 3957 | 0 | 3967 | 2089424 | 89426 | 3971 | 3971 | 3971 | 3964 |
| Q3 l_partkey, join | roaring, level 2 | "fjo" | 3 | 49.8 | yes | 0 / 30 | 59986052 | 55829471 | 28596 | 28596 | 114384 | 2010476 | 10478 | 82444 | 82444 | 82444 | 82352 |
| Q3 l_partkey, join | Baleen off |  | 4 | 142.9 | yes | ||||||||||||
| Q3 l_partkey, join | hash, level 2 | "" | 4 | 108.3 | yes | 0 / 30 | 59986052 | 29307856 | 28117 | 0 | 28129 | 14857110 | 13835 | 7712 | 0 | 7702 | 0 |
| Q3 l_partkey, join | hash, level 2 | "f" | 4 | 122.3 | yes | 0 / 30 | 59986052 | 29324370 | 54985 | 54985 | 219940 | 14849784 | 5846 | 7708 | 0 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "j" | 4 | 106.7 | yes | 0 / 30 | 59986052 | 29309419 | 28117 | 0 | 28125 | 14856567 | 13277 | 28129 | 28129 | 7702 | 0 |
| Q3 l_partkey, join | hash, level 2 | "fj" | 4 | 124.8 | yes | 0 / 30 | 59986052 | 29333037 | 54985 | 54985 | 219940 | 14845299 | 1210 | 219944 | 219944 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "jo" | 4 | 98.6 | yes | 0 / 30 | 59986052 | 29311157 | 28113 | 0 | 28124 | 14855788 | 12345 | 28128 | 28128 | 28128 | 28124 |
| Q3 l_partkey, join | hash, level 2 | "fjo" | 4 | 120.4 | yes | 0 / 30 | 59986052 | 29330963 | 54985 | 54985 | 219940 | 14846112 | 2617 | 219944 | 219944 | 219944 | 219940 |
| Q3 l_partkey, join | flat, level 2 | "" | 4 | 40.7 | yes | 0 / 30 | 59986052 | 55659777 | 3963 | 0 | 3974 | 2092793 | 92795 | 1087 | 0 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "f" | 4 | 42.6 | yes | 0 / 30 | 59986052 | 55763495 | 39213 | 39213 | 156852 | 2042433 | 42435 | 1062 | 0 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "j" | 4 | 41.4 | yes | 0 / 30 | 59986052 | 55730252 | 3899 | 0 | 3910 | 2058476 | 58478 | 3914 | 3914 | 1037 | 0 |
| Q3 l_partkey, join | flat, level 2 | "fj" | 4 | 42.8 | yes | 0 / 30 | 59986052 | 55848424 | 28537 | 28537 | 114148 | 2001568 | 1570 | 82373 | 82373 | 1035 | 0 |
| Q3 l_partkey, join | flat, level 2 | "jo" | 4 | 42.9 | yes | 0 / 30 | 59986052 | 55703689 | 3923 | 0 | 3933 | 2071298 | 71300 | 3937 | 3937 | 3937 | 3930 |
| Q3 l_partkey, join | flat, level 2 | "fjo" | 4 | 46.7 | yes | 0 / 30 | 59986052 | 55842880 | 28553 | 28553 | 114212 | 2004200 | 4202 | 82379 | 82379 | 82379 | 82361 |
| Q3 l_partkey, join | roaring, level 2 | "" | 4 | 42.5 | yes | 0 / 30 | 59986052 | 55616925 | 4003 | 0 | 4014 | 2113123 | 113125 | 1099 | 0 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "f" | 4 | 45.5 | yes | 0 / 30 | 59986052 | 55718911 | 40022 | 40022 | 160088 | 2064124 | 64126 | 1073 | 0 | 1037 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "j" | 4 | 42.4 | yes | 0 / 30 | 59986052 | 55673459 | 3951 | 0 | 3961 | 2086009 | 86011 | 3965 | 3965 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "fj" | 4 | 45.1 | yes | 0 / 30 | 59986052 | 55827074 | 28606 | 28606 | 114424 | 2011452 | 11454 | 82490 | 82490 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "jo" | 4 | 43.2 | yes | 0 / 30 | 59986052 | 55661230 | 3962 | 0 | 3972 | 2092236 | 92238 | 3976 | 3976 | 3976 | 3968 |
| Q3 l_partkey, join | roaring, level 2 | "fjo" | 4 | 46.7 | yes | 0 / 30 | 59986052 | 55825450 | 28577 | 28577 | 114308 | 2012360 | 12362 | 82449 | 82449 | 82449 | 82358 |
| Q3 l_partkey, join | Baleen off |  | 5 | 146.4 | yes | ||||||||||||
| Q3 l_partkey, join | hash, level 2 | "" | 5 | 106.6 | yes | 0 / 30 | 59986052 | 29303474 | 28120 | 0 | 28130 | 14859431 | 15734 | 7713 | 0 | 7700 | 0 |
| Q3 l_partkey, join | hash, level 2 | "f" | 5 | 121.5 | yes | 0 / 30 | 59986052 | 29324986 | 54985 | 54985 | 219940 | 14849173 | 5921 | 7708 | 0 | 7700 | 0 |
| Q3 l_partkey, join | hash, level 2 | "j" | 5 | 100.2 | yes | 0 / 30 | 59986052 | 29311846 | 28114 | 0 | 28125 | 14855661 | 12113 | 28129 | 28129 | 7700 | 0 |
| Q3 l_partkey, join | hash, level 2 | "fj" | 5 | 119.4 | yes | 0 / 30 | 59986052 | 29334772 | 54985 | 54985 | 219940 | 14844699 | 1169 | 219944 | 219944 | 7700 | 0 |
| Q3 l_partkey, join | hash, level 2 | "jo" | 5 | 102.1 | yes | 0 / 30 | 59986052 | 29310208 | 28116 | 0 | 28124 | 14856336 | 12910 | 28128 | 28128 | 28128 | 28124 |
| Q3 l_partkey, join | hash, level 2 | "fjo" | 5 | 112.6 | yes | 0 / 30 | 59986052 | 29330535 | 54985 | 54985 | 219940 | 14846514 | 2557 | 219944 | 219944 | 219944 | 219940 |
| Q3 l_partkey, join | flat, level 2 | "" | 5 | 41.5 | yes | 0 / 30 | 59986052 | 55664163 | 3959 | 0 | 3969 | 2090553 | 90555 | 1087 | 0 | 1035 | 0 |
| Q3 l_partkey, join | flat, level 2 | "f" | 5 | 42.7 | yes | 0 / 30 | 59986052 | 55764119 | 39454 | 39454 | 157816 | 2042110 | 42112 | 1062 | 0 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "j" | 5 | 42.0 | yes | 0 / 30 | 59986052 | 55723683 | 3904 | 0 | 3917 | 2061772 | 61774 | 3921 | 3921 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "fj" | 5 | 46.1 | yes | 0 / 30 | 59986052 | 55848265 | 28571 | 28571 | 114284 | 2001413 | 1415 | 82391 | 82391 | 1035 | 0 |
| Q3 l_partkey, join | flat, level 2 | "jo" | 5 | 45.6 | yes | 0 / 30 | 59986052 | 55700427 | 3927 | 0 | 3936 | 2072914 | 72916 | 3940 | 3940 | 3940 | 3933 |
| Q3 l_partkey, join | flat, level 2 | "fjo" | 5 | 49.5 | yes | 0 / 30 | 59986052 | 55841599 | 28587 | 28587 | 114348 | 2004552 | 4554 | 82411 | 82411 | 82411 | 82395 |
| Q3 l_partkey, join | roaring, level 2 | "" | 5 | 42.4 | yes | 0 / 30 | 59986052 | 55596070 | 4023 | 0 | 4030 | 2123416 | 123418 | 1103 | 0 | 1035 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "f" | 5 | 44.6 | yes | 0 / 30 | 59986052 | 55719932 | 40908 | 40908 | 163632 | 2063386 | 63388 | 1072 | 0 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "j" | 5 | 43.6 | yes | 0 / 30 | 59986052 | 55680671 | 3944 | 0 | 3954 | 2082478 | 82480 | 3958 | 3958 | 1035 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "fj" | 5 | 45.6 | yes | 0 / 30 | 59986052 | 55827512 | 28610 | 28610 | 114440 | 2011350 | 11352 | 82476 | 82476 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "jo" | 5 | 43.6 | yes | 0 / 30 | 59986052 | 55679209 | 3946 | 0 | 3957 | 2083523 | 83525 | 3961 | 3961 | 3961 | 3954 |
| Q3 l_partkey, join | roaring, level 2 | "fjo" | 5 | 47.4 | yes | 0 / 30 | 59986052 | 55826791 | 28609 | 28609 | 114436 | 2011664 | 11666 | 82476 | 82476 | 82476 | 82367 |
| Q3 l_partkey, join | Baleen off |  | 6 | 146.0 | yes | ||||||||||||
| Q3 l_partkey, join | hash, level 2 | "" | 6 | 104.9 | yes | 0 / 30 | 59986052 | 29302364 | 28123 | 0 | 28132 | 14859989 | 16997 | 7714 | 0 | 7700 | 0 |
| Q3 l_partkey, join | hash, level 2 | "f" | 6 | 125.5 | yes | 0 / 30 | 59986052 | 29325335 | 54985 | 54985 | 219940 | 14849202 | 5874 | 7708 | 0 | 7700 | 0 |
| Q3 l_partkey, join | hash, level 2 | "j" | 6 | 105.1 | yes | 0 / 30 | 59986052 | 29313730 | 28113 | 0 | 28121 | 14854232 | 11091 | 28125 | 28125 | 7700 | 0 |
| Q3 l_partkey, join | hash, level 2 | "fj" | 6 | 120.7 | yes | 0 / 30 | 59986052 | 29333603 | 54985 | 54985 | 219940 | 14844629 | 1292 | 219944 | 219944 | 7701 | 0 |
| Q3 l_partkey, join | hash, level 2 | "jo" | 6 | 100.8 | yes | 0 / 30 | 59986052 | 29305457 | 28119 | 0 | 28130 | 14858570 | 14798 | 28134 | 28134 | 28134 | 28130 |
| Q3 l_partkey, join | hash, level 2 | "fjo" | 6 | 119.5 | yes | 0 / 30 | 59986052 | 29330395 | 54985 | 54985 | 219940 | 14846308 | 2994 | 219944 | 219944 | 219944 | 219940 |
| Q3 l_partkey, join | flat, level 2 | "" | 6 | 41.6 | yes | 0 / 30 | 59986052 | 55663670 | 3960 | 0 | 3968 | 2091236 | 91238 | 1087 | 0 | 1037 | 0 |
| Q3 l_partkey, join | flat, level 2 | "f" | 6 | 45.9 | yes | 0 / 30 | 59986052 | 55765028 | 39434 | 39434 | 157736 | 2042153 | 42155 | 1062 | 0 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "j" | 6 | 44.8 | yes | 0 / 30 | 59986052 | 55723116 | 3905 | 0 | 3915 | 2061838 | 61840 | 3919 | 3919 | 1036 | 0 |
| Q3 l_partkey, join | flat, level 2 | "fj" | 6 | 43.7 | yes | 0 / 30 | 59986052 | 55847600 | 28567 | 28567 | 114268 | 2001522 | 1524 | 82386 | 82386 | 1035 | 0 |
| Q3 l_partkey, join | flat, level 2 | "jo" | 6 | 40.7 | yes | 0 / 30 | 59986052 | 55721030 | 3907 | 0 | 3917 | 2063238 | 63240 | 3921 | 3921 | 3921 | 3913 |
| Q3 l_partkey, join | flat, level 2 | "fjo" | 6 | 46.5 | yes | 0 / 30 | 59986052 | 55840824 | 28538 | 28538 | 114152 | 2005021 | 5023 | 82368 | 82368 | 82368 | 82348 |
| Q3 l_partkey, join | roaring, level 2 | "" | 6 | 42.8 | yes | 0 / 30 | 59986052 | 55612112 | 4007 | 0 | 4019 | 2115870 | 115872 | 1100 | 0 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "f" | 6 | 45.8 | yes | 0 / 30 | 59986052 | 55720755 | 41332 | 41332 | 165328 | 2063166 | 63168 | 1073 | 0 | 1037 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "j" | 6 | 45.8 | yes | 0 / 30 | 59986052 | 55695768 | 3931 | 0 | 3941 | 2075410 | 75412 | 3945 | 3945 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "fj" | 6 | 45.0 | yes | 0 / 30 | 59986052 | 55827532 | 28518 | 28518 | 114072 | 2011353 | 11355 | 82459 | 82459 | 1036 | 0 |
| Q3 l_partkey, join | roaring, level 2 | "jo" | 6 | 42.1 | yes | 0 / 30 | 59986052 | 55664857 | 3959 | 0 | 3968 | 2090271 | 90273 | 3972 | 3972 | 3972 | 3966 |
| Q3 l_partkey, join | roaring, level 2 | "fjo" | 6 | 49.4 | yes | 0 / 30 | 59986052 | 55828035 | 28566 | 28566 | 114264 | 2011147 | 11149 | 82473 | 82473 | 82473 | 82371 |
