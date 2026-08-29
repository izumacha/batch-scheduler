package io.github.izumacha.batch.cli;

import io.github.izumacha.batch.model.JobStatus;
import io.github.izumacha.batch.text.SafeText;

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
     *
     * <p>整形は行の構造を残す {@link SafeText#multiLine(String)} で行い、1 行へは潰さない。
     * 潰すと SnakeYAML の構文エラーが壊れる — あの診断は該当行を引用して桁位置を
     * {@code ^} で指す形をしており、1 行にすると {@code ^} が何も指さなくなる。
     * バッチ定義ファイルの構文エラーはこのツールでもっとも普通に踏むエラーで、
     * {@code ValidateCommand} と {@code RunCommand} の設定読み込みはどちらもこの
     * メソッドの返り値をそのまま印字する。制御文字の除去は
     * {@link SafeText#oneLine(String)} と同等なので、注入に対する強度は変わらない
     * （残すのは改行とタブだけ）。
     *
     * <p>1 行に揃えたい呼び出し元（表のセル・原因を併記する
     * {@link #safeMessageWithCause(Throwable)}）は、返り値をさらに
     * {@link #sanitizeOneLine(String)} へ通す。
     */
    static String safeMessage(Throwable t) {
        // メッセージを整形する。例外のメッセージには外部由来の値が入りうる
        // （--rerun-failed のバッチ名不一致は state ファイルの batchName を、
        // NoSuchFile / AccessDenied はオフェンディングパスをそのまま本文にする）。
        // 呼び出し側で掛け忘れると生の ESC / BEL が端末へ届くので、名前が約束している
        // 「安全なメッセージ」をこのメソッド自身が満たすようにしておく
        String message = SafeText.multiLine(t.getMessage());
        // 整形した結果が空なら（メッセージが無い／空白のみ／整形すると消える文字だけ）
        // クラスの単純名へ落とす。判定を整形の「前」に置くと、非 null だが整形後に
        // 空になるメッセージがフォールバックを素通りし、"error: " だけの行になる —
        // 診断価値の無い表示を防ぐという、このメソッドが存在する理由そのものが消える
        if (message == null || message.isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return message;
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
        // 外側の例外のメッセージ（無ければクラス名）から組み立てを始める。
        // safeMessage は行の構造を残して返すので、ここで 1 行へ揃え直す。
        // このメソッドは「原因を ( ) で連ねた 1 行」を約束しており、複数行のまま
        // 先頭に置くと、続く「 (原因)」が最終行の末尾へぶら下がって読めなくなる。
        // 下の appendDetail が head と突き合わせて重複を抑えるのも、両方が同じ
        // 1 行の形になっていて初めて成立する
        String head = sanitizeOneLine(safeMessage(t));
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
        // 比較は両方とも整形した形で行う。head は safeMessage が整形済みで返す一方
        // detail は生の toString() なので、片方だけを整形した状態で比べると、
        // 原因のメッセージに前後の空白や改行が含まれるだけで一致しなくなり、
        // まったく同じ文が 2 回並ぶ（この抑止が存在する唯一の理由が消える）
        if (!sanitizeOneLine(detail).equals(head)) {
            rendered.append(" (").append(detail).append(')');
        }
    }

    /**
     * 表示用に run ID を整形する。値が無い（{@code null}・空白のみ）場合は
     * {@link #instant(Instant)} / {@link #duration(Duration)} と同じ
     * プレースホルダ {@code "-"} を返す。
     *
     * <p>整形せずに {@code printf} へ渡すと、runId を欠いた state ファイルの行が
     * 文字列 {@code "null"} として表示され、本当に {@code "null"} という ID を持つ
     * 実行と見分けが付かなくなる。運用者はそれを {@code --rerun-failed null} に
     * 貼って「見つからない」に行き当たる。「値が無い」ことを表す表記は
     * このクラスで 1 つに揃える（§6 一元管理）。
     *
     * <p>ただし曖昧さが完全に消えるわけではない: {@code "-"} 自体も
     * {@code fileFor} を通る正当な runId なので、{@code -.json} という記録が
     * あれば同じ見た目になる。それでもこの置き換えに意味があるのは、
     * (1) 「値が無い」を表す表記が {@link #instant(Instant)} /
     * {@link #duration(Duration)} と揃い、列をまたいで一貫した読み方ができること、
     * (2) 実際に起こりうるのは「runId を欠いた壊れた記録」の方であり、
     * {@code -.json} という記録が作られることは（生成される runId の形式が
     * {@code yyyyMMdd-HHmmss-XXXXXX} である以上）まず無いこと、の 2 点による。
     *
     * <p>値がある場合は他の列と同じ {@link #sanitizeOneLine(String)} を通す。
     * runId は state ファイル由来の信頼できない値で、切り詰めはしない。
     */
    static String runId(String runId) {
        // null は先に弾く（整形に渡せないため）
        if (runId == null) {
            return PLACEHOLDER;
        }
        // 先に整形してから空かどうかを見る。isBlank() で判定してしまうと、
        // 空白ではないが表示されない文字だけの ID（bidi 制御の U+202E など。
        // 改変された state ファイルから来うる）が整形後に空文字となり、
        // 36 桁の空白として描画される。運用者には「表示バグ」と区別が付かず、
        // --rerun-failed へ貼るものも得られない — このメソッドが防ぐはずの行き止まり
        String rendered = sanitizeOneLine(runId);
        if (rendered.isEmpty()) {
            return PLACEHOLDER;
        }
        return rendered;
    }

    /**
     * 表のセル用に、切り詰めたうえで「値が無い」ことをプレースホルダで示す。
     *
     * <p>{@link #shortMessage(String, int)} は値が無いとき空文字を返す。ジョブの
     * メッセージ欄のように「無いのが普通」の列ではそれでよいが、一覧表のように
     * 必ず値があるはずの列では、空白だけのセルが「表示バグ」と区別が付かない
     * （{@link #runId(String)} の Javadoc と同じ理由）。「値が無い」ことを表す表記は
     * このクラスで 1 つに揃える（§6 一元管理）。
     */
    static String requiredCell(String value, int max) {
        // まずは通常の切り詰め整形を行う
        String shortened = shortMessage(value, max);
        // 何も残らなければプレースホルダへ落とす
        if (shortened.isEmpty()) {
            return PLACEHOLDER;
        }
        return shortened;
    }

    /**
     * 表示用に実行ステータスを整形する。値が無い場合は {@link #instant(Instant)} /
     * {@link #duration(Duration)} / {@link #runId(String)} と同じプレースホルダ
     * {@code "-"} を返す。
     *
     * <p>{@code status} を欠いた state ファイルは、{@code runId} を欠いたものと
     * 同じように読み込める（{@code ExecutionResult} の正規コンストラクタが既定値を
     * 与えるのは {@code jobResults} だけ）。整形せずに {@code printf} へ渡すと
     * 文字列 {@code "null"} が列に並び、「値が無い」ことを表す表記がこのクラスの
     * 他の列と食い違う（§6 一元管理）。
     */
    static String status(JobStatus status) {
        // 値が無い場合はプレースホルダを返す
        if (status == null) {
            return PLACEHOLDER;
        }
        // enum の名前をそのまま返す（外部由来の文字列ではないので整形は要らない）
        return status.name();
    }

    /**
     * 端末へ出す文字列を 1 行へ整形し、制御文字を取り除く。
     *
     * <p>整形の規則と、その順序が肝である理由は {@link SafeText#oneLine(String)} が持つ。
     * ここに書き写さないのは、説明が 2 か所に分かれると実装と食い違ったまま片方だけが
     * 残りうるため（§6 DRY）。この薄いラッパーは、{@code cli} 側の呼び出し元が
     * {@code CliFormat} の名前空間だけを見ていれば済むようにするためだけに置いている。
     *
     * @see SafeText#oneLine(String)
     */
    static String sanitizeOneLine(String text) {
        // 実装は text パッケージの共有ユーティリティへ委譲する。state パッケージの
        // ログ出力も同じ規則を通す必要があり（既定の ConsoleHandler は CLI と同じ
        // stderr へ出す）、そちらから cli を参照させると層が逆転するため
        return SafeText.oneLine(text);
    }

    /**
     * テーブル表示用に null かもしれないメッセージを最大 {@code max} 文字に切り詰める。
     * ジョブ出力由来の信頼できない文字列が渡るため、空白の圧縮に加えて
     * {@link #sanitizeOneLine(String)} で端末制御文字も除去する（唯一のチョークポイント）。
     *
     * <p>切り詰めマーカーは {@link #TRUNCATION_MARK}（ASCII）で、戻り値の長さは
     * 必ず {@code max} 以下に収まる（表の桁ずれを防ぐため）。
     */
    static String shortMessage(String message, int max) {
        // 1 行へ整形し、端末制御文字を取り除く（順序の理由は sanitizeOneLine を参照）。
        // 空判定は整形の「後」に行う。前に置くと、空白ではないが整形すると消える文字
        // だけの値（bidi 制御の U+202E など）が isBlank() をすり抜けて空文字になり、
        // 呼び出し側は「値がある」と思ったまま空のセルを描くことになる
        String oneLine = sanitizeOneLine(message);
        // 値が無い（null・空白のみ・整形すると消える）場合は空文字を返す
        if (oneLine == null || oneLine.isEmpty()) {
            return "";
        }
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

}
