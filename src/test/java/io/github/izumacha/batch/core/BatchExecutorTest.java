package io.github.izumacha.batch.core;

import io.github.izumacha.batch.config.ValidationException;
import io.github.izumacha.batch.model.Batch;
import io.github.izumacha.batch.model.ExecutionResult;
import io.github.izumacha.batch.model.Job;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchExecutorTest {

    private static BatchExecutor executor() {
        // Fast runner: no retry backoff.
        return new BatchExecutor(new JobRunner(50, Duration.ZERO, false));
    }

    private static Job job(String id, List<String> command, List<String> dependsOn) {
        return new Job(id, null, command, dependsOn, 0, 0, Map.of(), null);
    }

    private static Job ok(String id, List<String> dependsOn) {
        return job(id, List.of("sh", "-c", "exit 0"), dependsOn);
    }

    private static Job fail(String id, List<String> dependsOn) {
        return job(id, List.of("sh", "-c", "exit 1"), dependsOn);
    }

    @Test
    void allSuccessBatchSucceedsWithResultsInTopoOrder() {
        Batch batch = new Batch("happy", List.of(
                ok("a", List.of()),
                ok("b", List.of("a")),
                ok("c", List.of("b"))));
        ExecutionResult result = executor().execute(batch);

        assertEquals(JobStatus.SUCCEEDED, result.status());
        assertTrue(result.succeeded());
        assertEquals(List.of("a", "b", "c"),
                result.jobResults().stream().map(JobResult::jobId).toList());
        assertTrue(result.jobResults().stream().allMatch(JobResult::succeeded));
        assertNotNull(result.runId());
        assertEquals("happy", result.batchName());
    }

    @Test
    void failingJobSkipsDependentsButRunsIndependentJobs() {
        // a fails; b depends on a (skipped); c is independent and succeeds.
        Batch batch = new Batch("mixed", List.of(
                fail("a", List.of()),
                ok("b", List.of("a")),
                ok("c", List.of())));
        ExecutionResult result = executor().execute(batch);

        assertEquals(JobStatus.FAILED, result.status());

        JobResult a = result.result("a").orElseThrow();
        JobResult b = result.result("b").orElseThrow();
        JobResult c = result.result("c").orElseThrow();

        assertEquals(JobStatus.FAILED, a.status());
        assertEquals(JobStatus.SKIPPED, b.status());
        assertTrue(b.message().contains("dependency 'a'"), b.message());
        assertEquals(JobStatus.SUCCEEDED, c.status());
    }

    @Test
    void skipsPropagateTransitively() {
        // a fails -> b skipped -> c skipped (c depends on b).
        Batch batch = new Batch("chain", List.of(
                fail("a", List.of()),
                ok("b", List.of("a")),
                ok("c", List.of("b"))));
        ExecutionResult result = executor().execute(batch);

        assertEquals(JobStatus.FAILED, result.status());
        assertEquals(JobStatus.FAILED, result.result("a").orElseThrow().status());
        assertEquals(JobStatus.SKIPPED, result.result("b").orElseThrow().status());
        assertEquals(JobStatus.SKIPPED, result.result("c").orElseThrow().status());
        // c is skipped because its direct dependency 'b' did not succeed.
        assertTrue(result.result("c").orElseThrow().message().contains("dependency 'b'"),
                result.result("c").orElseThrow().message());
    }

    @Test
    void runIdHasExpectedShape() {
        Batch batch = new Batch("idshape", List.of(ok("a", List.of())));
        ExecutionResult result = executor().execute(batch);
        assertNotNull(result.runId());
        // yyyyMMdd-HHmmss-<6 hex>
        assertTrue(result.runId().matches("\\d{8}-\\d{6}-[0-9a-f]{6}"),
                "unexpected runId: " + result.runId());
    }

    @Test
    void invalidBatchThrowsValidationExceptionBeforeRunning() {
        Batch batch = new Batch("invalid", List.of(
                job("a", List.of("sh", "-c", "exit 0"), List.of("ghost"))));
        assertThrows(ValidationException.class, () -> executor().execute(batch));
    }

    @Test
    void timingFieldsArePopulated() {
        Batch batch = new Batch("timing", List.of(ok("a", List.of())));
        ExecutionResult result = executor().execute(batch);
        assertNotNull(result.startedAt());
        assertNotNull(result.finishedAt());
        assertTrue(result.duration().toNanos() >= 0);
    }
}
