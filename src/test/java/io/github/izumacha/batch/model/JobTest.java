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

    @Test
    void rejectsNullDependencyEntry() {
        // 依存リスト内の null 要素はコンストラクタで明示的に弾く。
        // 弾かないと後段の DependencyGraph.build で未捕捉 NPE＋スタックトレース露出になる。
        // 一部の依存 ID を null にした依存リストを用意する（Arrays.asList は null 要素を許容する）
        List<String> depsWithNull = java.util.Arrays.asList("a", null);
        // Job 構築時に IllegalArgumentException が投げられることを検証する
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new Job("j", null, List.of("sh", "-c", "echo x"), depsWithNull, 0, 0, Map.of(), null));
        // メッセージが依存要素の null 拒否である旨を含むことを検証する
        assertEquals(true, ex.getMessage().contains("dependsOn entry must not be null"));
    }

    @Test
    void rejectsNullCommandEntry() {
        // command 内の null 要素も dependsOn と同じ理由で明示的に弾く
        // （弾かないと List.copyOf の素の NPE がそのまま呼び出し元まで伝播する）
        List<String> commandWithNull = java.util.Arrays.asList("sh", null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new Job("j", null, commandWithNull, List.of(), 0, 0, Map.of(), null));
        // メッセージが command 要素の null 拒否である旨を含むことを検証する
        assertEquals(true, ex.getMessage().contains("command entry must not be null"));
    }

    @Test
    void rejectsNullEnvValue() {
        // env のマップに null 値が混入していた場合も明示的に弾く
        // （HashMap は null 値を許容するため、素の Map.copyOf 任せだと NPE がそのまま伝播する）
        Map<String, String> envWithNullValue = new java.util.HashMap<>();
        envWithNullValue.put("KEY", null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new Job("j", null, List.of("sh", "-c", "echo x"), List.of(), 0, 0, envWithNullValue, null));
        // メッセージが env のキー/値の null 拒否である旨を含むことを検証する
        assertEquals(true, ex.getMessage().contains("env key/value must not be null"));
    }

    @Test
    void rejectsNullEnvKey() {
        // env のマップに null キーが混入していた場合も明示的に弾く（値だけでなくキー側も
        // 同じチェック条件（e.getKey() == null）でカバーしていることを固定するテスト。
        // HashMap は null キーを 1 つだけ許容するため、素の Map.copyOf 任せだと
        // NPE がそのまま伝播する）
        Map<String, String> envWithNullKey = new java.util.HashMap<>();
        envWithNullKey.put(null, "value");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new Job("j", null, List.of("sh", "-c", "echo x"), List.of(), 0, 0, envWithNullKey, null));
        // メッセージが env のキー/値の null 拒否である旨を含むことを検証する
        assertEquals(true, ex.getMessage().contains("env key/value must not be null"));
    }
}
