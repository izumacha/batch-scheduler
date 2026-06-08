# Design

`batch-scheduler` is a small, robust MVP tool for running a set of jobs that have
dependencies between them. This document describes its goals, architecture, and
the main decisions behind it.

## Goals & non-goals

**Goals**

- A minimal, **robust** MVP: do a few things and do them correctly.
- Declarative batches in YAML.
- Honor dependencies as a validated DAG and run jobs in a deterministic order.
- Per-job retries and timeouts.
- Clear failure semantics and a persisted, inspectable run history.

**Non-goals (for the MVP)**

- Scheduling / cron-style triggers — runs are started explicitly by the user.
- Parallel execution — jobs run sequentially, one at a time.
- Distributed execution — everything runs in a single local process.

## Architecture

The code is organized into five packages, each with a single responsibility:

- `model` — immutable data records: `Job`, `Batch`, `JobResult`,
  `ExecutionResult`, and the `JobStatus` enum.
- `config` — loads and parses YAML into the model (`BatchConfigLoader`) and the
  exception types (`ConfigException`, `ValidationException`).
- `core` — structural validation and the dependency DAG (`DependencyGraph`) plus
  the execution engine (`BatchExecutor`).
- `state` — persistence of run reports (`ExecutionStore` and its
  `JsonExecutionStore` implementation).
- `cli` — the picocli-based command-line interface (`Main`, `BatchCli`, and the
  `run` / `validate` / `list` sub-commands).

### Data flow

```
YAML file
   │  BatchConfigLoader.load(path)
   ▼
Batch  ──────────────────────────────► (validate) DependencyGraph.build(batch)
   │                                          │  throws ValidationException
   │  BatchExecutor.execute(batch)            ▼
   ▼                                   topologicalOrder()
ExecutionResult  (per-job JobResults, overall status, timings)
   │  JsonExecutionStore.save(result)
   ▼
.batch-state/<runId>.json   ◄── list / findAll() reads these back
```

The CLI ties these together: `validate` stops after building the graph; `run`
goes all the way through execution and persistence; `list` reads stored results.

## Key decisions

- **Immutable records.** All model types are Java records with normalization and
  light validation in their canonical constructors. This makes the data easy to
  reason about and safe to share.
- **Validation aggregates all errors.** `DependencyGraph.build` collects every
  structural problem (duplicate ids, unknown/self dependencies, empty commands,
  cycles) and throws a single `ValidationException` carrying the full list, so
  users can fix everything in one pass rather than one error at a time.
- **Failure semantics.** Jobs run in topological order. If a job ends `FAILED`,
  every job that depends on it (transitively) is marked `SKIPPED`, and the overall
  run status is `FAILED`. A run is `SUCCEEDED` only if every job succeeded.
- **State as one JSON file per run.** Each `ExecutionResult` is persisted as a
  standalone JSON document keyed by run id. This keeps the store trivially simple,
  human-readable, and easy to back up or inspect, with no database dependency.

## Future extensions

- **Parallel execution** of independent jobs (run ready jobs concurrently while
  still honoring the DAG).
- **Scheduling** (cron-style triggers) so batches can run unattended.
- **Richer retry / backoff** policies (e.g. exponential backoff, jitter).
- **Resume / rerun-failed** — re-run only the failed and skipped jobs of a prior
  run.
- **Pluggable stores** — alternative `ExecutionStore` implementations (database,
  object storage) behind the existing interface.
