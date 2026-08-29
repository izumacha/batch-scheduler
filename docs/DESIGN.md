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
- `state` — persistence of run reports (`ExecutionStore`, its
  `JsonExecutionStore` implementation, and `Durability`, which owns every
  `fsync` on the write path — see "Record durability" below).
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
error. The `--rerun-failed` lookup is likewise resolved *before* the state
directory is prepared (the store reads a run id against a not-yet-created
directory as simply "not found"), so an unknown or malformed run id (exit
code 3) also leaves no `--state-dir` tree behind: pre-run failures leave no
side effects in the state directory. It then prepares the state directory
**before** executing any job: the
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
  30.0` is rejected — these fields accept integers only. The same reasoning
  covers **explicit nulls**: a key written with no value (a bare
  `timeoutSeconds:` — YAML parses it as `null`) would by default be silently
  coerced to `0`, again meaning *no timeout at all*, so
  `FAIL_ON_NULL_FOR_PRIMITIVES` turns it into a configuration error (exit
  code 3) instead. *Omitting* the key entirely remains the documented way to
  get the default of `0`; a small deserializer wrapper in `BatchConfigLoader`
  keeps that omitted-key path working (Jackson's record deserialization would
  otherwise treat an omitted constructor argument like a null and reject it
  too).
- **Single-document YAML only.** A config file containing multiple YAML documents
  (`---` separators) is rejected as a configuration error (exit code 3) instead of
  every document after the first being silently dropped (silent job loss).
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
  Known limitation: `JobResult.NO_EXIT_CODE` (`-1`) is the sentinel for "no exit
  code was obtained" (start failure, timeout). On POSIX systems a process exit
  code is always `0..255`, so the sentinel is unambiguous there — but on Windows
  a process can genuinely exit with `-1`, which collides with the sentinel. The
  persisted `JobResult` JSON keeps the raw `-1` either way (the schema is
  unchanged); only the human-readable summary treats the two alike, rendering
  the collision case as a plain `exit -1` failure rather than inventing a new
  schema field to disambiguate — acceptable for this MVP.
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
  document-size limit (`MAX_CONFIG_BYTES`, 4 MiB), a nesting-depth limit, and
  recursive keys disabled. Oversized files are rejected before being read whole.
  YAML anchors (`&name`), aliases (`*name`), and merge keys (`<<`) are rejected
  outright with a config error (exit code 3) rather than merely bounded by an
  alias count: the Jackson YAML bridge drives SnakeYAML's raw event stream
  without running its composer (the stage that resolves anchors and aliases),
  so an alias in value position would silently degrade into the literal string
  of the alias name (the job would execute the wrong command) and a merge key
  would surface as an unknown `<<` field that lenient unknown-field handling
  silently drops (timeouts/retries silently lost while `validate` still reports
  OK). Rejecting these features up front is the fail-closed alternative to
  silently corrupting them. A pre-pass alias-count limit is still enforced
  first, purely so that "billion laughs" alias-expansion bombs are cut off as a
  resource-limit violation before the feature scan ever walks them.
- **Bounded output capture.** Each job's combined output is drained on a
  dedicated thread (so a full pipe never blocks the child) and only a bounded
  tail is retained; an individual line is capped so a single runaway line cannot
  exhaust memory.
- **Display sanitization of untrusted job output.** Although the batch file is
  trusted, the *runtime output* a job produces (and values read back from state
  files, such as run ids) is untrusted data. Before any of it is echoed into
  the run-summary tables, `CliFormat` collapses whitespace and strips the
  remaining non-printable control characters (C0 controls such as ESC/BEL, DEL,
  C1 controls such as CSI, and Unicode format characters — category `Cf` —
  including the bidirectional-text controls U+202A–U+202E and U+2066–U+2069),
  so a failed job's captured output cannot inject terminal escape sequences
  into the operator's terminal (title spoofing, hidden text, cursor
  manipulation) or visually reorder the summary-table text via bidi override
  characters. `CliFormat.shortMessage` is the single
  choke point for table cells, and `ListCommand` runs run ids through the same
  `stripControlChars` helper.
- **ASCII-only CLI diagnostics.** Every message the tool itself writes to
  stdout/stderr is plain ASCII English. `System.out`/`System.err` encode with
  the JVM's `stdout.encoding`/`stderr.encoding`, which fall back to the
  platform's native charset — US-ASCII whenever `LANG` is unset (the default in
  JDK base images and CI containers). A non-ASCII diagnostic is therefore
  flattened to `?` there, destroying exactly the information the operator needs:
  the unsupported-YAML-feature error used to print
  `error: YAML ????? &a ??????: bomb.yaml (???...)`, naming neither the offending
  feature nor the remedy. Pinning the output streams to UTF-8 instead would trade
  this for mojibake on genuinely non-UTF-8 consoles, so the tool keeps its *own*
  wording locale-independent and leaves externally-sourced strings (captured job
  output, batch names) to the platform's encoding.
  This covers the *markers the tool inserts into* otherwise-external text as
  well, because a lone `?` there is indistinguishable from genuinely corrupted
  output: the table-cell truncation marker (`CliFormat.TRUNCATION_MARK`) and the
  over-long-line marker the output collector appends
  (`JobRunner.OutputCollector.TRUNCATION_MARK`) are both ASCII `...` rather than
  the ellipsis `U+2026` they used to be. Three tests guard against a regression —
  `BatchConfigLoaderTest#unsupportedFeatureMessageIsAsciiOnlySoItSurvivesAnyLocale`,
  `CliFormatTest#shortMessage_truncationMarkIsAsciiOnlySoItSurvivesAnyLocale`, and
  `JobRunnerTest#longLineTruncationMarkIsAsciiOnlySoItSurvivesAnyLocale`.
  Since the table marker is 3 characters wide rather than 1, `shortMessage`
  reserves room for it so a truncated cell still fits the column budget, and
  omits the marker entirely when the budget is too small to hold it.
- **Pinned build toolchain.** The Maven Wrapper (`./mvnw`) fetches the Maven
  distribution at build time, which makes that download part of the build's trust
  chain: whatever it returns is executed with the developer's (or CI runner's)
  privileges. HTTPS to `repo.maven.apache.org` protects the transport but not the
  repository itself, so `.mvn/wrapper/maven-wrapper.properties` also pins
  `distributionSha256Sum` and the wrapper refuses to run a distribution whose hash
  does not match. The pinned value was derived by verifying the zip against the
  SHA-512 that Apache publishes alongside it and hashing that same verified file.
  The wrapper validates whichever archive it downloaded, and it silently switches
  from the `.zip` to the `.tar.gz` when `unzip` is missing — that archive hashes
  differently, so the pin turns a missing `unzip` into a
  "your Maven distribution might be compromised" failure. Both READMEs therefore
  list `unzip` as a requirement rather than leaving that failure unexplained.
  Bumping Maven means updating `distributionUrl` and `distributionSha256Sum`
  together.
- **Iterative graph algorithms.** Validation, cycle detection, and topological
  sort are iterative, so a deeply-nested or very long dependency chain cannot
  overflow the call stack.
- **State-directory safety.** Run ids are validated to reject path separators and
  `..` so a record can never be written or read outside the state directory.
  Writes go through a temp file and an atomic move; reads do not follow symlinks.
  This now also covers the base directory itself, not just individual
  `<runId>.json` files: `ensureBaseDirectory()` refuses to operate if `--state-dir`
  is itself a symlink (rather than following it via `createDirectories`), and
  `findAll`/`findRecent`/`findById` treat a symlinked base directory the same as
  a missing one, so a pre-planted symlink cannot redirect reads or writes
  elsewhere. `save()` additionally guards against the base directory being
  swapped for a symlink *during* the write itself (as opposed to being
  pre-planted before the tool ever runs): it records the base directory's
  resolved real path before writing, and after the temp-file-then-atomic-move
  sequence completes, verifies the file actually landed under that same real
  directory. A mismatch means the base directory was swapped mid-write, so the
  misdirected file is deleted and the save is rejected. This "verify after the
  fact, roll back on mismatch" check covers the whole span from the moment the
  real path is captured through the end of the write, in one pass, rather than
  trying to re-check before every individual filesystem call the sequence
  makes (which, with path-based `java.nio.file` APIs that re-resolve the base
  directory on every call, would still leave a gap after the last such check
  and before the next). It does not reach backward into the separate,
  already-noted check-then-act window inside `ensureBaseDirectory()` itself
  (between its own symlink check and `createDirectories` completing) — a swap
  timed into exactly that earlier window is adopted as the trusted real path
  rather than caught, since resolving a symlink doesn't distinguish "was
  always there" from "was just swapped in". That earlier window is not new or
  widened by this mechanism; closing it fully would require fd-relative
  (`SecureDirectoryStream`/openat-equivalent) directory operations throughout,
  which is out of scope for this MVP given the trust model above.

  The read path (`findAll`/`findRecent`/`findById`, which all funnel through
  the shared `tryRead` helper) has the same shape of gap: `NOFOLLOW_LINKS` on
  the individual `<runId>.json` file only guards that file's own final path
  component, so if the base directory itself is swapped for a symlink
  *during* a read — after the initial symlink/existence check but before the
  read completes — the intermediate resolution of the file's parent
  directory would silently follow the swap and serve content from an
  attacker-controlled location. Unlike `save()`, this is *not* closed by
  reading first and comparing real paths afterward ("verify after the
  fact"): a write's post-write check inspects a file it just physically
  created, which cannot un-happen, so a real-path mismatch reliably proves
  the write landed in the wrong place. A read's post-read check instead
  re-resolves the file from scratch, independently of the already-completed
  read — so the base directory could be swapped to a symlink for the read
  and swapped back to the legitimate directory before the check runs, which
  would then resolve cleanly and wrongly accept bytes that were never read
  from there. Because a read's target already exists (unlike a write's,
  which doesn't exist until the write happens), this class resolves the
  file's real path *first*, verifies it under the base directory's real path
  (captured once per call), and only then opens and reads from that
  already-resolved real path — which by definition contains no symlink
  components — rather than from the original, possibly-symlinked path. No
  later swap of the base directory can redirect that open, because the open
  no longer mentions the base directory at all; `NOFOLLOW_LINKS` still
  guards the narrow window between the resolve and the open in case the
  resolved file itself is replaced by a symlink in that instant. The
  residual window is the same one already accepted elsewhere in this class
  (see `ensureBaseDirectory()`, above): an attacker who can rewrite the
  *already-resolved, real* target location itself — as opposed to
  redirecting a symlink the tool follows — is outside this defense's threat
  model. The durability flushes below add the first opens of state files that
  happen *after* something else created them, and `open(2)` on a FIFO blocks
  until a peer appears with no timeout or `O_NONBLOCK` reachable from Java —
  so a pipe left at a record's name by a co-resident process would wedge the
  CLI after the batch had already run, with no way to tell whether the run was
  recorded. `Durability` therefore checks the file type before opening and
  degrades to a warning when the path is not the regular file (or directory)
  the step expects. `NOFOLLOW_LINKS` does not cover this: a FIFO is not a
  symlink. The check leaves a narrow swap window between the test and the open
  — Java offers no atomic "open only if regular" — but a pipe already in place
  is refused deterministically.
- **Record durability.** The temp-file-plus-rename above makes a save *atomic*
  (a concurrent reader never sees a half-written file), which is a different
  property from surviving a power loss. The record's contents and the rename
  that publishes them are written back from the page cache independently, so a
  crash before write-back can lose either one alone: a durable rename with
  non-durable contents leaves a `<runId>.json` that reads back empty or garbled
  (and is then skipped by `tryRead`, so the run silently disappears from
  `list`), while durable contents with a non-durable rename lets the record
  vanish or revert to whatever previously held that name. Either outcome costs
  more than a missing history row, because `run --rerun-failed <runId>` reads
  that record back to decide which jobs already succeeded — without it the
  operator must re-run the whole batch, re-executing jobs that had already
  succeeded, which is precisely what `--rerun-failed` exists to avoid.
  `Durability` therefore flushes four things — one `Durability.Step` each, so
  that adding a flush point forces a decision about its target and its failure
  policy rather than inheriting someone else's: each directory level
  `ensureBaseDirectory()` newly creates (syncing each level's *parent*, since
  that is what holds the entry), the temp file's contents before the rename,
  the published file's contents again when the rename had to fall back to a
  non-atomic copy-and-delete (a separate step because it re-flushes bytes the
  fallback wrote at a *new* location, and carries its own warning budget and
  operator wording), and the base directory after the rename — the last one
  only once
  `verifyWroteUnderExpectedBase` has passed, so a *rename* the symlink check is
  about to reject is never committed first. This ordering deliberately covers
  the directory entry only: the record's own bytes are flushed before the check
  runs, so a write misdirected by a mid-sequence symlink swap may already have
  reached the attacker-controlled directory. Withholding the content flush
  would not change that (the bytes are written either way, flushed or not);
  what the check does about it is unlink the misdirected file via
  `deleteQuietly(target)` — a small helper that returns the cleanup failure
  instead of throwing it, so an unlink the attacker's directory refuses is
  attached with `addSuppressed` rather than replacing the rejection itself —
  and reject the save. Note also that the
  non-atomic `Files.move` fallback re-syncs the *destination* rather than
  relying on the earlier sync of the temp file: that fallback may internally
  copy-and-delete, in which case the synced temp file is unlinked and the
  destination holds fresh, unflushed pages — syncing only the directory there
  would recreate the exact "durable entry, non-durable contents" failure this
  section exists to prevent. **What a failure does depends on what failed.**
  A directory flush is the step that legitimately cannot run at all (Windows
  does not allow opening a directory as a channel) and its failure costs the
  directory entry rather than the record's bytes, so it logs once and
  continues: failing a save because the platform cannot `fsync` would report a
  run whose record is on disk as "failed to save execution result", telling
  the operator the opposite of what happened. A regular-file flush is
  different — a failed `fsync` there means the record's own bytes did not
  reach the disk, because with delayed allocation the kernel can report
  `ENOSPC`/`EIO` at exactly that point and drop the dirty pages — so those
  steps propagate, `save()` wraps the error as it already does for any other
  write failure, and `RunCommand` reports it (its handler already names a full
  disk as the expected cause). What propagates is only a failure of the
  *flush*: a failure to *open* — including an unchecked one, which is wrapped
  so that it lands on the same side — means the sync could not be attempted
  rather than that a write was lost, and the bytes were written and the stream
  closed before it ran, so it warns even on a record-content step. Failing a
  save because an antivirus held the file open would be the same inversion in
  the other direction. That rule covers the non-atomic fallback too (which, on the default
  filesystem provider, `save()` cannot currently reach: the temp file and the
  target sit in the same directory, so `rename()` never fails with `EXDEV` and
  `ATOMIC_MOVE` therefore never degrades. The fallback branch predates this
  change and is kept consistent rather than left as the one publish path
  without durability, so that it does not become a hole the moment it becomes
  reachable):
  when `Files.move` copies rather than renames, the destination is a freshly
  allocated file whose bytes the earlier temp-file flush never touched, so its
  re-sync is a record-content flush like any other and fails the save the same
  way. Deriving the rule from *what is being flushed*, rather than from
  "before or after publication", is what makes that case fall out correctly
  instead of needing to be remembered. One asymmetry is accepted knowingly on
  that path: when the temp-file flush fails the `finally` deletes the temp file,
  so the reported failure matches the disk, but when the *fallback's*
  destination flush fails the record has already been published and is left in
  place. The save is still reported as failed, because an `fsync` error means
  the kernel hit a write error — the page-cache copy can read back fine while
  the on-disk copy is not — and a conservative "could not confirm this was
  saved" is recoverable, whereas a false success is not. Deleting the published
  record instead would be worse: the move already overwrote whatever held that
  name, so removing it loses both copies. When a record-content flush is
  *degraded* rather than propagated (it could not be opened at all), the
  rename's directory sync is skipped too. Committing it alone would build the
  worst of the two crash outcomes on purpose — a durable directory entry
  pointing at contents that were never flushed, which comes back garbled, is
  skipped by `tryRead`, and so vanishes from `list` while `--rerun-failed`
  reports it missing. Leaving both halves uncommitted costs at most the whole
  record, which is the outcome an operator can actually recognise.
  The split is by *which half failed*,
  never by checked versus unchecked: an unchecked failure raised while opening
  is wrapped and stays best-effort, but one raised by `force`/`close` reached
  the flush and fails a record-content step like any other flush error.
  Routing on the exception's type instead would let a provider that throws
  unchecked from `force` publish bytes that never reached the disk and still
  report success — the very inversion this section exists to prevent, which is
  why `sync` handles both kinds in a single `catch` rather than two.
  The accepted cost of the rule is
  a state directory on a mount whose `fsync(2)` is not implemented for regular
  files (some `vboxsf`/9p/FUSE setups return `EINVAL`/`ENOSYS` there): every
  `run` will report a persistence failure rather than silently accept writes it
  cannot confirm. Be clear about the blast radius — the record is not merely
  reported as unconfirmed, it is **discarded**: the flush throws before the
  rename, the `finally` deletes the temp file, and `run` exits 3 with no
  `<runId>.json` at all, so `list` stays empty and `--rerun-failed` is
  unusable on that mount, on every run rather than once. That is deliberate.
  `IOException` carries no way to tell "fsync unsupported" from
  "fsync reported ENOSPC", and between the two mistakes a loud, immediate
  failure the operator sees on the very first run is preferable to records that
  look saved and are not. Directory syncs keep their escape hatch because there
  the platform gap is the *expected* case (Windows), not an anomaly. Successful steps are logged at
  `FINE` — after the channel is closed, since deferred write errors on
  networked filesystems surface from `close()` and a record logged before that
  would claim a flush that then failed — since an `fsync` otherwise leaves no
  evidence that it happened. A skipped step warns once per step, per store.
  For directories that warning distinguishes a failure to *open* (a platform
  gap) from a failure of the *flush* on a directory that opened fine (a real
  error), because the warn-once budget means that sentence is the only notice
  the operator will get, and wording a genuine failure as a platform
  limitation would tell them to ignore it. Two platform gaps remain and are
  inherent rather than accepted trade-offs: Windows does not allow opening a
  directory as a channel, so the directory syncs are skipped there, and macOS
  `fsync(2)` pushes only to the drive's own cache rather than issuing
  `F_FULLFSYNC`.

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
