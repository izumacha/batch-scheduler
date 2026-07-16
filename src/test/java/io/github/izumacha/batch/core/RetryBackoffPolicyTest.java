package io.github.izumacha.batch.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryBackoffPolicyTest {

    // ジッターを常に「取りうる最大値」に固定する関数（境界値を検証しやすくするため）。
    // jitter の引数は排他的上限なので、常に bound-1 を返せば「上限ぎりぎり」を再現できる
    private static final java.util.function.LongUnaryOperator MAX_JITTER = bound -> bound - 1;
    // ジッターを常に0に固定する関数（待機なしになる境界を検証するため）
    private static final java.util.function.LongUnaryOperator MIN_JITTER = bound -> 0;

    @Test
    void baseDelayZero_isZeroを返し待機なしになる() {
        // 基準待機時間ゼロならバックオフ自体が無効
        RetryBackoffPolicy policy = new RetryBackoffPolicy(Duration.ZERO, Duration.ofMinutes(5));
        assertTrue(policy.isZero());
        // MAX_JITTER を渡しても待機時間は常にゼロ（jitter は呼ばれない設計）
        assertEquals(Duration.ZERO, policy.delayFor(1, MAX_JITTER));
        assertEquals(Duration.ZERO, policy.delayFor(100, MAX_JITTER));
    }

    @Test
    void nullBaseDelay_ゼロとして扱われる() {
        // null は「未指定」としてゼロ（バックオフなし）に正規化される
        RetryBackoffPolicy policy = new RetryBackoffPolicy(null, null);
        assertTrue(policy.isZero());
    }

    @Test
    void 負の基準待機時間は拒否される() {
        // fail-closed: 曖昧な負の値のまま動かさず例外を投げる
        assertThrows(IllegalArgumentException.class,
                () -> new RetryBackoffPolicy(Duration.ofSeconds(-1), Duration.ofMinutes(5)));
    }

    @Test
    void 負の上限は拒否される() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryBackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(-1)));
    }

    @Test
    void attemptが1未満なら拒否される() {
        RetryBackoffPolicy policy = new RetryBackoffPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5));
        assertThrows(IllegalArgumentException.class, () -> policy.delayFor(0, MAX_JITTER));
    }

    @Test
    void 初回リトライは基準待機時間を超えない() {
        // attempt=1（1回目の試行直後）は 2^0=1倍、つまり基準値そのものが上限
        RetryBackoffPolicy policy = new RetryBackoffPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5));
        // 最大ジッターでも基準値(1000ms)ちょうどまでしか出ない
        assertEquals(Duration.ofMillis(1000), policy.delayFor(1, MAX_JITTER));
        // 最小ジッターなら待機なし
        assertEquals(Duration.ZERO, policy.delayFor(1, MIN_JITTER));
    }

    @Test
    void 試行を重ねるごとに指数的に上限が伸びる() {
        RetryBackoffPolicy policy = new RetryBackoffPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5));
        // attempt=2: 2^1=2倍 -> 上限2000ms
        assertEquals(Duration.ofMillis(2000), policy.delayFor(2, MAX_JITTER));
        // attempt=3: 2^2=4倍 -> 上限4000ms
        assertEquals(Duration.ofMillis(4000), policy.delayFor(3, MAX_JITTER));
        // attempt=5: 2^4=16倍 -> 上限16000ms
        assertEquals(Duration.ofMillis(16000), policy.delayFor(5, MAX_JITTER));
    }

    @Test
    void 上限を超えて伸び続けずクランプされる() {
        // 基準1秒・上限5分(300秒)で、試行回数を非常に多くしても300秒でクランプされる
        RetryBackoffPolicy policy = new RetryBackoffPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5));
        assertEquals(Duration.ofSeconds(300), policy.delayFor(1_000_000, MAX_JITTER));
        // 桁あふれが起きるほど巨大な試行回数でも安全にクランプされることを確認する
        assertEquals(Duration.ofSeconds(300), policy.delayFor(Integer.MAX_VALUE, MAX_JITTER));
    }

    @Test
    void 基準待機時間が上限を超えて設定されていても初回から上限でクランプされる() {
        // 回帰テスト: 指数計算ループは attempt=1 のとき1回も回らないため、初期値を
        // baseMillis のまま使うと1回目のリトライだけ上限チェックを素通りしてしまうバグがあった
        // （2回目以降はループ内でクランプされるのに1回目だけ効かない不整合）。
        // 基準10分・上限5分という「基準が上限を超える」設定で、1回目から上限どおりに
        // クランプされることを検証する
        RetryBackoffPolicy policy = new RetryBackoffPolicy(Duration.ofMinutes(10), Duration.ofMinutes(5));
        // attempt=1（ループが回らない最初のケース）でも上限の5分を超えない
        assertEquals(Duration.ofMinutes(5), policy.delayFor(1, MAX_JITTER));
        // attempt=2（ループが回るケース）も同様に上限どおり
        assertEquals(Duration.ofMinutes(5), policy.delayFor(2, MAX_JITTER));
    }

    @Test
    void 上限がゼロなら基準待機時間があっても初回から待機なし() {
        // 上記と同じ回帰: maxDelay=ZERO のとき、attempt=1 でも即座に待機なしになるべき
        RetryBackoffPolicy policy = new RetryBackoffPolicy(Duration.ofSeconds(5), Duration.ZERO);
        assertEquals(Duration.ZERO, policy.delayFor(1, MAX_JITTER));
        assertEquals(Duration.ZERO, policy.delayFor(2, MAX_JITTER));
    }

    @Test
    void ジッターは計算した上限の範囲内で呼び出し側の関数に委譲される() {
        // jitter に渡される排他的上限が「クランプ後の値+1」であることを検証する
        RetryBackoffPolicy policy = new RetryBackoffPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5));
        long[] capturedBound = new long[1];
        policy.delayFor(3, bound -> {
            capturedBound[0] = bound;
            return 0;
        });
        // attempt=3 の上限は 4000ms なので、排他的上限として 4001 が渡されるはず
        assertEquals(4001L, capturedBound[0]);
    }

    @Test
    void サブミリ秒の基準待機時間はゼロとして扱われる() {
        // 500ナノ秒は Duration としては非ゼロだが toMillis() では 0 になるため、待機なし扱いになる
        RetryBackoffPolicy policy = new RetryBackoffPolicy(Duration.ofNanos(500), Duration.ofMinutes(5));
        assertEquals(Duration.ZERO, policy.delayFor(1, MAX_JITTER));
    }
}
