# batch-scheduler

A small, robust MVP batch execution manager. You define a set of jobs in a YAML
file, and the tool runs them honoring a dependency DAG (directed acyclic graph),
retrying and timing out individual jobs as configured, skipping the dependents of
anything that fails, and persisting a JSON record of every run so its history and
outcome can be inspected afterwards.

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
- `--help`, `--version` — standard help and version output (also available on each
  sub-command).

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
| `2`  | Validation error — the batch is structurally invalid (bad DAG, missing dependency, duplicate id, empty command, cycle). |
| `3`  | Configuration / IO error (file missing or unparseable) or a usage error. |

## Design

See [docs/DESIGN.md](docs/DESIGN.md) for the architecture, key design decisions,
and planned future extensions.
