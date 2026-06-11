package io.github.izumacha.batch.state;

import io.github.izumacha.batch.model.ExecutionResult;

import java.time.Instant;
import java.util.Comparator;

/**
 * Shared helpers for working with collections of {@link ExecutionResult}s in
 * the persistence layer.
 */
final class ExecutionResults {

    /**
     * Orders results most recent first by {@code startedAt}, placing results
     * with a {@code null} {@code startedAt} last regardless of direction.
     */
    static final Comparator<ExecutionResult> BY_STARTED_AT_DESC = (a, b) -> {
        // a と b それぞれの開始時刻を取り出す
        Instant sa = a.startedAt();
        Instant sb = b.startedAt();
        // 両方の開始時刻が null なら順序は同じとして 0 を返す
        if (sa == null && sb == null) {
            return 0;
        }
        // a の開始時刻が null なら a を b より後ろに置く（null は末尾）
        if (sa == null) {
            return 1; // a after b
        }
        // b の開始時刻が null なら a を b より前に置く（null は末尾）
        if (sb == null) {
            return -1; // a before b
        }
        // 両方 null でない場合は新しい方が先頭になるよう降順で比較する
        return sb.compareTo(sa); // descending
    };

    // ユーティリティクラスのため外部からのインスタンス化を禁止するプライベートコンストラクタ
    private ExecutionResults() {
    }
}
