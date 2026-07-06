package io.github.izumacha.batch.core;

import io.github.izumacha.batch.model.Job;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobRunnerTest {

    /** No retry backoff so retry tests stay fast. */
    private static JobRunner fastRunner() {
        return new JobRunner(50, Duration.ZERO, false);
    }

    private static Job job(String id, List<String> command, int retries, long timeoutSeconds) {
        return new Job(id, null, command, List.of(), retries, timeoutSeconds, Map.of(), null);
    }

    @Test
    void successfulCommandReportsSucceeded() {
        JobResult r = fastRunner().run(job("ok", List.of("sh", "-c", "exit 0"), 0, 0));
        assertEquals(JobStatus.SUCCEEDED, r.status());
        assertEquals(0, r.exitCode());
        assertEquals(1, r.attempts());
        assertTrue(r.succeeded());
        assertEquals("exit 0", r.message());
        assertNotNull(r.startedAt());
        assertNotNull(r.finishedAt());
    }

    @Test
    void nonZeroExitReportsFailedWithExitCode() {
        JobResult r = fastRunner().run(job("bad", List.of("sh", "-c", "exit 3"), 0, 0));
        assertEquals(JobStatus.FAILED, r.status());
        assertEquals(3, r.exitCode());
        assertEquals(1, r.attempts());
        assertTrue(r.message().contains("exit 3"), r.message());
    }

    @Test
    void retriesAreExhaustedAndAttemptCountIsCorrect() {
        // 2 retries => 3 total attempts, all failing.
        JobResult r = fastRunner().run(job("retry", List.of("sh", "-c", "exit 2"), 2, 0));
        assertEquals(JobStatus.FAILED, r.status());
        assertEquals(2, r.exitCode());
        assertEquals(3, r.attempts());
        assertTrue(r.message().contains("exit 2"), r.message());
        assertTrue(r.message().contains("3 attempts"), r.message());
    }

    @Test
    void timeoutReportsFailedWithTimeoutMessage() {
        JobResult r = fastRunner().run(job("slow", List.of("sh", "-c", "sleep 5"), 0, 1));
        assertEquals(JobStatus.FAILED, r.status());
        assertEquals(JobResult.NO_EXIT_CODE, r.exitCode());
        assertTrue(r.message().contains("timed out after 1s"), r.message());
        // Must not have actually waited the full 5 seconds.
        assertTrue(r.duration().compareTo(Duration.ofSeconds(4)) < 0,
                "expected to be killed well before 5s, was " + r.duration());
    }

    @Test
    void outputIsCapturedAndTailedIntoMessageOnFailure() {
        // Print a line then fail; the tail should appear in the message.
        JobResult r = fastRunner().run(
                job("out", List.of("sh", "-c", "echo boom-marker; exit 1"), 0, 0));
        assertEquals(JobStatus.FAILED, r.status());
        assertTrue(r.message().contains("boom-marker"), r.message());
    }

    @Test
    void onlyLastLinesAreKeptForOutputTail() {
        JobRunner runner = new JobRunner(2, Duration.ZERO, false);
        // Emit 5 lines, fail; only the last 2 are retained, and the message
        // shows the first retained line (line4).
        JobResult r = runner.run(job("many",
                List.of("sh", "-c", "for i in 1 2 3 4 5; do echo line$i; done; exit 1"), 0, 0));
        assertEquals(JobStatus.FAILED, r.status());
        assertTrue(r.message().contains("line4"), r.message());
        assertTrue(!r.message().contains("line1"), r.message());
    }

    @Test
    void failureToStartIsRecordedNotThrown() {
        JobResult r = fastRunner().run(
                job("nope", List.of("this-command-definitely-does-not-exist-12345"), 0, 0));
        assertEquals(JobStatus.FAILED, r.status());
        assertEquals(JobResult.NO_EXIT_CODE, r.exitCode());
        assertNotNull(r.message());
        assertTrue(r.message().toLowerCase().contains("start") || !r.message().isBlank(), r.message());
    }

    @Test
    void hugeSingleOutputLineIsBoundedNotLoadedWhole() {
        // A process emitting one enormous line must not exhaust memory: the
        // captured tail is bounded and the job still completes normally.
        Job j = job("flood", List.of("sh", "-c",
                "head -c 5000000 /dev/zero | tr '\\0' 'x'; echo; exit 0"), 0, 0);
        JobResult r = fastRunner().run(j);
        assertEquals(JobStatus.SUCCEEDED, r.status());
        // Message stays small despite ~5 MB of output on a single line.
        assertTrue(r.message().length() < 64 * 1024, "message length=" + r.message().length());
    }

    @Test
    void invalidEnvKeyIsReportedAsFailureNotThrown() {
        // A key containing '=' is rejected by ProcessBuilder; it must surface as
        // a FAILED result rather than crashing the batch.
        Job j = new Job("badenv", null, List.of("sh", "-c", "exit 0"),
                List.of(), 0, 0, Map.of("A=B", "x"), null);
        JobResult r = fastRunner().run(j);
        assertEquals(JobStatus.FAILED, r.status());
        assertEquals(JobResult.NO_EXIT_CODE, r.exitCode());
        assertNotNull(r.message());
    }

    @Test
    void environmentVariableIsPassedToProcess() {
        Job j = new Job("env", null, List.of("sh", "-c", "test \"$MY_VAR\" = hello"),
                List.of(), 0, 0, Map.of("MY_VAR", "hello"), null);
        JobResult r = fastRunner().run(j);
        assertEquals(JobStatus.SUCCEEDED, r.status(), r.message());
    }

    @Test
    void childStdinIsClosedSoReadDoesNotHangUntilTimeout() {
        // JobRunner はジョブに標準入力を供給しないため、子プロセスの標準入力は起動直後に
        // 閉じられ、`read` は即座に EOF を検知して失敗するはず。閉じ忘れていると `read` が
        // 入力を待って無期限にブロックし、このジョブは (実際には一瞬で終わるべきところ)
        // タイムアウト秒数いっぱいまで待たされて TIMED OUT として終端する。
        JobResult r = fastRunner().run(
                job("stdin", List.of("sh", "-c", "read x; exit 7"), 0, 3));
        // read が即座に EOF で終わり、後続の "exit 7" がそのまま実行されることを確認する
        assertEquals(JobStatus.FAILED, r.status());
        assertEquals(7, r.exitCode());
        // タイムアウト(3秒)を待たされていない、つまり標準入力がブロックしていないことを確認する
        assertTrue(r.duration().compareTo(Duration.ofSeconds(2)) < 0,
                "expected stdin EOF to unblock 'read' almost immediately, was " + r.duration());
    }
}
