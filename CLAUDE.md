# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**SmartRabbit** is a research prototype for interactive query processing built on top of Apache AsterixDB. It executes two competing query plans concurrently — an interactive (index-based, fast partial results) plan and a blocking (full scan, complete results) plan — and switches between them via file-based signaling. The project includes a DuckDB benchmark harness for comparative evaluation.

## Build Commands

Build from the `asterixdb/` subdirectory (not the repo root):

```bash
# Full build (skip tests/RAT/checkstyle for development)
cd asterixdb/
mvn clean package -DskipTests -DskipRat -Dlicense.skip=true -Dcheckstyle.skip=true

# Run tests for a single module (e.g., asterix-app)
cd asterixdb/asterix-app
mvn test -DskipRat -Dlicense.skip=true -Dcheckstyle.skip=true

# Run a single test class
mvn test -DskipRat -Dlicense.skip=true -Dcheckstyle.skip=true -Dtest=TestClassName
```

Requires: JDK 11+ (compiles targeting Java 21), Maven 3.3.9+, Python 3.6+.

## Running AsterixDB Locally

```bash
java -cp asterixdb/asterix-server/target/asterix-server-*-binary-assembly.jar \
  org.apache.asterix.api.common.AsterixHyracksIntegrationUtil
```

Or search for `AsterixHyracksIntegrationUtil.java` in your IDE and run it directly. Configuration (buffer cache size, partition count, etc.) is in `asterixdb/src/test/resources/cc-main.conf`.

## Running SmartRabbit Experiments

```bash
cd asterixdb/

# Load TPC-H data (requires dbgen output)
python3 load_tpch_datasets.py --sf 10 --base-path /scratch/dbgen-data/SF10

# Run experiments
python3 run_workflow.py --sfs 10 30 100 --runs 3 --queries q3 --nodes 204
# Optional: --interactive-only or --blocking-only
```

`SmartRabbitExecutor.py` is the core executor — it POSTs queries to the AsterixDB REST API at `http://localhost:19002/query/service` and collects timing/metrics. The `--nodes` flag is bookkeeping for which cluster config was used.

## Repository Structure

The repo root contains two Maven modules (defined in `pom.xml`):

- **`hyracks-fullstack/`** — Low-level query execution engine (Hyracks) and algebraic optimizer (Algebricks). Contains storage access methods (LSM B-Tree, R-Tree, inverted index), dataflow operators, and distributed cluster coordination (CC-NC architecture).
- **`asterixdb/`** — The AsterixDB database layer: SQL++ parser/compiler, metadata management, query optimization rules, runtime operators, and the server assembly.
- **`duckdb/`** — Comparative SSB (Star Schema Benchmark) experiments on DuckDB, including "grafting" (pre-computed projections) variants.

## SmartRabbit Architecture (Key Files)

The hybrid execution system works through these components:

**Configuration & Properties:**
- `asterixdb/asterix-common/.../config/SmartRabbitProperties.java` — Defines `HYBRID_EXECUTION_DIR` property
- `asterixdb/asterix-common/.../config/CompilerProperties.java` — `COMPILER_INTERACTIVE_MODE` and `COMPILER_BLOCKING_MODE` flags
- CC config key: `smartrabbit.hybrid_execution_dir` in `cc.conf` files

**Optimization Rules (plan generation):**
- `asterixdb/asterix-algebra/.../smartrabbit/InteractiveJoinRule.java` — Wraps joins with dual-plan (hash join + index nested-loop join)
- `asterixdb/asterix-algebra/.../smartrabbit/InteractiveSortRule.java` — Index-based alternative for ORDER BY/GROUP BY
- `asterixdb/asterix-algebra/.../base/SetExecutionModeForInteractiveModeRule.java` — Sets UNPARTITIONED mode for interactive results

**Plan Switch Operator:**
- `hyracks-fullstack/algebricks/.../operators/logical/PlanSwitchOperator.java` — Logical operator wrapping interactive (plan 0) and blocking (plan 1) alternatives

**Runtime Signaling (file-based IPC):**
- `hyracks-fullstack/hyracks/.../result/ResultWriterOperatorDescriptor.java` — In interactive mode: writes progressive results to `InteractiveAnswers`, tracks `InteractiveAnswerRate`, watches for `B2ISignal` to stop
- `hyracks-fullstack/hyracks/.../group/sort/ExternalSortGroupByRunMerger.java` — In blocking mode: sends `B2ISignal` when global group-by begins, waits for `I2BSignal` acknowledgment (up to 180s)
- `hyracks-fullstack/hyracks/.../util/SmartRabbitHybridExecutionDirResolver.java` — Resolves the shared directory for signal files

**Signal Protocol:** Blocking plan writes `B2ISignal` → Interactive plan writes `I2BSignal` + `InteractiveAnswerCount` → Blocking plan resumes with complete results. The switch is unidirectional (no switch back).

## Key Configuration

- `asterixdb/src/test/resources/cc-main.conf` — Local dev cluster config (buffer cache, partitions, hybrid execution dir)
- `asterixdb/asterix-server/src/main/opt/local/conf/cc.conf` — Production/deployment config
- `asterixdb/asterix-server/src/test/resources/NCServiceExecutionIT/cc.conf` — Integration test config (2 NCs)

## Maven Profiles

- `opt-modules` — Enables `asterix-opt` optimizer module (activated if `asterix-opt/pom.xml` exists)
- `slow-aql-tests` — Enables long-running test suites
- `python-udfs` — Enables Python UDF support
