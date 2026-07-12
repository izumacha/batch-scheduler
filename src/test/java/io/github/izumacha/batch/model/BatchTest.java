package io.github.izumacha.batch.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link Batch} の入力検証を検証するユニットテスト。 */
class BatchTest {

    @Test
    void rejectsNullJobEntry() {
        // jobs リスト内の null 要素はコンストラクタで明示的に弾く
        // （Job.java の command/dependsOn/env と同じ理由。弾かないと素の List.copyOf の
        //  NPE がそのまま呼び出し元まで伝播し、未捕捉 NPE＋スタックトレース露出になる）
        Job valid = new Job("j", null, List.of("sh", "-c", "echo x"), List.of(), 0, 0, Map.of(), null);
        // Arrays.asList は null 要素を許容するため、意図的に null を混ぜたリストを作る
        List<Job> jobsWithNull = java.util.Arrays.asList(valid, null);
        // Batch 構築時に IllegalArgumentException が投げられることを検証する
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new Batch("b", jobsWithNull));
        // メッセージが jobs 要素の null 拒否である旨を含むことを検証する
        assertEquals(true, ex.getMessage().contains("jobs entry must not be null"));
    }
}
