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
            Pattern.compile("[\\s\\u0085\\u2028\\u2029]+");

    // インスタンス生成を禁止するためのプライベートコンストラクタ（ユーティリティクラス）
    private SafeText() {
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
        String collapsed = WHITESPACE.matcher(text).replaceAll(" ").trim();
        // 空白圧縮後に残った ESC・BEL などの制御文字を取り除き、端末への注入を防ぐ
        return stripControlChars(collapsed);
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
