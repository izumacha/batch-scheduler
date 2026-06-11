package io.github.izumacha.batch.cli;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * CLI コマンドが共通して使う null 安全な整形ユーティリティメソッド群。
 */
final class CliFormat {

    // タイムスタンプのフォーマッタ（システムのデフォルトタイムゾーンで「yyyy-MM-dd HH:mm:ss」形式）
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // インスタンス生成を禁止するためのプライベートコンストラクタ（ユーティリティクラス）
    private CliFormat() {
    }

    /** Instant をローカルタイムスタンプとして整形する。null の場合は {@code "-"} を返す */
    static String instant(Instant instant) {
        // null の場合はハイフンを返し、そうでなければフォーマッタで整形して返す
        return instant == null ? "-" : TIMESTAMP.format(instant);
    }

    /** Duration をコンパクトな形式で整形する（例: {@code "1m03.4s"}、{@code "850ms"}） */
    static String duration(Duration duration) {
        // null・ゼロ・負の値はすべて "0ms" として返す
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return "0ms";
        }
        // ミリ秒単位の値を取得する
        long millis = duration.toMillis();
        // 1000ms 未満の場合は「XXXms」形式で返す
        if (millis < 1000) {
            return millis + "ms";
        }
        // 合計秒数を取得する
        long totalSeconds = duration.getSeconds();
        // 分の部分を計算する
        long minutes = totalSeconds / 60;
        // 秒の部分（小数点付き）を計算する
        double seconds = (totalSeconds % 60) + (millis % 1000) / 1000.0;
        // 1分以上の場合は「Xm00.0s」形式で返す
        if (minutes > 0) {
            return String.format("%dm%04.1fs", minutes, seconds);
        }
        // 1分未満の場合は「X.Xs」形式で返す
        return String.format("%.1fs", seconds);
    }

    /** テーブル表示用に null かもしれないメッセージを最大 {@code max} 文字に切り詰める */
    static String shortMessage(String message, int max) {
        // null または空白のみの場合は空文字を返す
        if (message == null || message.isBlank()) {
            return "";
        }
        // 改行や連続する空白を 1 つのスペースに圧縮して 1 行に整形する
        String oneLine = message.replaceAll("\\s+", " ").trim();
        // 最大文字数以内であればそのまま返す
        if (oneLine.length() <= max) {
            return oneLine;
        }
        // 最大文字数を超える場合は末尾を省略記号（…）で置き換えて返す
        return oneLine.substring(0, Math.max(0, max - 1)) + "…";
    }
}
