package io.github.izumacha.batch.core;

import io.github.izumacha.batch.model.Batch;
import io.github.izumacha.batch.model.ExecutionResult;
import io.github.izumacha.batch.model.Job;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the execution of a {@link Batch}: validates it into a
 * {@link DependencyGraph}, runs jobs in topological order via a
 * {@link JobRunner}, skips jobs whose dependencies did not succeed, and
 * aggregates everything into an {@link ExecutionResult}.
 */
public final class BatchExecutor {

    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final JobRunner runner;

    public BatchExecutor() {
        this(new JobRunner());
    }

    public BatchExecutor(JobRunner runner) {
        if (runner == null) {
            throw new IllegalArgumentException("runner must not be null");
        }
        this.runner = runner;
    }

    /**
     * Executes the batch. Invalid batches surface a
     * {@link io.github.izumacha.batch.config.ValidationException} before any job
     * runs. Individual job failures are recorded, not thrown.
     */
    public ExecutionResult execute(Batch batch) {
        // Validation happens here; ValidationException propagates to the caller.
        DependencyGraph graph = DependencyGraph.build(batch);

        Instant startedAt = Instant.now();
        String runId = generateRunId(startedAt);

        try {
            List<Job> order = graph.topologicalOrder();
            Map<String, JobResult> results = new LinkedHashMap<>();

            for (Job job : order) {
                String blockingDep = firstBlockingDependency(graph, job, results);
                if (blockingDep != null) {
                    results.put(job.id(), JobResult.skipped(
                            job.id(),
                            "skipped: dependency '" + blockingDep + "' did not succeed"));
                    continue;
                }
                results.put(job.id(), runner.run(job));
            }

            // Results are inserted in execution (topological) order.
            List<JobResult> jobResults = new ArrayList<>(results.values());
            JobStatus overall = jobResults.stream().allMatch(r -> r.status() == JobStatus.SUCCEEDED)
                    ? JobStatus.SUCCEEDED
                    : JobStatus.FAILED;

            Instant finishedAt = Instant.now();
            return new ExecutionResult(
                    runId,
                    batch.name(),
                    overall,
                    startedAt,
                    finishedAt,
                    jobResults);
        } catch (RuntimeException e) {
            throw new BatchExecutionException("unexpected error while executing batch '"
                    + batch.name() + "' (runId=" + runId + ")", e);
        }
    }

    /**
     * Returns the id of the first dependency (in declaration order) that blocks
     * this job from running, or {@code null} if all dependencies succeeded.
     * Because skipped results also block, skips propagate transitively.
     */
    private static String firstBlockingDependency(DependencyGraph graph,
                                                  Job job,
                                                  Map<String, JobResult> results) {
        // job.dependsOn() preserves declaration order; graph.dependenciesOf is a Set.
        for (String dep : job.dependsOn()) {
            JobResult depResult = results.get(dep);
            if (depResult != null && depResult.status().blocksDependents()) {
                return dep;
            }
        }
        return null;
    }

    private static String generateRunId(Instant when) {
        StringBuilder hex = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            hex.append(HEX[RANDOM.nextInt(16)]);
        }
        return RUN_ID_FORMAT.format(when) + "-" + hex;
    }
}
