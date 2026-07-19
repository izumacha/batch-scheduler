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

`run` validates the DAG (exit code 2 on structural errors) *before* touching
the state directory, so an invalid batch never creates the `--state-dir` tree
as a side effect and validation errors are never masked by a state-directory
error. It then prepares the state directory **before** executing any job: the
pre-run step creates the directory and catches structurally-unusable paths —
for example a path that is an existing regular file — failing fast with exit
code 3 while no job has run yet. This pre-run step is *not* a full writability
probe: an existing directory without write permission still surfaces only at
save time, after the run. The store constructor deliberately does not probe
for writability, because `list` shares `JsonExecutionStore` and must keep
working against a read-only state directory. When persisting the record fails
*after* execution (read-only directory, disk filled up mid-run, the directory
destroyed by a job), the exit code prefers the batch outcome: a failed batch
exits 1 (`EXIT_FAILED`) so wrapper scripts branch on the real result, and only
a successful batch reports the persistence failure as exit 3 (`EXIT_CONFIG`).

## Key decisions

- **Immutable records.** All model types are Java records with normalization and
  light validation in their canonical constructors. This makes the data easy to
  reason about and safe to share.
- **Strict numeric parsing.** Float literals in integer fields (`timeoutSeconds`,
  `retries`) are rejected as configuration errors (exit code 3) instead of being
  silently truncated: Jackson's default float-to-int coercion is disabled in
  `BatchConfigLoader`, so `timeoutSeconds: 0.9` can never silently become `0` —
  which would mean *no timeout at all*. This applies to **all** float literals,
  not just fractional ones: even a whole-number float such as `timeoutSeconds:
  30.0` is rejected — these fields accept integers only.
- **Validation aggregates all errors.** `DependencyGraph.build` collects every
  structural problem (duplicate ids, unknown/self dependencies, empty or blank
  commands — a command whose first token, the program name, is empty or
  whitespace-only can never start — and cycles) and throws a single
  `ValidationException` carrying the full list, so users can fix everything in
  one pass rather than one error at a time. Cycle detection uses a standard
  iterative DFS (not full strongly-connected-component enumeration), so in the
  rare case where two distinct cycles share a confluence node that was already
  fully explored via another path, only one is guaranteed to be reported per
  `validate()` call — re-running `validate` after fixing it will surface any
  remainder. See the Javadoc on `DependencyGraph.detectCycles` for details.
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
  This now also covers the base directory itself, not just individual
  `<runId>.json` files: `ensureBaseDirectory()` refuses to operate if `--state-dir`
  is itself a symlink (rather than following it via `createDirectories`), and
  `findAll`/`findRecent` treat a symlinked base directory the same as a missing
  one, so a pre-planted symlink cannot redirect reads or writes elsewhere.

## Future extensions

- **Parallel execution** of independent jobs (run ready jobs concurrently while
  still honoring the DAG).
- **Scheduling** (cron-style triggers) so batches can run unattended.
- **Pluggable stores** — alternative `ExecutionStore` implementations (database,
  object storage) behind the existing interface.

`Resume / rerun-failed` has been implemented: `run --rerun-failed <runId>` loads
the named prior `ExecutionResult` from the state directory and passes it to
`BatchExecutor.execute(Batch, ExecutionResult)`. While executing in topological
order, any job whose result in that prior run was `SUCCEEDED` is reused verbatim
(not re-run) and used as-is when checking whether it blocks dependents; jobs that
were `FAILED` or `SKIPPED`, and jobs present in the batch but absent from the
prior result (newly added since), execute normally. This is a rerun, not an
in-place resume: it always produces a fresh run id and a new persisted record
covering every job in the batch, so the run history keeps a complete, independently
inspectable record of each attempt.

`execute` rejects (`IllegalArgumentException`, surfaced by `run` as a one-line
`error:` message and exit `3`) a `priorResult` whose `batchName()` does not match
the batch being run, so a `--rerun-failed` runId copy-pasted from an unrelated
batch file — one that happens to share a job id with the batch actually being
run, under the same shared `--state-dir` — cannot silently borrow that job's
unrelated result. This guard is a best-effort mitigation, not a strong identity
check: batch `name` is a human-chosen label with no uniqueness constraint (it
defaults to `"batch"` when unset), so two distinct batch files that both happen
to use the same name are not distinguished. Reuse also does not detect that a
`SUCCEEDED` job's own definition (its `command`, `dependsOn`, `env`, etc.) has
changed since the prior run — like the batch file's `command` entries
themselves (see "Security & trust model" above), the operator issuing
`--rerun-failed` is trusted to know that the jobs being reused are still valid.

`Richer retry / backoff policies (e.g. exponential backoff, jitter)` has been
implemented: `JobRunner` delegates delay computation to `RetryBackoffPolicy`,
which grows the configured base delay exponentially per attempt (capped at
`RetryBackoffPolicy.DEFAULT_MAX_DELAY`, 5 minutes) and applies full jitter
(a uniform random pick within `[0, cappedDelay]`) to avoid many retrying jobs
synchronizing on the same wall-clock instant. A base delay of `Duration.ZERO`
still means "no backoff" (immediate retry), preserving prior behavior.
