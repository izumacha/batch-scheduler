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

    // 端末表示から取り除く制御文字のパターン（§6: 意図のある値は名前付き定数に置く）。
    // \p{Cntrl} は ASCII の C0 制御文字（ESC=0x1B・BEL=0x07 など）と DEL（0x7F）、
    // U+0080〜U+009F は C1 制御文字（CSI=0x9B など）、\p{Cf} は Unicode の
    // フォーマット文字（カテゴリ Cf。双方向テキスト制御の RLO=U+202E や
    // U+202A〜U+202E・分離制御の U+2066〜U+2069 など）。ジョブの出力は信頼できない
    // 実行時データ（DESIGN.md の信頼モデル参照）なので、生の制御文字を端末へ
    // そのまま流すとタイトル偽装・文字消去・カーソル操作などの端末乗っ取りを許して
    // しまい、bidi（双方向）制御文字は run/list のサマリー表の文字列を視覚的に
    // 並べ替えて表示内容を偽装できてしまう。改行・タブ等の空白系は shortMessage が
    // 先にスペースへ圧縮するため、ここでは「空白圧縮後に残る非表示文字」をまとめて削除する
    private static final String CONTROL_CHARS_PATTERN = "[\\p{Cntrl}\\u0080-\\u009F\\p{Cf}]";

    // 表のセルを切り詰めたことを示すマーカー（§6: マジック文字列を避け単一の参照元に置く）。
    // ASCII の "..." にしているのは DESIGN.md「ASCII-only CLI diagnostics」の不変条件のため。
    // System.out は JVM の stdout.encoding（＝プラットフォームのネイティブ文字集合）で符号化し、
    // LANG 未設定の C/POSIX ロケール（Docker の JDK ベースイメージや CI コンテナの既定）では
    // それが US-ASCII になる。以前ここで使っていた省略記号「…」(U+2026) は US-ASCII で
    // 表現できないため "?" へ潰れ、切り詰め表示が "some messag?" のように
    // 「壊れた出力」と区別できない見た目になっていた。
    // ジョブ出力など外部由来の文字列の符号化はプラットフォーム側の責務とし、
    // このツール自身が足す文言だけをロケール非依存に保つ方針（DESIGN.md 参照）
    private static final String TRUNCATION_MARK = "...";

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

    /**
     * 例外のメッセージに、原因があれば「 (原因)」を 1 行で併記して返す。
     *
     * <p>チェックなし例外で包まれた失敗は、外側のメッセージだけを出すと肝心の理由が
     * 落ちる。とくに {@code JsonExecutionStore.save} は原因側にしか「記録は公開済みだが
     * 耐久性を確認できなかった」という区別を持たせておらず、外側だけを見せると
     * 「保存できなかった」としか読めない案内になり、実際には残っている記録に対して
     * バッチ全体の再実行へ誘導してしまう。スタックトレースは出さない（§9）。
     */
    static String safeMessageWithCause(Throwable t) {
        // 失敗の根本原因（例: 既存ファイルと衝突・権限不足・同期の失敗）を取り出す
        Throwable cause = t.getCause();
        // 原因がある場合だけ「 (原因)」の形で併記する
        String detail = cause != null ? " (" + cause + ")" : "";
        // 外側のメッセージと原因を 1 行にまとめて返す
        return safeMessage(t) + detail;
    }

    /**
     * テーブル表示用に null かもしれないメッセージを最大 {@code max} 文字に切り詰める。
     * ジョブ出力由来の信頼できない文字列が渡るため、空白の圧縮に加えて
     * {@link #stripControlChars(String)} で端末制御文字も除去する（唯一のチョークポイント）。
     *
     * <p>切り詰めマーカーは {@link #TRUNCATION_MARK}（ASCII）で、戻り値の長さは
     * 必ず {@code max} 以下に収まる（表の桁ずれを防ぐため）。
     */
    static String shortMessage(String message, int max) {
        // null または空白のみの場合は空文字を返す
        if (message == null || message.isBlank()) {
            return "";
        }
        // 改行や連続する空白を 1 つのスペースに圧縮して 1 行に整形する
        String oneLine = message.replaceAll("\\s+", " ").trim();
        // 空白圧縮後に残った ESC・BEL などの制御文字を取り除き、端末への注入を防ぐ
        oneLine = stripControlChars(oneLine);
        // 最大文字数以内であればそのまま返す
        if (oneLine.length() <= max) {
            return oneLine;
        }
        // マーカーを入れる余地すら無い極端に小さい max では、マーカーを付けずに単純に切る
        // （マーカーを足すと戻り値が max を超えて表の桁が崩れてしまうため）
        if (max <= TRUNCATION_MARK.length()) {
            return oneLine.substring(0, Math.max(0, max));
        }
        // 最大文字数を超える場合は、マーカー分を差し引いた位置で切ってマーカーを付けて返す
        // （切り詰め後の全長がちょうど max になる）
        return oneLine.substring(0, max - TRUNCATION_MARK.length()) + TRUNCATION_MARK;
    }

    /**
     * 端末をあやつる制御文字（ESC・BEL・CSI・DEL など）を文字列から取り除く。
     * ジョブ出力や state ファイル由来の信頼できない値（runId 等）を端末へ表示する
     * 直前のサニタイズとして使う（§9: 出力もエスケープする）。null は null のまま返す。
     */
    static String stripControlChars(String value) {
        // null はそのまま返す（呼び出し元の null 扱いを変えないため）
        if (value == null) {
            return null;
        }
        // 定数パターンに一致する制御文字をすべて削除した文字列を返す
        return value.replaceAll(CONTROL_CHARS_PATTERN, "");
    }
}
