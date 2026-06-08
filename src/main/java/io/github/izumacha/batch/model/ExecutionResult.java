package io.github.izumacha.batch.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Immutable report describing one execution of a {@link Batch}. This is the
 * unit of state persisted and queried through the execution store.
 *
 * @param runId      unique id of this run
 * @param batchName  name of the batch that was executed
 * @param status     overall outcome: {@code SUCCEEDED} only if every job succeeded, otherwise {@code FAILED}
 * @param startedAt  when the run started
 * @param finishedAt when the run finished
 * @param jobResults per-job results, in execution order
 */
public record ExecutionResult(
        String runId,
        String batchName,
        JobStatus status,
        Instant startedAt,
        Instant finishedAt,
        List<JobResult> jobResults
) {

    public ExecutionResult {
        jobResults = jobResults == null ? List.of() : List.copyOf(jobResults);
    }

    public boolean succeeded() {
        return status == JobStatus.SUCCEEDED;
    }

    public Duration duration() {
        if (startedAt == null || finishedAt == null) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, finishedAt);
    }

    public Optional<JobResult> result(String jobId) {
        return jobResults.stream().filter(r -> r.jobId().equals(jobId)).findFirst();
    }

    public long countByStatus(JobStatus status) {
        return jobResults.stream().filter(r -> r.status() == status).count();
    }
}
