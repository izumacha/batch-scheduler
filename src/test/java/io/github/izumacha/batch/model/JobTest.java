package io.github.izumacha.batch.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link Job} の入力検証と maxAttempts() 境界挙動を検証するユニットテスト。 */
class JobTest {

    /** テスト用に最小限のフィールドで Job を生成するヘルパー。 */
    private static Job jobWithRetries(int retries) {
        // id と command 以外はデフォルト相当の値で Job を組み立てて返す
        return new Job("j", null, List.of("sh", "-c", "echo x"), List.of(), retries, 0, Map.of(), null);
    }

    @Test
    void maxAttemptsIsRetriesPlusOne() {
        // retries=0 なら初回のみの 1 回、retries=2 なら 3 回になることを確認する
        assertEquals(1, jobWithRetries(0).maxAttempts());
        assertEquals(3, jobWithRetries(2).maxAttempts());
    }

    @Test
    void maxAttemptsDoesNotOverflowAtUpperBound() {
        // 上限ちょうどの retries でも maxAttempts() が正の値（桁あふれしない）ことを確認する
        int maxAttempts = jobWithRetries(Job.MAX_RETRIES).maxAttempts();
        // MAX_RETRIES + 1 が int の範囲内で正しく計算される（オーバーフローで負にならない）
        assertEquals(Job.MAX_RETRIES + 1, maxAttempts);
    }

    @Test
    void rejectsNegativeRetries() {
        // 負の retries は範囲外として拒否される
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> jobWithRetries(-1));
        // メッセージが範囲チェックの内容を含むことを検証する
        assertEquals(true, ex.getMessage().contains("retries must be between 0 and"));
    }

    @Test
    void rejectsRetriesAboveUpperBound() {
        // 上限を超える retries（Integer.MAX_VALUE）は maxAttempts() の桁あふれを招くため拒否される
        assertThrows(IllegalArgumentException.class, () -> jobWithRetries(Integer.MAX_VALUE));
        // 上限 + 1 も同様に拒否される
        assertThrows(IllegalArgumentException.class, () -> jobWithRetries(Job.MAX_RETRIES + 1));
    }
}
