package io.github.izumacha.batch.text;

import java.util.regex.Pattern;

/**
 * Neutralizes untrusted text before it reaches a terminal.
 *
 * <p>Two kinds of value in this tool are untrusted: a job's captured output,
 * and anything read out of the state directory (which
 * {@code docs/DESIGN.md} "State-directory safety" treats as a tampering
 * target -- run-id validation and no-symlink-following exist for the same
 * reason). Both end up on stdout or stderr, so both have to be stripped of
 * the escape sequences that let a string repaint the terminal, retitle the
 * window, or reorder what the operator sees.
 *
 * <p>This lives in its own package rather than in {@code cli} because the
 * {@code state} package needs it too: {@code JsonExecutionStore} logs the
 * names of files it skips, and with the JDK's default {@code ConsoleHandler}
 * those records go to the same stderr as the CLI's own output. Keeping the
 * logic here means there is exactly one implementation of the rule (CLAUDE.md
 * §6 DRY) and no layering inversion ({@code state} must not depend on
 * {@code cli}).
 */
public final class SafeText {

    // 端末表示から取り除く制御文字のパターン。
    // \p{Cntrl} は ASCII の C0 制御文字（ESC=0x1B・BEL=0x07 など）と DEL（0x7F）、
    // U+0080〜U+009F は C1 制御文字（CSI=0x9B など）、\p{Cf} は Unicode の
    // フォーマット文字（双方向テキスト制御の RLO=U+202E や分離制御の U+2066〜U+2069 など）。
    // 生の制御文字を端末へ流すとタイトル偽装・文字消去・カーソル操作などの乗っ取りを許し、
    // bidi 制御文字は表の文字列を視覚的に並べ替えて表示内容を偽装できてしまう
    private static final Pattern CONTROL_CHARS =
            Pattern.compile("[\\p{Cntrl}\\u0080-\\u009F\\p{Cf}]");

    // 1 行へ圧縮するときに「区切り」として扱う空白の集合。
    // Java の \s は [ \t\n\x0B\f\r] だけで、U+0085 (NEL)・U+2028 (LS)・U+2029 (PS) を
    // 含まない。3 文字とも、圧縮側に足さないと別々の壊れ方をする:
    //   - U+0085 (NEL) は CONTROL_CHARS の U+0080〜U+009F に当たるため「削除」され、
    //     前後の単語が "line onefile not found" のように繋がって別語へ化ける。
    //   - U+2028/U+2029 は Unicode カテゴリ Zl/Zp で、\p{Cntrl} にも U+0080〜U+009F にも
    //     \p{Cf} にも当たらない。つまり圧縮も除去もされず、生の行区切りとして端末へ届き、
    //     1 記録 1 行という表の前提を壊す。
    // ここから外すと、CONTROL_CHARS は Zl/Zp を拾わないので後者は素通りに戻る
    private static final Pattern WHITESPACE =
            Pattern.compile("[\\s\\p{Z}\\u0085]+");

    // 中略したことを示すマーカー。ASCII なのは、LANG 未設定の C/POSIX ロケールでは
    // stdout が US-ASCII になり「…」(U+2026) が "?" へ潰れて壊れた出力と区別が
    // 付かなくなるため（DESIGN.md「ASCII-only CLI diagnostics」）
    private static final String ELLIPSIS = "...";

    /**
     * ログ 1 行に載せる値の最大文字数。
     *
     * <p>これらの記録は既定の {@code ConsoleHandler} が CLI と同じ stderr へ書くので、
     * 表示経路としては CLI のエラー行と同じ扱いにする。{@code FileSystemException} は
     * 関係するパスを丸ごと本文に持つため、上限が無いと 1 行が数キロバイトになりうる。
     */
    private static final int MAX_LOG_CHARS = 500;

    // 行の区切りとして扱う並び。Java の \R は改行の総称で、CRLF をひとかたまりに
    // 扱いつつ LF・CR・VT・FF・NEL・LS・PS のいずれにも当たる。多様な綴りの改行を
    // まず LF 1 種類へ揃えておくことで、この下の除去パターンが「残すのは LF だけ」と
    // 単純に書ける（CR や NEL を残すかどうかを別途決めずに済む）
    private static final Pattern LINE_SEPARATORS = Pattern.compile("\\R");

    // 行の構造を保ったまま取り除く制御文字のパターン。CONTROL_CHARS との違いは
    // 改行（LF）とタブを残す点だけ。
    // [\p{Cntrl}&&[^\n\t]] は「C0 制御文字と DEL のうち、LF とタブ以外」という意味
    // （&& は文字クラスの共通部分、[^...] は否定）。
    // 残す 2 文字は端末をあやつる力を持たない — LF は行を進めるだけ、タブは次の
    // タブ位置へ動かすだけで、カーソルの絶対移動・画面消去・タイトル変更はできない。
    // 一方 ESC・BEL・CSI・bidi 制御はここでも取り除くので、注入に対する強度は
    // CONTROL_CHARS と変わらない
    private static final Pattern CONTROL_CHARS_KEEPING_LINES =
            Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]|[\\u0080-\\u009F\\p{Cf}]");

    // インスタンス生成を禁止するためのプライベートコンストラクタ（ユーティリティクラス）
    private SafeText() {
    }

    /**
     * ログ 1 行へ載せる値を、無害化したうえで有界にして返す。
     *
     * <p>{@code oneLine} と {@code bounded} を正しい順序で呼ぶ 3 段重ねを、ログを出す
     * 側が毎回書き写さずに済むようにするためのもの。書き写す形にしていると、新しい
     * ログ行で 1 段抜けたときに生の ESC/BEL や数メガバイトの 1 行が stderr へ届き、
     * しかもそれを検出する仕組みが無い — この仕組みが塞ごうとしている穴そのもの。
     *
     * @param value 載せる値（{@code null} 可。{@code "null"} として描画される）
     * @return 1 行に整形し、制御文字を除き、長さを切り詰めた文字列
     */
    public static String forLog(Object value) {
        // ログ用の上限で表示用の整形を行う
        return forDisplay(value, MAX_LOG_CHARS);
    }

    /**
     * 端末へ出す値を、無害化したうえで指定した長さに収めて返す。
     *
     * <p>{@link #oneLine(String)} と {@link #bounded(String, int)} を正しい順序で
     * 呼ぶ組を 1 か所に持つためのもの。上限が場所ごとに違う（エラー行・run ID・
     * ログ行）ので、値と上限だけを受け取る。書き写す形にしていると、新しい表示箇所で
     * 1 段抜けたときに生の ESC/BEL や数メガバイトの 1 行が端末へ届き、
     * しかもそれを検出する仕組みが無い。
     *
     * <p>末尾を落とす切り詰め（表のセル）は形が違うので
     * {@link #truncate(String, int)} を別に持つ。中略と切り詰めのどちらが正しいかは
     * 「続きに意味があるか」で決まり、機械的には選べない。
     *
     * @param value 表示する値（{@code null} 可。{@code "null"} として描画される）
     * @param max 戻り値の最大文字数
     * @return 1 行に整形し、制御文字を除き、中略して長さを収めた文字列
     */
    public static String forDisplay(Object value, int max) {
        // 文字列化してから、1 行への整形と長さの上限を順に適用する
        return bounded(oneLine(String.valueOf(value)), max);
    }

    /**
     * 長すぎる文字列を、先頭と末尾の両方を残して中略する。
     *
     * <p>末尾を切り落とす単純な切り詰めでは駄目な場面がある。外部由来の値は
     * メッセージの<em>途中</em>に埋め込まれ、対処に必要な説明はその<em>後ろ</em>に
     * 来ることが多いため（Jackson の {@code at [Source: ...; line: 4, column: 14]}、
     * {@code BatchExecutor} の「別のバッチのものなので流用を拒否した」という結び、
     * {@code FileSystemException} の errno の説明）。末尾から切ると、攻撃者が長さを
     * 選べる値を通しただけで、その説明を丸ごと消せてしまう。
     *
     * <p>両端を残せば、どの値が問題だったか（先頭）と、なぜ駄目だったか（末尾）の
     * 両方が残る。中略した事実は {@code "..."} で示す。
     *
     * @param text 対象の文字列（{@code null} 可）
     * @param max 戻り値の最大文字数（マーカーを含む）
     * @return {@code max} 以下に収めた文字列。{@code null} は {@code null} のまま
     */
    public static String bounded(String text, int max) {
        // null はそのまま返す（呼び出し元の null 扱いを変えないため）
        if (text == null) {
            return null;
        }
        // 収まっているならそのまま返す
        if (text.length() <= max) {
            return text;
        }
        // マーカーすら入らない極端な max では、マーカー無しで単純に切る
        // （足すと戻り値が max を超えて桁が崩れるため）
        if (max <= ELLIPSIS.length()) {
            return text.substring(0, safeCut(text, Math.max(0, max)));
        }
        // マーカー分を除いた残りを、先頭側と末尾側へ分ける
        int budget = max - ELLIPSIS.length();
        int tail = budget / 2;
        int head = budget - tail;
        // 切る位置がサロゲートペアの途中にならないよう内側へ寄せる。Java の String は
        // UTF-16 の符号単位で添字を取るので、補助文字（絵文字・CJK 拡張 B など。
        // ジョブ出力や改変された batchName から実際に入りうる）の真ん中で切ると
        // 片割れのサロゲートが残る。これは Unicode カテゴリ Cs で、制御文字の除去でも
        // 落ちないため端末で "?" や U+FFFD になり、ASCII のマーカーをわざわざ選んで
        // 避けた「壊れた出力に見える」状態をここで作ってしまう
        head = safeCut(text, head);
        int tailStart = text.length() - tail;
        if (tailStart < text.length() && Character.isLowSurrogate(text.charAt(tailStart))) {
            tailStart++;
        }
        // 先頭 + マーカー + 末尾 で組み立てる（全長は max 以下）
        return text.substring(0, head) + ELLIPSIS + text.substring(tailStart);
    }

    /**
     * 長すぎる文字列を末尾から切り詰め、切ったことを {@code "..."} で示す。
     *
     * <p>{@link #bounded(String, int)} と違って末尾を落とす。表のセルのように
     * 「桁を揃えることが目的で、続きは元々読めない」場所ではこちらが正しい。
     * 診断のように末尾へ理由が来る文字列には {@link #bounded(String, int)} を使う。
     *
     * @param text 対象の文字列（{@code null} 可）
     * @param max 戻り値の最大文字数（マーカーを含む）
     * @return {@code max} 以下に収めた文字列。{@code null} は {@code null} のまま
     */
    public static String truncate(String text, int max) {
        // null はそのまま返す（呼び出し元の null 扱いを変えないため）
        if (text == null) {
            return null;
        }
        // 収まっているならそのまま返す
        if (text.length() <= max) {
            return text;
        }
        // マーカーすら入らない極端な max では、マーカー無しで単純に切る
        if (max <= ELLIPSIS.length()) {
            return text.substring(0, safeCut(text, Math.max(0, max)));
        }
        // マーカー分を残した位置で切り、マーカーを付ける（全長は max 以下）
        return text.substring(0, safeCut(text, max - ELLIPSIS.length())) + ELLIPSIS;
    }

    /**
     * サロゲートペアを分断しない切り位置を返す（必要なら 1 つ内側へ寄せる）。
     *
     * <p>Java の {@code String} は UTF-16 の符号単位で添字を取るため、補助文字
     * （絵文字・CJK 拡張 B など。ジョブ出力や改変された state ファイルから実際に
     * 入りうる）の真ん中で切ると片割れのサロゲートが残る。それは Unicode カテゴリ
     * Cs で {@link #stripControlChars(String)} でも落ちず、端末では {@code "?"} や
     * U+FFFD になる — マーカーを ASCII にしてまで避けた「壊れた出力に見える」状態を
     * 自分で作ることになる。
     */
    private static int safeCut(String text, int cut) {
        // 直前が上位サロゲートなら、その 1 つ手前で切る
        if (cut > 0 && cut < text.length() && Character.isHighSurrogate(text.charAt(cut - 1))) {
            return cut - 1;
        }
        // それ以外はそのままの位置でよい
        return cut;
    }

    /**
     * 端末へ出す文字列を 1 行へ整形し、制御文字を取り除く。
     *
     * <p>順序が肝で、必ず「空白の圧縮 → 制御文字の除去」で行う。逆にすると改行・タブは
     * {@code \p{Cntrl}} に含まれるため「削除」され、前後の単語が
     * {@code "line onefile not found"} のように繋がって別語へ化ける。
     *
     * <p>この 2 手をメソッドに切り出しているのは、順序の不変条件を 1 か所へ集めるため
     * （§6 DRY）。呼び出し元に書き写すと、片方だけ順序を入れ替えても
     * もう片方のテストが緑のまま通ってしまう。
     *
     * @param text 整形する文字列（{@code null} 可）
     * @return 1 行に整形して制御文字を除いた文字列。{@code null} は {@code null} のまま
     */
    public static String oneLine(String text) {
        // null はそのまま返す（呼び出し元の null 扱いを変えないため）
        if (text == null) {
            return null;
        }
        // 改行や連続する空白を 1 つのスペースに圧縮して 1 行に整形する
        String collapsed = WHITESPACE.matcher(text).replaceAll(" ");
        // 空白圧縮後に残った ESC・BEL などの制御文字を取り除き、端末への注入を防ぐ
        String stripped = stripControlChars(collapsed);
        // 除去した「後」にもう一度圧縮して端を落とす。制御文字を挟んだ空白
        // （"\u2060 \u2060" のような、改変された state ファイルから来うる値）は
        // 除去の前には空白として端に無いため trim ですり抜け、除去後に空白だけが残る。
        // それを返すと、呼び出し側の「空ならプレースホルダ」という判定
        // （runId / requiredCell / safeMessage）が空でないと見なして通し、
        // 36 桁の空白セルや "error: " だけの行になる — どれも、それらの判定が
        // 防ぐために存在する行き止まりそのもの
        return WHITESPACE.matcher(stripped).replaceAll(" ").strip();
    }

    /**
     * 行の構造を保ったまま、端末をあやつる制御文字を取り除く。
     *
     * <p>{@link #oneLine(String)} は空白をすべて 1 つのスペースへ潰すので、表のセルの
     * ように「1 件 1 行」が前提の場所には向くが、<em>行と桁の配置そのものが情報である</em>
     * 診断には使えない。代表例が SnakeYAML の構文エラーで、次のように該当行を引用して
     * 桁位置を {@code ^} で指す:
     *
     * <pre>
     * while parsing a flow sequence
     *  in 'reader', line 4, column 14:
     *         command: ["x"
     *                  ^
     * </pre>
     *
     * <p>これを 1 行へ潰すと {@code ^} は何も指さなくなり、引用行の字下げも消える。
     * バッチ定義ファイルの構文エラーはこのツールでもっとも普通に踏むエラーなので、
     * ここだけは行の構造を残す。
     *
     * <p>安全性は落とさない。取り除く対象から外すのは改行とタブだけで、どちらも
     * 端末をあやつる力を持たない（{@link #CONTROL_CHARS_KEEPING_LINES} のコメント参照）。
     * ESC・BEL・CSI・bidi 制御は {@link #oneLine(String)} と同じように取り除く。
     * 改行の綴りは先に LF へ揃えるので、CR だけを送り込んで行頭へ戻し、直前の行を
     * 上書きするような小細工も通らない。
     *
     * @param text 整形する文字列（{@code null} 可）
     * @return 行の区切りを LF に揃え、制御文字を除き、前後の空白を落とした文字列。
     *     {@code null} は {@code null} のまま
     */
    public static String multiLine(String text) {
        // null はそのまま返す（呼び出し元の null 扱いを変えないため）
        if (text == null) {
            return null;
        }
        // 多様な綴りの改行をまず LF 1 種類へ揃える
        String normalized = LINE_SEPARATORS.matcher(text).replaceAll("\n");
        // 改行とタブ以外の制御文字を取り除き、前後の余白を落として返す。
        // strip() を掛けるのは oneLine() と揃えるため。これが無いと、空白だけの
        // メッセージが「空ではない」まま呼び出し元へ返り、safeMessage の
        // 「空ならクラス名へ落とす」判定を素通りしてしまう
        return CONTROL_CHARS_KEEPING_LINES.matcher(normalized).replaceAll("").strip();
    }

    /**
     * 端末をあやつる制御文字（ESC・BEL・CSI・DEL など）を文字列から取り除く。
     * 改行やタブも {@code \p{Cntrl}} に含まれるため「削除」される点に注意で、
     * 単語が繋がっては困る場所では {@link #oneLine(String)} を使う。
     *
     * @param value 対象の文字列（{@code null} 可）
     * @return 制御文字を除いた文字列。{@code null} は {@code null} のまま
     */
    public static String stripControlChars(String value) {
        // null はそのまま返す（呼び出し元の null 扱いを変えないため）
        if (value == null) {
            return null;
        }
        // 定数パターンに一致する制御文字をすべて削除した文字列を返す
        return CONTROL_CHARS.matcher(value).replaceAll("");
    }
}
