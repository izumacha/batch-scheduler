package io.github.izumacha.batch.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable outcome of executing a single job during a batch run.
 *
 * @param jobId      id of the job this result belongs to
 * @param status     terminal {@link JobStatus} ({@code SUCCEEDED}, {@code FAILED} or {@code SKIPPED})
 * @param exitCode   process exit code; {@code -1} when the job never produced one
 *                   (skipped, timed out, or failed to start)
 * @param attempts   number of attempts actually made (0 for skipped jobs)
 * @param startedAt  when the first attempt started; {@code null} for skipped jobs
 * @param finishedAt when the job reached its terminal state; {@code null} for skipped jobs
 * @param message    short human-readable note (error summary, timeout, output tail, ...)
 */
public record JobResult(
        String jobId,
        JobStatus status,
        int exitCode,
        int attempts,
        Instant startedAt,
        Instant finishedAt,
        String message
) {

    // プロセスが終了コードを返さなかった場合（スキップ・タイムアウト・起動失敗）に使う番兵値
    public static final int NO_EXIT_CODE = -1;

    /** Wall-clock duration of the job, or {@link Duration#ZERO} when it did not run. */
    public Duration duration() {
        // 開始時刻または終了時刻が null なら所要時間ゼロを返す（スキップされたジョブなど）
        if (startedAt == null || finishedAt == null) {
            return Duration.ZERO;
        }
        // 開始時刻と終了時刻の差分として実際の所要時間を計算して返す
        return Duration.between(startedAt, finishedAt);
    }

    public boolean succeeded() {
        // ジョブのステータスが SUCCEEDED かどうかを返す
        return status == JobStatus.SUCCEEDED;
    }

    /** Result for a job that was skipped because a dependency did not succeed. */
    public static JobResult skipped(String jobId, String message) {
        // 依存ジョブが成功しなかったためスキップされたジョブの結果を生成して返す
        // 試行回数は 0、開始・終了時刻は null、終了コードは NO_EXIT_CODE を設定する
        return new JobResult(jobId, JobStatus.SKIPPED, NO_EXIT_CODE, 0, null, null, message);
    }
}
