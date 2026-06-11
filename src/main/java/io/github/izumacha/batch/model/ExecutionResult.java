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
        // jobResults が null なら空リストに、そうでなければ変更不可のコピーにする
        jobResults = jobResults == null ? List.of() : List.copyOf(jobResults);
    }

    public boolean succeeded() {
        // 全体ステータスが SUCCEEDED かどうかを返す（全ジョブ成功時のみ true）
        return status == JobStatus.SUCCEEDED;
    }

    public Duration duration() {
        // 開始時刻または終了時刻が null なら所要時間ゼロを返す
        if (startedAt == null || finishedAt == null) {
            return Duration.ZERO;
        }
        // 開始時刻と終了時刻の差分として所要時間を計算して返す
        return Duration.between(startedAt, finishedAt);
    }

    public Optional<JobResult> result(String jobId) {
        // ジョブ結果リストから jobId が一致する最初の要素を探して返す
        return jobResults.stream().filter(r -> r.jobId().equals(jobId)).findFirst();
    }

    public long countByStatus(JobStatus status) {
        // 指定されたステータスと一致するジョブ結果の件数を数えて返す
        return jobResults.stream().filter(r -> r.status() == status).count();
    }
}
