package io.github.izumacha.batch.cli;

import java.time.DateTimeException;
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

    // 「値が無い / 整形できない」ことを表すテーブル表示用のプレースホルダ文字列。
    // null の Instant と、フォーマッタの表現範囲を超えて整形できない値の両方で共用する
    // （§6: マジック文字列を避け、単一の参照元に置く）
    private static final String PLACEHOLDER = "-";

    // インスタンス生成を禁止するためのプライベートコンストラクタ（ユーティリティクラス）
    private CliFormat() {
    }

    /**
     * Instant をローカルタイムスタンプとして整形する。null の場合、および
     * {@link Instant#MIN}/{@link Instant#MAX} 近傍のようにローカル日時へ変換できない
     * 極端な時刻（手書き・破損した state ファイル由来など。タイムゾーンのオフセットを
     * 足すと LocalDate の表現範囲＝EpochDay の上下限を踏み越えてしまう値）の場合は
     * {@code "-"} を返す。ここで例外を漏らすと {@code list} のテーブル描画ループが
     * 途中で打ち切られ、1 件の壊れた記録が他の正常な記録の表示まで巻き込んで
     * しまうため（fail-safe、§9）。
     */
    static String instant(Instant instant) {
        // null の場合は表示用プレースホルダを返す
        if (instant == null) {
            return PLACEHOLDER;
        }
        try {
            // フォーマッタで「yyyy-MM-dd HH:mm:ss」形式に整形して返す
            return TIMESTAMP.format(instant);
        } catch (DateTimeException e) {
            // ローカル日時へ変換できない極端な時刻は、例外を呼び出し元へ漏らさず
            // プレースホルダで縮退表示する
            return PLACEHOLDER;
        }
    }

    /**
     * Duration をコンパクトな形式で整形する（例: {@code "1m03.4s"}、{@code "850ms"}）。
     * ミリ秒換算が long を桁あふれするほど巨大な値（破損した state ファイル由来など）は
     * {@code "-"} を返す。{@link #instant(Instant)} と同じく、1 件の壊れた記録で
     * {@code list} のテーブル描画を中断させないための fail-safe（§9）。
     */
    static String duration(Duration duration) {
        // null・ゼロ・負の値はすべて "0ms" として返す
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return "0ms";
        }
        // ミリ秒単位の値を格納する変数を宣言する
        long millis;
        try {
            // ミリ秒単位の値を取得する（約 2.9 億年を超えると long を桁あふれして例外になる）
            millis = duration.toMillis();
        } catch (ArithmeticException e) {
            // 桁あふれするほど巨大な期間は「0ms」と偽らず、整形不能のプレースホルダで縮退表示する
            return PLACEHOLDER;
        }
        // 1000ms 未満の場合は「XXXms」形式で返す
        if (millis < 1000) {
            return millis + "ms";
        }
        // 先に 0.1 秒(=100ms)単位へ四捨五入してから分・秒へ分解する。
        // %.1f に丸めを任せると 59.95 秒が 60.0 に丸め上がっても分桁へ繰り上がらず
        // "1m60.0s" のような不正表示になるため、整数演算で丸めて桁上がりを正しく扱う。
        // 単純な (millis + 50) / 100 は millis が Long.MAX_VALUE 近傍のとき +50 が
        // 桁あふれして "-55.-7s" のような不正表示になるため、商と余りに分けてから
        // 丸める数学的に等価な式（q + (r+50)/100。r<100 なので加算があふれない）を使う
        long tenths = millis / 100 + (millis % 100 + 50) / 100;
        // 分の部分を計算する（600 個の 0.1 秒 = 60 秒 = 1 分）
        long minutes = tenths / 600;
        // 分を除いた残りを 0.1 秒単位で求める
        long secondTenths = tenths % 600;
        // 秒の整数部を求める
        long wholeSeconds = secondTenths / 10;
        // 秒の小数第 1 位を求める
        long fraction = secondTenths % 10;
        // 1分以上の場合は「Xm00.0s」形式で返す（秒の整数部は 2 桁ゼロ埋め）
        if (minutes > 0) {
            return String.format("%dm%02d.%01ds", minutes, wholeSeconds, fraction);
        }
        // 1分未満の場合は「X.Xs」形式で返す
        return String.format("%d.%01ds", wholeSeconds, fraction);
    }

    /**
     * 例外のメッセージを取得する。メッセージが null の場合は代わりに例外クラス名を返し、
     * 診断価値の無い「error: null」表示を防ぐ。BatchCli の最終防波堤ハンドラでのみ適用されて
     * いたこのフォールバックを、同じ問題を持つ他のエラー出力箇所（RunCommand/ListCommand の
     * 個別 catch 節）でも再利用できるよう共通ユーティリティに切り出したもの（§6 DRY）。
     */
    static String safeMessage(Throwable t) {
        // メッセージがあればそれを、無ければクラスの単純名（パッケージ名を含まない）を返す
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
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
