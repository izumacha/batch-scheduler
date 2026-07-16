package io.github.izumacha.batch.core;

import java.time.Duration;
import java.util.function.LongUnaryOperator;

/**
 * リトライ間隔（バックオフ）を計算するポリシー。
 *
 * <p>「フルジッター」方式（AWS Architecture Blog "Exponential Backoff And Jitter" が推奨する手法）を
 * 採用する: 試行回数ごとに上限つき指数関数で上振れ幅を広げ、実際の待機時間はその範囲内の
 * 一様乱数とする。固定間隔だと、外部サービス障害からの復旧直後に多数のリトライが同一タイミングへ
 * 集中し（thundering herd）、復旧直後のサービスを再び過負荷にしうる。ランダム幅を持たせることで
 * リトライのタイミングを散らし、この集中を避ける（docs/DESIGN.md の Future extensions「Richer
 * retry / backoff policies」に対応）。
 *
 * <p>baseDelay が {@link Duration#ZERO} の場合はバックオフなし（待機せず即座にリトライ）として
 * 扱う。既存の固定間隔 API（{@code JobRunner} のコンストラクタ）がゼロを「待機なし」として
 * 扱っていた挙動をそのまま踏襲するための後方互換。
 */
public final class RetryBackoffPolicy {

    // 明示的な上限を指定しない場合に使う既定の上限（指数増加がこれを超えて伸び続けないようにする）
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofMinutes(5);

    // 初回リトライ前の基準待機時間（2回目以降はこれを2の(試行回数-1)乗で伸ばしていく）
    private final Duration baseDelay;
    // 指数増加の上限（この値を超えて伸びないようにクランプする）
    private final Duration maxDelay;

    public RetryBackoffPolicy(Duration baseDelay, Duration maxDelay) {
        // 基準待機時間が未指定（null）ならゼロ（バックオフなし）として扱う
        this.baseDelay = baseDelay == null ? Duration.ZERO : baseDelay;
        // 負の基準待機時間は設定ミスとして拒否する（fail-closed: 曖昧な値のまま動かさない）
        if (this.baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must not be negative");
        }
        // 上限が未指定（null）なら既定の上限を使う
        this.maxDelay = maxDelay == null ? DEFAULT_MAX_DELAY : maxDelay;
        // 負の上限も設定ミスとして拒否する
        if (this.maxDelay.isNegative()) {
            throw new IllegalArgumentException("maxDelay must not be negative");
        }
    }

    /** バックオフが無効（待機時間が常にゼロ）かどうかを返す。 */
    public boolean isZero() {
        // 基準待機時間がゼロならバックオフ自体を無効とみなす
        return baseDelay.isZero();
    }

    /**
     * 指定した試行回数に対する、次のリトライまでの待機時間を計算する。
     *
     * @param attempt 直前に完了した試行回数（1始まり。1 = 1回目の試行が失敗し、
     *                これから2回目を試みる直前の待機を表す）
     * @param jitter  上限つき指数値（ミリ秒、排他的上限）を受け取り [0, 上限) の乱数値を返す関数。
     *                本番では {@code bound -> ThreadLocalRandom.current().nextLong(bound)} を渡す。
     *                テストでは決定的な値を返す関数を渡すことで再現可能に検証できる。
     * @return 次のリトライまで待機すべき時間
     */
    public Duration delayFor(int attempt, LongUnaryOperator jitter) {
        // 試行回数は1以上でなければならない（0回目や負数は呼び出し側の不正利用）
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        // バックオフ無効なら待機なし（jitter を呼ぶまでもない）
        if (isZero()) {
            return Duration.ZERO;
        }
        // 基準待機時間をミリ秒に変換する
        long baseMillis = baseDelay.toMillis();
        // ミリ秒未満に丸められてゼロになった場合（例: 数百マイクロ秒）は待機なしとして扱う
        if (baseMillis <= 0) {
            return Duration.ZERO;
        }
        // 上限をミリ秒に変換する
        long capMillis = maxDelay.toMillis();

        // 基準待機時間を試行回数に応じて2倍ずつ増やしていく（オーバーフローと上限超過を都度確認する）。
        // retries の上限は Job.MAX_RETRIES（100万）まで許容されるため、素朴に 2^(attempt-1) を
        // 計算すると long でもすぐ桁あふれする。上限に達し次第ループを打ち切ることで、実際の
        // 反復回数は「基準値が2倍を繰り返して上限へ到達するまで」（せいぜい数十回）に収まる。
        // 初期値は基準値と上限の小さい方から始める。baseMillis をそのまま初期値にすると、
        // attempt=1（ループが1回も回らない）のとき上限チェックが一度も行われず、
        // baseDelay が maxDelay を超えて設定された場合に1回目のリトライだけ上限を超えて
        // 待ってしまう（2回目以降はループ内のクランプが効くのに1回目だけ効かない不整合）
        long cappedMillis = Math.min(baseMillis, capMillis);
        for (int i = 1; i < attempt; i++) {
            // 既に上限に達していれば、これ以上倍にせず打ち切る
            if (cappedMillis >= capMillis) {
                cappedMillis = capMillis;
                break;
            }
            // 基準値を2倍にする
            cappedMillis *= 2;
            // 桁あふれ（負に転じる）または上限超過なら上限に丸めて打ち切る
            if (cappedMillis < 0 || cappedMillis > capMillis) {
                cappedMillis = capMillis;
                break;
            }
        }
        // 上限がゼロに設定されている等でクランプ結果がゼロ以下になった場合は jitter を呼ばず待機なし
        if (cappedMillis <= 0) {
            return Duration.ZERO;
        }

        // [0, cappedMillis] の範囲でランダムな待機時間を選ぶ（フルジッター）。
        // jitter 関数の引数は排他的上限のため、cappedMillis 自身も選ばれ得るよう +1 する
        // （cappedMillis が Long.MAX_VALUE 付近になることは実運用上ありえないが、念のため桁あふれを避ける）
        long boundExclusive = cappedMillis < Long.MAX_VALUE ? cappedMillis + 1 : Long.MAX_VALUE;
        // 呼び出し側から渡された jitter 関数に実際の乱数選択を委譲し、待機時間（ミリ秒）を得る
        long jitteredMillis = jitter.applyAsLong(boundExclusive);
        // 計算結果を Duration に変換して返す
        return Duration.ofMillis(jitteredMillis);
    }
}
