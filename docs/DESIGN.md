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

`run` prepares (creates and validates) the state directory **before** executing
any job, so an unusable `--state-dir` — for example a path that is an existing
regular file — fails fast with exit code 3 while no job has run yet. If
persisting the record still fails *after* execution (e.g. the disk filled up
mid-run), the exit code prefers the batch outcome: a failed batch exits 1
(`EXIT_FAILED`) so wrapper scripts branch on the real result, and only a
successful batch reports the persistence failure as exit 3 (`EXIT_CONFIG`).

## Key decisions

- **Immutable records.** All model types are Java records with normalization and
  light validation in their canonical constructors. This makes the data easy to
  reason about and safe to share.
- **Strict numeric parsing.** Fractional values in integer fields (e.g.
  `timeoutSeconds: 0.9` or `retries: 2.9`) are rejected as configuration errors
  (exit code 3) instead of being silently truncated: Jackson's default
  float-to-int coercion is disabled in `BatchConfigLoader`, so `timeoutSeconds:
  0.9` can never silently become `0` — which would mean *no timeout at all*.
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

## Security & trust model

`batch-scheduler` executes the commands defined in a batch file. **The batch
configuration is trusted input** — like a `Makefile` or a CI pipeline, it is
authored by the operator who runs the tool, and by design it can run arbitrary
commands, with arbitrary environment variables and working directories. The tool
does not, and is not intended to, sandbox those commands. Do not feed it batch
files from untrusted sources.

Within that model, the implementation still defends against accidental and
malicious resource exhaustion and against tampering with the state directory:

- **Bounded config parsing.** The YAML parser is configured with an explicit
  document-size limit (`MAX_CONFIG_BYTES`, 4 MiB), an alias-count limit (defends
  against "billion laughs" alias-expansion bombs), a nesting-depth limit, and
  recursive keys disabled. Oversized files are rejected before being read whole.
- **Bounded output capture.** Each job's combined output is drained on a
  dedicated thread (so a full pipe never blocks the child) and only a bounded
  tail is retained; an individual line is capped so a single runaway line cannot
  exhaust memory.
- **Iterative graph algorithms.** Validation, cycle detection, and topological
  sort are iterative, so a deeply-nested or very long dependency chain cannot
  overflow the call stack.
- **State-directory safety.** Run ids are validated to reject path separators and
  `..` so a record can never be written or read outside the state directory.
  Writes go through a temp file and an atomic move; reads do not follow symlinks.

## Future extensions

- **Parallel execution** of independent jobs (run ready jobs concurrently while
  still honoring the DAG).
- **Scheduling** (cron-style triggers) so batches can run unattended.
- **Richer retry / backoff** policies (e.g. exponential backoff, jitter).
- **Resume / rerun-failed** — re-run only the failed and skipped jobs of a prior
  run.
- **Pluggable stores** — alternative `ExecutionStore` implementations (database,
  object storage) behind the existing interface.
