package io.github.izumacha.batch.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JobResult} の jobId 検証と、それに依存する {@link ExecutionResult#result(String)} の
 * null 安全性を確認するユニットテスト。
 */
class JobResultTest {

    @Test
    void nullJobIdIsRejected() {
        // jobId が null の JobResult はコンストラクタで即座に拒否されるべき。
        // 検証がないと、破損・手動改変された状態ファイルから jobId=null の JobResult が
        // そのままデシリアライズされ、ExecutionResult#result(String) を呼んだ際に
        // 未捕捉 NullPointerException になってしまう（本テストはその回帰防止）。
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new JobResult(null, JobStatus.SUCCEEDED, 0, 1,
                        Instant.now(), Instant.now(), "ok"));
        assertTrue(ex.getMessage().contains("jobId"), ex.getMessage());
    }

    @Test
    void blankJobIdIsRejected() {
        // 空白のみの jobId も同様に拒否されるべき（null と同じ理由）
        assertThrows(IllegalArgumentException.class,
                () -> new JobResult("   ", JobStatus.SUCCEEDED, 0, 1,
                        Instant.now(), Instant.now(), "ok"));
    }

    @Test
    void executionResultLookupIsNullSafeWhenJobIdsAreValid() {
        // jobId が検証済みであれば、ExecutionResult#result は例外を投げず正しく検索できる
        Instant now = Instant.now();
        JobResult r = new JobResult("a", JobStatus.SUCCEEDED, 0, 1, now, now, "exit 0");
        ExecutionResult er = new ExecutionResult("run1", "batch", JobStatus.SUCCEEDED, now, now, List.of(r));

        assertEquals("a", er.result("a").orElseThrow().jobId());
        assertTrue(er.result("missing").isEmpty());
    }
}
