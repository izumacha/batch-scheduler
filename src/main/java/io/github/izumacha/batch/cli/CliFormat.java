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

    /** 原因の連鎖をたどる上限段数（循環していても止まるようにするための歯止め）。 */
    private static final int MAX_CAUSE_DEPTH = 5;

    /**
     * 打ち切り後に根元を探しに行くときの歩数上限。表示するのは 1 段だけなので
     * 出力量は増えず、循環した連鎖でも必ず止まる。
     */
    private static final int MAX_ROOT_SEARCH_DEPTH = 100;

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

    // 1 行へ圧縮するときに「区切り」として扱う空白の集合（§6: 意図のある値は名前付き定数に置く）。
    // Java の \s は [ \t\n\x0B\f\r] だけで、U+0085 (NEL)・U+2028 (LS)・U+2029 (PS) を
    // 含まない。一方 CONTROL_CHARS_PATTERN は U+0080〜U+009F と \p{Cf} でそれらを拾って
    // 「削除」するため、この 3 文字だけが圧縮ではなく除去に回り、前後の単語が
    // "line onefile not found" のように繋がってしまう。外部由来の文字列（OS のエラー文や
    // 非 UTF-8 ロケールのデコード結果）は実際にこれらを含みうるので、区切りとして
    // 扱う側へ明示的に足しておく
    private static final String WHITESPACE_PATTERN = "[\\s\\u0085\\u2028\\u2029]+";


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
        // 外側の例外のメッセージ（無ければクラス名）から組み立てを始める
        String head = safeMessage(t);
        StringBuilder rendered = new StringBuilder(head);
        // 外側に添えられた診断（addSuppressed）も併記する
        appendSuppressed(rendered, head, t);
        // 原因を根元までたどって併記する。1 段だけだと、途中で説明を足して包み直した
        // 経路（保存の「記録は公開済みだが耐久性を確認できなかった」がこれ）で
        // 肝心の理由（ENOSPC 等）が落ちる。説明を足した経路ほど理由が消える逆転になる
        Throwable cause = t.getCause();
        // 万一 getCause() が循環していても止まるよう、たどる深さに上限を設ける
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            // この段の表現（クラス名とメッセージ）を併記する
            appendDetail(rendered, head, cause.toString());
            // その段に添えられた診断も併記する
            appendSuppressed(rendered, head, cause);
            // 次の段（さらに内側の原因）へ進む
            cause = cause.getCause();
        }
        // 上限で打ち切った場合は、その事実を残したうえで根元だけは必ず載せる。
        // 印だけ付けて終えると「打ち切ったことは分かるが理由は分からない」になり、
        // このメソッドが直したはずの状態と実質同じところへ戻ってしまう。中間の
        // 包み（BatchExecutionException → RuntimeException → …）は文脈を足すだけで
        // 対処に必要な情報を持たないことが多く、operator が読みたいのは根元の
        // IOException なので、間を省いてでもそこは見せる
        if (cause != null) {
            // 印を付けるのは「根本原因の行にも載らない段が実際にある」ときだけ。
            // 連鎖がちょうど上限 +1 段だと、打ち切った先は根元そのもので下に併記される
            // ため、無条件に付けると「何も落ちていないのに落ちたと告げる」ことになり、
            // 運用者は存在しない情報を探しに行く
            if (cause.getCause() != null) {
                rendered.append(" (...further causes omitted)");
            }
            // 根元まで下りる。循環していても止まるよう、こちらにも歩数の上限を置く
            Throwable root = cause;
            for (int steps = 0; root.getCause() != null && steps < MAX_ROOT_SEARCH_DEPTH; steps++) {
                root = root.getCause();
            }
            // 本当に根元まで下りられたときだけ「根本原因」と名乗る。歩数上限で止まった
            // （循環している・異常に深い）場合に着いた先はただの途中の包みで、それを
            // 根本原因と言い切ると運用者を間違った失敗の調査へ送り出してしまう
            String label = root.getCause() == null ? "root cause: " : "deepest cause reached: ";
            appendDetail(rendered, head, label + root);
        }
        // 1 行へ整形し、端末制御文字を取り除いてから返す。原因のメッセージにはパス
        // （NoSuchFile / AccessDenied はオフェンディングパスをそのままメッセージにする）が
        // 生で入り、それが端末へ出る。長さは切り詰めない — 表のセルと違って桁を揃える
        // 必要が無く、このメソッドが存在する理由そのものである根本原因が末尾にあるため
        return sanitizeOneLine(rendered.toString());
    }

    /**
     * {@code throwable} に添えられた診断（{@code addSuppressed}）を併記する。
     *
     * <p>抑制された例外は「主因ではないが、判断材料になる別の失敗」を運ぶために
     * 付けられている。たとえばアトミック移動が使えなかった理由は、フォールバックも
     * 失敗したときに主因ではなくなるが、「この保存先ではこの経路を通るのが普通なのか」を
     * 判断する材料はそちらにしかない。ここで描画しなければ、集めているだけで
     * 誰にも届かない情報になる。
     */
    private static void appendSuppressed(StringBuilder rendered, String head, Throwable throwable) {
        // 添えられた失敗を順に併記する（無ければ何もしない）
        for (Throwable suppressed : throwable.getSuppressed()) {
            // 主因と区別できるよう "also:" を付ける
            appendDetail(rendered, head, "also: " + suppressed);
        }
    }

    /**
     * まだ出ていない詳細だけを「 (詳細)」の形で追記する。
     *
     * <p>メッセージを渡さずに包んだ例外（{@code new UncheckedIOException(cause)}）は
     * {@code Throwable} が原因の {@code toString()} をそのまま detailMessage に
     * 採用するため、無条件に足すとまったく同じ文が 2 回並ぶ。
     *
     * <p>抑止するのは<em>冒頭のメッセージとの完全一致</em>だけで、それ以外は
     * 重複して見えても必ず出す。理由は 2 つ。組み立て済みの文字列への<em>含有</em>で
     * 判定すると、原因の文言を引用したうえで説明を足す包み方
     * （{@code "... could not sync the file at /x"} が
     * {@code "... could not sync the file"} を含む）で別物の原因が黙って消える。
     * 「これまでに出した全部との一致」で判定しても同じ穴が残る — 別々の失敗が
     * たまたま同じ文字列に描画されること（同じパスに対する 2 つの
     * {@code AccessDeniedException} など）は珍しくなく、その 2 件目は印も付かずに
     * 消える。どちらも、このメソッドが直したはずの「診断が消えているのに、消えたことも
     * 分からない」状態そのもの。冒頭との一致だけは、包み方の都合で必ず同じ文になる
     * （{@code new UncheckedIOException(cause)} は原因の {@code toString()} を
     * そのまま detailMessage にする）と分かっている 1 ケースなので抑止してよい。
     */
    private static void appendDetail(StringBuilder rendered, String head, String detail) {
        // 冒頭のメッセージと同じ文でなければ追記する
        if (!detail.equals(head)) {
            rendered.append(" (").append(detail).append(')');
        }
    }

    /**
     * 端末へ出す文字列を 1 行へ整形し、制御文字を取り除く。
     *
     * <p>順序が肝で、必ず「空白の圧縮 → 制御文字の除去」で行う。逆にすると改行・タブは
     * {@code \p{Cntrl}} に含まれるため「削除」され、前後の単語が
     * {@code "line onefile not found"} のように繋がって別語へ化ける。
     *
     * <p>この 2 手をメソッドに切り出しているのは、順序の不変条件を 1 か所へ集めるため
     * （§6 DRY）。両方の呼び出し元に書き写すと、片方だけ順序を入れ替えても
     * もう片方のテストが緑のまま通ってしまう。
     */
    static String sanitizeOneLine(String text) {
        // 改行や連続する空白を 1 つのスペースに圧縮して 1 行に整形する
        String oneLine = text.replaceAll(WHITESPACE_PATTERN, " ").trim();
        // 空白圧縮後に残った ESC・BEL などの制御文字を取り除き、端末への注入を防ぐ
        return stripControlChars(oneLine);
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
        // 1 行へ整形し、端末制御文字を取り除く（順序の理由は sanitizeOneLine を参照）
        String oneLine = sanitizeOneLine(message);
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
