package io.github.izumacha.batch.core;

import io.github.izumacha.batch.model.Job;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // shows the final output line (line5). The tail is a ring buffer that
        // keeps the END of the output, so the summary must surface its last
        // line — a failing process conventionally prints its error last, and
        // the buffer's first line is just an arbitrary mid-stream line
        // (whichever happened to sit at total-lines minus window-size).
        JobResult r = runner.run(job("many",
                List.of("sh", "-c", "for i in 1 2 3 4 5; do echo line$i; done; exit 1"), 0, 0));
        assertEquals(JobStatus.FAILED, r.status());
        assertTrue(r.message().contains("line5"), r.message());
        assertTrue(!r.message().contains("line1"), r.message());
    }

    @Test
    void failureMessageShowsFinalErrorLineNotOldestRetainedLine() {
        JobRunner runner = new JobRunner(3, Duration.ZERO, false);
        // Emit progress lines then a FATAL line and fail. The retained window
        // is the last 3 lines (progress4, progress5, FATAL...); the summary
        // must show the FATAL line, not whichever progress line happens to be
        // the oldest retained one.
        JobResult r = runner.run(job("fatal",
                List.of("sh", "-c",
                        "for i in 1 2 3 4 5; do echo progress$i; done; echo 'FATAL: the real error'; exit 1"),
                0, 0));
        assertEquals(JobStatus.FAILED, r.status());
        assertTrue(r.message().contains("FATAL: the real error"), r.message());
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
    void emptyCommandIsReportedAsFailureNotThrown() {
        // Job itself does not reject an empty command list (only DependencyGraph.build()'s
        // pre-flight validation does), so a Job constructed directly (bypassing that
        // validation, as this test does) can reach JobRunner with command = List.of().
        // ProcessBuilder(List.of()).start() throws ArrayIndexOutOfBoundsException, which must
        // surface as a FAILED result rather than escaping run() as an uncaught exception.
        Job j = new Job("emptycmd", null, List.of(), List.of(), 0, 0, Map.of(), null);
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

    @Test
    void interruptStopsRetryingPromptlyWithZeroBackoff() throws InterruptedException {
        // バックオフ(リトライ間隔)0・リトライ多めのランナーで、長時間ジョブの実行中に
        // スレッドを割り込む。割り込み後は残り試行分の子プロセスを起動＆即中断で
        // 空回りせず、割り込みを検知した試行で止まる(= 試行回数 1)ことを検証する。
        // 修正前は下の Thread.sleep(バックオフ)が 0 だと呼ばれず割り込みを見逃し、
        // 残り 5 回すべてで sleep 5 を起動→即 kill する空回りが起きていた。
        JobRunner runner = new JobRunner(50, Duration.ZERO, false);
        // retries=5 なので、空回りすれば計 6 回試行してしまう長時間ジョブ
        Job job = job("interrupt", List.of("sh", "-c", "sleep 5"), 5, 0);

        // 別スレッドで実行し、結果を受け取るための入れ物
        AtomicReference<JobResult> resultRef = new AtomicReference<>();
        Thread worker = new Thread(() -> resultRef.set(runner.run(job)));
        // 実行開始
        worker.start();
        // 最初の試行が sleep の待機に入るのを待ってから割り込む
        Thread.sleep(500);
        worker.interrupt();
        // 割り込み後、全試行(6×sleep)を待たずに速やかに終わるはず。余裕をもって 10 秒待つ
        worker.join(Duration.ofSeconds(10).toMillis());

        // ワーカーが停止していること(ハングしていない)
        assertFalse(worker.isAlive(), "worker should finish promptly after interrupt");
        JobResult r = resultRef.get();
        assertNotNull(r);
        // 割り込みで中断されたので失敗扱い
        assertEquals(JobStatus.FAILED, r.status());
        // 割り込みを検知した最初の試行で止まり、残りリトライを空回りしないこと
        assertEquals(1, r.attempts(),
                "should stop after the interrupted attempt, not spin through all retries");
    }

    @Test
    void timeoutKillsDescendantsOfForkingJob() throws Exception {
        // fork し続けるジョブがタイムアウトした際に、子孫プロセスが孤児として
        // 取り残されないことを検証する。修正前(子孫→親の順で kill)は、列挙と
        // kill の間に親が生み続けた子が大量に生き残っていた。
        // 子の PID を一時ファイルへ記録し、実行後に全 PID の死亡を確認する。
        java.nio.file.Path pidFile = java.nio.file.Files.createTempFile("jobrunner-pids", ".txt");
        try {
            // 300 秒眠る子を全速力で fork し続けるスクリプト(タイムアウトは 1 秒)
            String script = "while :; do sleep 300 & echo $! >> \"$PID_FILE\"; done";
            Job forker = new Job("forker", null, List.of("sh", "-c", script),
                    List.of(), 0, 1, Map.of("PID_FILE", pidFile.toString()), null);
            JobResult r = fastRunner().run(forker);
            // タイムアウトで失敗扱いになること
            assertEquals(JobStatus.FAILED, r.status());

            // 記録された子 PID を読み出す(少なくとも 1 つは fork されているはず)
            List<Long> pids = java.nio.file.Files.readAllLines(pidFile).stream()
                    .filter(line -> !line.isBlank())
                    .map(Long::parseLong)
                    .toList();
            assertFalse(pids.isEmpty(), "the forking job should have spawned children");

            // すべての子がタイムアウト kill の後まもなく死んでいること(猶予 5 秒)
            long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            for (Long pid : pids) {
                while (isRunning(pid)) {
                    assertTrue(System.nanoTime() < deadlineNanos,
                            "child process " + pid + " leaked after timeout kill");
                    Thread.sleep(50);
                }
            }
        } finally {
            // 一時ファイルを後始末する
            java.nio.file.Files.deleteIfExists(pidFile);
        }
    }

    /** PID のプロセスが実際に実行中かどうかを判定する(終了済み・未回収のゾンビは「死亡」扱い)。 */
    private static boolean isRunning(long pid) {
        // プロセスハンドルが取得できない・isAlive が false なら確実に死んでいる
        var handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        // コンテナ環境では PID 1 が孤児ゾンビを回収しないことがあり、
        // ProcessHandle.isAlive() はゾンビ(終了済み・未回収)にも true を返す。
        // Linux では /proc の状態フィールドで ゾンビ(Z)/死亡(X) を除外する。
        try {
            String stat = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/" + pid + "/stat"));
            // コマンド名は括弧で囲まれるため、最後の ")" の 2 文字後が状態フィールド
            char state = stat.charAt(stat.lastIndexOf(')') + 2);
            return state != 'Z' && state != 'X';
        } catch (java.io.IOException | IndexOutOfBoundsException e) {
            // /proc が読めない環境では isAlive の判定に従う(過剰検出側に倒す)
            return true;
        }
    }
}
