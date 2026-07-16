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
        // yyyyMMdd-HHmmss-<12 hex>（乱数部は同一秒内の衝突を避けるため 12 桁）
        assertTrue(result.runId().matches("\\d{8}-\\d{6}-[0-9a-f]{12}"),
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

    @Test
    void rerunFailedReusesSucceededJobsAndReRunsOnlyFailedOrSkippedOnes() {
        // 前回実行: a は失敗、b は a に依存するためスキップ、c は独立して成功。
        Batch failingBatch = new Batch("mixed", List.of(
                fail("a", List.of()),
                ok("b", List.of("a")),
                ok("c", List.of())));
        ExecutionResult priorResult = executor().execute(failingBatch);
        assertEquals(JobStatus.FAILED, priorResult.status());

        // 今回は a を成功するコマンドへ差し替えた同じバッチ定義で rerun-failed する。
        Batch fixedBatch = new Batch("mixed", List.of(
                ok("a", List.of()),
                ok("b", List.of("a")),
                ok("c", List.of())));
        ExecutionResult rerun = executor().execute(fixedBatch, priorResult);

        // バッチ全体は今回はすべて成功する。
        assertEquals(JobStatus.SUCCEEDED, rerun.status());
        // a は前回 FAILED だったので再実行され、今回は SUCCEEDED になる。
        assertEquals(JobStatus.SUCCEEDED, rerun.result("a").orElseThrow().status());
        // b は前回 SKIPPED だったので再実行され、a が今回成功したため SUCCEEDED になる。
        assertEquals(JobStatus.SUCCEEDED, rerun.result("b").orElseThrow().status());
        // c は前回 SUCCEEDED だったので再実行されず、前回の結果（同じ startedAt）がそのまま流用される。
        JobResult c = rerun.result("c").orElseThrow();
        assertEquals(JobStatus.SUCCEEDED, c.status());
        assertEquals(priorResult.result("c").orElseThrow().startedAt(), c.startedAt(),
                "c should be reused verbatim from the prior result, not re-executed");
    }

    @Test
    void rerunFailedRunsNewJobsNotPresentInPriorResultNormally() {
        // 前回実行: a のみ成功。
        Batch priorBatch = new Batch("growing", List.of(ok("a", List.of())));
        ExecutionResult priorResult = executor().execute(priorBatch);
        assertEquals(JobStatus.SUCCEEDED, priorResult.status());

        // 今回は前回に存在しなかった新規ジョブ b を a に依存させて追加する。
        Batch grownBatch = new Batch("growing", List.of(
                ok("a", List.of()),
                ok("b", List.of("a"))));
        ExecutionResult rerun = executor().execute(grownBatch, priorResult);

        // 新規ジョブ b は前回結果に存在しないため通常どおり実行され成功する。
        assertEquals(JobStatus.SUCCEEDED, rerun.status());
        assertEquals(JobStatus.SUCCEEDED, rerun.result("b").orElseThrow().status());
        // a は前回 SUCCEEDED だったため再実行されず、前回の結果がそのまま流用される。
        assertEquals(priorResult.result("a").orElseThrow().startedAt(),
                rerun.result("a").orElseThrow().startedAt(),
                "a should be reused verbatim from the prior result, not re-executed");
    }

    @Test
    void rerunFailedRejectsPriorResultFromADifferentBatch() {
        // 「other」という名前の別バッチの前回結果を用意する（たまたま同じ job id "a" を含む）。
        Batch otherBatch = new Batch("other", List.of(ok("a", List.of())));
        ExecutionResult otherPriorResult = executor().execute(otherBatch);
        assertEquals(JobStatus.SUCCEEDED, otherPriorResult.status());

        // 名前が異なる「mine」というバッチに対して、無関係な前回結果を rerun-failed で渡す。
        Batch myBatch = new Batch("mine", List.of(ok("a", List.of())));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> executor().execute(myBatch, otherPriorResult));
        // 取り違えを検出したことがメッセージから分かるはず（両方のバッチ名を含む）。
        assertTrue(ex.getMessage().contains("other") && ex.getMessage().contains("mine"),
                ex.getMessage());
    }

    @Test
    void executeWithoutPriorResultBehavesLikeNormalExecute() {
        // priorResult に null を渡した場合は通常実行（execute(batch) と同じ挙動）になる。
        Batch batch = new Batch("plain", List.of(ok("a", List.of())));
        ExecutionResult result = executor().execute(batch, null);
        assertEquals(JobStatus.SUCCEEDED, result.status());
        assertEquals(JobStatus.SUCCEEDED, result.result("a").orElseThrow().status());
    }
}
