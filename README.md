# batch-scheduler

**English** | [日本語](README.ja.md)

A small, robust MVP batch execution manager. You define a set of jobs in a YAML
file, and the tool runs them honoring a dependency DAG (directed acyclic graph),
retrying and timing out individual jobs as configured, skipping the dependents of
anything that fails, and persisting a JSON record of every run so its history and
outcome can be inspected afterwards.

## Demo

The representative flow with `examples/etl.yaml` (extract → transform → load →
notify): reading the batch definition, validating the dependency DAG, running
the jobs in topological order, and listing the persisted run history.

![examples/etl.yaml のバッチ定義を表示し、DAG を検証してからトポロジカル順に実行し、実行履歴を一覧表示する端末操作のデモ](docs/demo.gif)

## Features

- **DAG dependencies** — jobs declare `dependsOn` and run in a validated
  topological order.
- **Retries** — a job can be retried a configurable number of times after its
  first failure.
- **Per-job timeout** — each attempt can be bounded by a timeout in seconds.
- **Dependent-skipping on failure** — when a job ends `FAILED` (or is itself
  skipped), every job that transitively depends on it is marked `SKIPPED` and the
  overall run is reported as `FAILED`.
- **JSON-persisted run history** — each run is saved as a JSON document so you can
  list past runs and their outcomes.

## Requirements

- Java 21
- Maven 3.9+

## Build

```sh
mvn package
```

This produces the executable shaded jar at `target/batch-scheduler.jar`.

## Usage

```sh
# Run a batch (executes jobs and records the run)
java -jar target/batch-scheduler.jar run examples/etl.yaml

# Validate a batch without running it (checks the DAG, prints execution order)
java -jar target/batch-scheduler.jar validate examples/etl.yaml

# List previously recorded runs (most recent first)
java -jar target/batch-scheduler.jar list
```

Useful options:

- `run --state-dir <dir>` / `list --state-dir <dir>` — directory where run state
  is stored (default: `.batch-state`).
- `run -q` / `run --quiet` — suppress the per-job summary table.
- `run --rerun-failed <runId>` — reuse `SUCCEEDED` job results from a prior run
  (looked up by run id under `--state-dir`) and only (re-)execute jobs that were
  `FAILED` or `SKIPPED` in it, or that did not exist in it yet. See
  [Rerunning only the failed jobs](#rerunning-only-the-failed-jobs).
- `list -n <n>` / `list --limit <n>` — show at most `n` of the most recent runs
  (default: `20`); pass `0` or a negative number to list all.
- `--help`, `--version` — standard help and version output (also available on each
  sub-command).

### Rerunning only the failed jobs

After a `run` ends with some jobs `FAILED` or `SKIPPED`, you can fix the
underlying issue and rerun just those jobs (plus any brand-new jobs added to
the batch file since) without redoing the ones that already succeeded:

```sh
# Note the "Run ID" printed by the failed run (or read it from `list`).
java -jar target/batch-scheduler.jar run examples/etl.yaml --rerun-failed 20260101-120000-abc123def456
```

Jobs that were `SUCCEEDED` in that prior run are reused verbatim (their
original result is copied into the new run, not re-executed); jobs that were
`FAILED` or `SKIPPED`, and any job present in the batch file but absent from
the prior run, run normally. The prior run id is looked up in the same
`--state-dir`, so it must still be present there, and it must belong to a run
of a batch with the same `name` — a run id from a different batch is rejected
(exit `3`) rather than silently borrowing an unrelated job's result. This is a
rerun, not a resume: it still produces a brand-new run id and a new persisted
record covering every job in the batch.

`--rerun-failed` does not detect that a `SUCCEEDED` job's own definition
(its `command`, `dependsOn`, etc.) changed since the prior run — like the rest
of the batch file, you are trusted to only skip jobs you know are still valid.

## YAML schema

A batch file has a `name` and a list of `jobs`. Each job is defined by the
following fields:

| Field            | Type              | Required | Default        | Description |
|------------------|-------------------|----------|----------------|-------------|
| `id`             | string            | yes      | —              | Unique identifier of the job. |
| `name`           | string            | no       | the `id`       | Human-friendly label. |
| `command`        | array of strings  | yes      | —              | The command to run, as an **argv array** (program followed by its arguments). |
| `dependsOn`      | array of strings  | no       | `[]`           | Ids of jobs that must succeed before this one runs. |
| `retries`        | integer (>= 0)    | no       | `0`            | Number of *additional* attempts after the first failure. |
| `timeoutSeconds` | integer (>= 0)    | no       | `0` (no limit) | Per-attempt timeout in seconds. |
| `env`            | map string→string | no       | `{}`           | Extra environment variables for the spawned process. |
| `workingDir`     | string            | no       | launcher's dir | Working directory for the process. |

> Note: `command` is an **argv array**, not a shell string. To use shell features
> (pipes, globbing, `&&`, ...), invoke a shell explicitly, e.g.
> `["sh", "-c", "echo hi && ls"]`.

### Sample

```yaml
name: etl

jobs:
  - id: extract
    name: Extract raw data
    command: ["sh", "-c", "echo extract"]

  - id: transform
    name: Transform extracted data
    command: ["sh", "-c", "echo transform"]
    dependsOn: [extract]
    retries: 2
    timeoutSeconds: 30

  - id: load
    command: ["sh", "-c", "echo load"]
    dependsOn: [transform]
    workingDir: /tmp
    env:
      STAGE: load
```

## Exit codes

| Code | Meaning |
|------|---------|
| `0`  | Success — the operation completed (and, for `run`, the batch `SUCCEEDED`). |
| `1`  | The batch ran to completion but ended in a `FAILED` state. |
| `2`  | Validation error — the batch is structurally invalid (bad DAG, missing dependency, duplicate id, empty or blank command token, cycle). |
| `3`  | Configuration / IO error (file missing or unparseable, or the `--state-dir` cannot be prepared before the run) or a usage error. |

Notes:

- `run` validates the batch (exit `2`) before preparing the state directory
  (exit `3`), so an invalid batch never creates the `--state-dir` tree as a
  side effect.
- If persisting the run record fails *after* execution (e.g. the state
  directory became unwritable mid-run), the batch outcome wins: after a failed
  batch the exit code is still `1`; only after a successful batch is the
  persistence failure reported as exit `3`.
- `timeoutSeconds` and `retries` accept integers only: any float literal —
  including whole-number floats such as `30.0` — is rejected as a
  configuration error (exit `3`) instead of being truncated.

## Security & trust model

The batch file is **trusted input**: like a `Makefile` or CI pipeline, it can run
arbitrary commands by design, so don't run batch files from untrusted sources.
Within that model the tool still guards against resource exhaustion (bounded YAML
parsing, bounded job-output capture, iterative graph algorithms) and against
escaping the state directory (run-id validation, no symlink following). See
[docs/DESIGN.md](docs/DESIGN.md#security--trust-model) for details.

## Design

See [docs/DESIGN.md](docs/DESIGN.md) for the architecture, key design decisions,
and planned future extensions.
