package io.github.izumacha.batch.cli;

// アサーション（assertEquals 等）を静的インポートする
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
// 正規表現マッチのアサーションに使う
import static org.junit.jupiter.api.Assertions.assertTrue;

// 切り詰めマーカーが US-ASCII で符号化できるかを検査するために使う
import java.nio.charset.StandardCharsets;
// 期間（Duration）を生成するために使う
import java.time.Duration;
// 時刻（Instant）を生成するために使う
import java.time.Instant;
// テストメソッドであることを示すアノテーション
import org.junit.jupiter.api.Test;

/**
 * CliFormat.duration の整形を検証するユニットテスト。
 * 特に「秒が分境界の直前で 0.1 秒へ丸め上がるケース」を回帰テストとして固定する。
 */
class CliFormatTest {

    /** 1000ms 未満はミリ秒表記になることを確認する */
    @Test
    void duration_subSecond_showsMillis() {
        // 850ms は "850ms" と表示される
        assertEquals("850ms", CliFormat.duration(Duration.ofMillis(850)));
    }

    /** null・ゼロ・負値はすべて "0ms" になることを確認する */
    @Test
    void duration_nullZeroNegative_returnsZeroMs() {
        // null は "0ms"
        assertEquals("0ms", CliFormat.duration(null));
        // ゼロは "0ms"
        assertEquals("0ms", CliFormat.duration(Duration.ZERO));
        // 負の値も "0ms"
        assertEquals("0ms", CliFormat.duration(Duration.ofMillis(-5)));
    }

    /** 1分未満の通常ケースで「X.Xs」表記になることを確認する */
    @Test
    void duration_underOneMinute_formatsSeconds() {
        // 1.0 秒ちょうど
        assertEquals("1.0s", CliFormat.duration(Duration.ofMillis(1000)));
        // 1m03.4s の元になる 63.4 秒は 1 分以上なので別テストで確認する。ここは 3.4 秒
        assertEquals("3.4s", CliFormat.duration(Duration.ofMillis(3400)));
        // 59.9 秒（分へ繰り上がらない上限付近）
        assertEquals("59.9s", CliFormat.duration(Duration.ofMillis(59_900)));
    }

    /** 1分以上の通常ケースで「Xm0S.Ts」表記（秒は 2 桁ゼロ埋め）になることを確認する */
    @Test
    void duration_overOneMinute_formatsMinutesAndSeconds() {
        // 1 分 3.4 秒
        assertEquals("1m03.4s", CliFormat.duration(Duration.ofMillis(63_400)));
        // 2 分 0.0 秒ちょうど
        assertEquals("2m00.0s", CliFormat.duration(Duration.ofMillis(120_000)));
    }

    /**
     * 回帰テスト: 秒の小数部が 0.95 以上のとき、0.1 秒へ丸め上がっても
     * 分桁へ正しく繰り上がること（旧実装は "1m60.0s" / "60.0s" を返していた）。
     */
    @Test
    void duration_roundsUpAcrossMinuteBoundary() {
        // 119.95 秒 → 2m00.0s（旧実装は誤って "1m60.0s"）
        assertEquals("2m00.0s", CliFormat.duration(Duration.ofMillis(119_950)));
        // 59.95 秒 → 1m00.0s（旧実装は誤って "60.0s"）
        assertEquals("1m00.0s", CliFormat.duration(Duration.ofMillis(59_950)));
        // 59.95 秒の直前（59.94 秒）は繰り上がらず 59.9s のまま
        assertEquals("59.9s", CliFormat.duration(Duration.ofMillis(59_940)));
    }

    /**
     * 回帰テスト: ミリ秒換算が long を桁あふれするほど巨大な Duration でも
     * 例外を漏らさずプレースホルダ "-" を返すこと（旧実装は ArithmeticException を
     * 投げ、list コマンドのテーブル描画が途中で打ち切られて終了コード 3 になっていた）。
     */
    @Test
    void duration_overflowingToMillis_returnsPlaceholder() {
        // Long.MAX_VALUE 秒（toMillis() が必ず桁あふれする値）はプレースホルダになる
        assertEquals("-", CliFormat.duration(Duration.ofSeconds(Long.MAX_VALUE)));
        // 桁あふれ境界のわずかに上（Long.MAX_VALUE ミリ秒 + 1ms 相当）もプレースホルダになる
        assertEquals("-", CliFormat.duration(
                Duration.ofMillis(Long.MAX_VALUE).plusMillis(1)));
    }

    /** 桁あふれしないギリギリの巨大な Duration は従来どおり通常整形されることを確認する */
    @Test
    void duration_hugeButNotOverflowing_stillFormats() {
        // Long.MAX_VALUE ミリ秒ちょうどは桁あふれしないため "Xm..s" 形式で整形される
        String formatted = CliFormat.duration(Duration.ofMillis(Long.MAX_VALUE));
        // 「分+秒」形式（例: "153722867280912m55.8s"）で返ることを確認する
        assertTrue(formatted.matches("\\d+m\\d{2}\\.\\ds"), formatted);
    }

    /** 通常の Instant は「yyyy-MM-dd HH:mm:ss」形式に整形されることを確認する */
    @Test
    void instant_normal_formatsTimestamp() {
        // 2026-01-02T03:04:05Z を整形する（表示はシステムタイムゾーン依存のため形式のみ検証する）
        String formatted = CliFormat.instant(Instant.parse("2026-01-02T03:04:05Z"));
        // 「4桁年-2桁月-2桁日 時:分:秒」の形式になっていることを確認する
        assertTrue(formatted.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), formatted);
    }

    /** null の Instant はプレースホルダ "-" になることを確認する */
    @Test
    void instant_null_returnsPlaceholder() {
        // null はハイフンで表示される
        assertEquals("-", CliFormat.instant(null));
    }

    /**
     * 回帰テスト: ローカル日時へ変換できない極端な Instant でも例外を漏らさず
     * プレースホルダ "-" を返すこと（旧実装は DateTimeException を投げ、
     * 壊れた state ファイル 1 件で list の一覧全体が中断して終了コード 3 になっていた）。
     */
    @Test
    void instant_extremeValues_returnPlaceholder() {
        // Instant.MIN（負の 10 億年）はタイムゾーン変換で EpochDay の下限を踏み越えるため
        // プレースホルダになる
        assertEquals("-", CliFormat.instant(Instant.parse("-1000000000-01-01T00:00:00Z")));
        // Instant.MAX（+10 億年）も同様に EpochDay の上限を踏み越えるためプレースホルダになる
        assertEquals("-", CliFormat.instant(Instant.MAX));
    }

    /** メッセージを持つ例外はそのメッセージをそのまま返すことを確認する */
    @Test
    void safeMessage_withMessage_returnsMessage() {
        assertEquals("boom", CliFormat.safeMessage(new IllegalStateException("boom")));
    }

    /**
     * メッセージを持たない例外（getMessage() が null）では、診断価値の無い
     * "null" ではなく例外クラスの単純名を返すことを確認する（RunCommand/ListCommand の
     * 各 catch 節と BatchCli の最終防波堤ハンドラが共有するフォールバック）。
     */
    @Test
    void safeMessage_withoutMessage_returnsClassSimpleName() {
        assertEquals("IllegalStateException", CliFormat.safeMessage(new IllegalStateException()));
    }

    /**
     * 原因を持つ例外では、外側のメッセージに「 (原因)」が併記されることを確認する。
     * JsonExecutionStore.save は「記録は公開済みだが耐久性を確認できなかった」という
     * 区別を原因側にしか持たせていないため、ここが落ちると運用者には
     * 「保存できなかった」としか伝わらず、実際には残っている記録に対して
     * バッチ全体の再実行へ誘導してしまう。
     */
    @Test
    void safeMessageWithCause_appendsTheCause() {
        // 原因付きの例外を組み立てる（save が投げる形と同じ入れ子）
        Exception cause = new java.io.IOException("record published but unconfirmed");
        Exception outer = new java.io.UncheckedIOException(
                "failed to save execution result", (java.io.IOException) cause);
        // 外側のメッセージと原因の両方が 1 行に含まれる
        String rendered = CliFormat.safeMessageWithCause(outer);
        assertTrue(rendered.contains("failed to save execution result"), rendered);
        assertTrue(rendered.contains("record published but unconfirmed"), rendered);
    }

    /**
     * 外側にメッセージを渡さずに包んだ例外（Throwable が原因の toString() を
     * そのまま detailMessage に採用する形）では、同じ文が 2 回並ばないことを確認する。
     * JsonExecutionStore のシンボリックリンク拒否がこの形をしており、無条件に
     * 原因を足すと「... /x (java.io.IOException: ... /x)」になっていた。
     */
    @Test
    void safeMessageWithCause_doesNotRepeatACauseAlreadyInTheMessage() {
        // メッセージを渡さずに包む（detailMessage は原因の toString() になる）
        Exception cause = new java.io.IOException("refusing to use a symlinked state directory: /x");
        Exception outer = new java.io.UncheckedIOException((java.io.IOException) cause);
        // 併記されるのは 1 回だけで、丸括弧での繰り返しは付かない
        String rendered = CliFormat.safeMessageWithCause(outer);
        assertEquals(cause.toString(), rendered);
    }

    /**
     * 原因が 2 段以上ある場合に、根元まで併記されることを確認する。
     * 非アトミック移動の経路は save が「記録は公開済みだが耐久性を確認できなかった」を
     * 挟んで包み直すため 3 段になり、1 段だけ辿る実装では ENOSPC 等の実際の理由が
     * 落ちていた（説明を足した経路ほど理由が消える、という逆転になっていた）。
     */
    @Test
    void safeMessageWithCause_walksTheWholeCauseChain() {
        // 3 段の入れ子を作る（save がこの経路で作る形と同じ）
        java.io.IOException root = new java.io.IOException("No space left on device");
        java.io.IOException middle =
                new java.io.IOException("the record was published but could not be confirmed durable", root);
        Exception outer = new java.io.UncheckedIOException("failed to save execution result", middle);
        // 3 段すべての情報が 1 行に含まれる
        String rendered = CliFormat.safeMessageWithCause(outer);
        assertTrue(rendered.contains("failed to save execution result"), rendered);
        assertTrue(rendered.contains("could not be confirmed durable"), rendered);
        assertTrue(rendered.contains("No space left on device"), rendered);
    }

    /**
     * 原因の連鎖が上限より深い場合に、打ち切ったことが出力に残ることを確認する。
     * 黙って落とすと、このメソッドが直したはずの「根本原因が消えているのに、
     * 消えたことも分からない」状態に戻る。
     */
    @Test
    void safeMessageWithCause_marksThatItTruncatedALongChain() {
        // 上限（5 段）を超える深さの連鎖を作る
        Throwable deepest = new IllegalStateException("root");
        Throwable current = deepest;
        for (int i = 0; i < 8; i++) {
            current = new IllegalStateException("layer" + i, current);
        }
        // 打ち切った事実が出力に含まれる
        String rendered = CliFormat.safeMessageWithCause(current);
        assertTrue(rendered.contains("further causes omitted"), rendered);
        // 中間の包みは省かれている（＝実際に打ち切られている）
        assertFalse(rendered.contains("layer1,"), rendered);
        assertFalse(rendered.contains("layer0)"), rendered);
        // それでも根元だけは印つきで載る。印だけ付けて終えると「打ち切ったことは
        // 分かるが理由は分からない」になり、このメソッドの存在意義が消える
        assertTrue(rendered.contains("root cause: java.lang.IllegalStateException: root"),
                rendered);
    }

    /**
     * Java の {@code \s} が拾わない改行文字（U+0085 NEL）も区切りとして圧縮され、
     * 単語が繋がらないことを確認する。これらは制御文字パターン側では「削除」対象
     * なので、圧縮側に足しておかないと 3 文字だけが除去へ回って
     * "line onefile not found" のような化けを起こす。
     */
    @Test
    void safeMessageWithCause_collapsesUnicodeLineSeparatorsToo() {
        // \s に含まれない改行（NEL・行区切り・段落区切り）を挟んだメッセージを作る
        Exception cause = new java.io.IOException("line oneline two line three end");
        Exception outer = new java.io.UncheckedIOException(
                "failed to save execution result", (java.io.IOException) cause);
        String rendered = CliFormat.safeMessageWithCause(outer);
        // どれもスペースへ圧縮され、単語が繋がっていない
        assertTrue(rendered.contains("line one line two line three end"), rendered);
    }

    /** 循環した原因の連鎖でも、根元の探索が止まって戻ってくることを確認する */
    @Test
    void safeMessageWithCause_terminatesOnACyclicCauseChain() {
        // getCause() が自分自身へ戻る連鎖を用意する（壊れた例外実装の模擬）
        Throwable[] holder = new Throwable[1];
        Throwable cyclic = new IllegalStateException("cyclic") {
            @Override
            public synchronized Throwable getCause() {
                // 常に次の要素を返し続けることで無限の連鎖に見せる
                return holder[0];
            }
        };
        holder[0] = cyclic;
        // 上限で必ず止まるので、呼び出しは有限時間で戻る
        String rendered = CliFormat.safeMessageWithCause(cyclic);
        assertTrue(rendered.contains("further causes omitted"), rendered);
        // 根元まで下りられていないので「根本原因」とは名乗らない。途中の包みを
        // 根本原因と言い切ると、運用者を間違った失敗の調査へ送り出してしまう
        assertFalse(rendered.contains("root cause:"), rendered);
        assertTrue(rendered.contains("deepest cause reached:"), rendered);
    }

    /**
     * セキュリティ回帰テスト: 原因のメッセージに含まれる端末制御文字が除去されることを
     * 確認する。NoSuchFile / AccessDenied はオフェンディングパスをそのままメッセージに
     * するため、外部から与えられたパスが端末へ生で出る経路になりうる。
     * 本クラスは stripControlChars を「唯一のチョークポイント」と位置づけている。
     */
    @Test
    void safeMessageWithCause_stripsTerminalControlCharactersFromCauses() {
        // 端末制御文字（ESC・BEL）を含むパスを持つ原因を組み立てる
        Exception cause = new java.nio.file.NoSuchFileException("/srv/\u001b]0;pwned\u0007state");
        Exception outer = new java.io.UncheckedIOException(
                "failed to save execution result", (java.io.IOException) cause);
        // 生の ESC / BEL が出力へ漏れていない
        String rendered = CliFormat.safeMessageWithCause(outer);
        assertFalse(rendered.contains("\u001b"), rendered);
        assertFalse(rendered.contains("\u0007"), rendered);
        // 診断に必要なパスそのものは残っている
        assertTrue(rendered.contains("state"), rendered);
    }

    /**
     * 添えられた診断（addSuppressed）が併記されることを確認する。
     * 保存側は「アトミック移動が使えなかった理由」「誤配置ファイルを削除できなかった
     * こと」などを主因ではない情報として addSuppressed で運んでいる。ここで描画
     * しなければ、集めているだけで誰にも届かない情報になる。
     */
    @Test
    void safeMessageWithCause_rendersSuppressedDiagnostics() {
        // 主因に、判断材料となる別の失敗を添える（保存側と同じ形）
        java.io.IOException primary = new java.io.IOException("fallback move failed");
        primary.addSuppressed(new java.io.IOException("atomic move unsupported"));
        Exception outer = new java.io.UncheckedIOException("failed to save execution result", primary);
        // 主因も添えられた側も 1 行に含まれる
        String rendered = CliFormat.safeMessageWithCause(outer);
        assertTrue(rendered.contains("fallback move failed"), rendered);
        assertTrue(rendered.contains("atomic move unsupported"), rendered);
        // 主因と区別できる印が付いている
        assertTrue(rendered.contains("also:"), rendered);
    }

    /**
     * 改行やタブが「削除」されて前後の単語が繋がらないことを確認する。
     * 改行・タブは stripControlChars の \p{Cntrl} に含まれるため、空白の圧縮を
     * 先に済ませておかないと "line one" と "line two" が "line oneline two" のように
     * 別語へ化ける。原因のメッセージは外部由来（ジョブ出力・OS のエラー文）で
     * 改行を含みうるので、診断が読めなくなる実害がある。
     */
    @Test
    void safeMessageWithCause_collapsesWhitespaceInsteadOfFusingWords() {
        // 改行とタブを含むメッセージを持つ原因を組み立てる
        Exception cause = new java.io.IOException("line one\nline two\tline three");
        Exception outer = new java.io.UncheckedIOException(
                "failed to save execution result", (java.io.IOException) cause);
        String rendered = CliFormat.safeMessageWithCause(outer);
        // 単語の境界がスペースとして残っている（＝繋がっていない）
        assertTrue(rendered.contains("line one line two line three"), rendered);
        // 生の改行・タブは出力へ漏れていない（1 行であることの担保）
        assertFalse(rendered.contains("\n"), rendered);
        assertFalse(rendered.contains("\t"), rendered);
    }

    /**
     * 重複の判定が「完全一致」で行われ、部分一致では行われないことを確認する。
     * 外側が内側の文言を引用したうえで説明を足す包み方をすると、内側の原因は
     * 外側の部分文字列になる。含有で判定していると別物である内側が黙って消え、
     * しかも打ち切りの印も付かないため「根本原因が消えたことも分からない」状態に戻る。
     */
    @Test
    void safeMessageWithCause_dedupesByExactMatchNotContainment() {
        // 内側の文言をそのまま含み、さらに説明を足したメッセージで包む
        Exception inner = new java.io.IOException("could not sync the file");
        Exception outer = new java.io.UncheckedIOException(
                new java.io.IOException("could not sync the file at /srv/state", inner));
        String rendered = CliFormat.safeMessageWithCause(outer);
        // 内側の原因が「部分文字列だから」という理由で落とされていない
        assertTrue(rendered.contains("(java.io.IOException: could not sync the file)"), rendered);
    }

    /** 原因を持たない例外では、メッセージだけがそのまま返ることを確認する */
    @Test
    void safeMessageWithCause_withoutCause_returnsMessageOnly() {
        assertEquals("boom", CliFormat.safeMessageWithCause(new IllegalStateException("boom")));
    }

    /**
     * セキュリティ回帰テスト: ジョブ出力由来のメッセージに含まれる端末制御文字
     * （ESC・BEL・CSI 等）が除去され、生の 0x1B / 0x07 が表示文字列へ漏れないこと
     * （旧実装は空白しか圧縮せず、タイトル偽装・文字消去などの端末注入を許していた）。
     */
    @Test
    void shortMessage_stripsTerminalControlCharacters() {
        // OSC タイトル偽装（ESC ] 0 ; ... BEL）と CSI 画面消去（ESC [ 2 J）を含む攻撃的な入力
        String hostile = "\u001B]0;evil\u0007ok \u001B[2Jdone";
        // サニタイズ後の表示文字列を取得する
        String sanitized = CliFormat.shortMessage(hostile, 60);
        // ESC（0x1B）が残っていないことを確認する
        assertTrue(sanitized.indexOf('\u001B') < 0, sanitized);
        // BEL（0x07）が残っていないことを確認する
        assertTrue(sanitized.indexOf('\u0007') < 0, sanitized);
        // 制御文字だけが消え、可視文字はそのまま残ることを確認する
        assertEquals("]0;evilok [2Jdone", sanitized);
    }

    /** DEL（0x7F）と C1 制御文字（CSI=0x9B 等）も除去されることを確認する */
    @Test
    void shortMessage_stripsDelAndC1Controls() {
        // DEL と 1 バイト CSI（U+009B）を含む入力がどちらも除去される
        assertEquals("ab31mred", CliFormat.shortMessage("a\u007Fb\u009B31mred", 60));
    }

    /** 制御文字を含む長いメッセージでも、切り詰め（最大文字数＋切り詰めマーカー）が従来どおり働くこと */
    @Test
    void shortMessage_truncationStillWorksAfterSanitization() {
        // ESC を混ぜた 70 文字超の入力を用意する（サニタイズ後は "[31m"＋"x"×70 の 74 文字）
        String longMessage = "\u001B[31m" + "x".repeat(70);
        // 60 文字上限で切り詰めた結果を取得する
        String truncated = CliFormat.shortMessage(longMessage, 60);
        // 全体の長さが上限の 60 文字ちょうどになることを確認する
        assertEquals(60, truncated.length());
        // 末尾が ASCII の切り詰めマーカー "..." で終わることを確認する
        assertTrue(truncated.endsWith("..."), truncated);
    }

    /**
     * 回帰防止: 切り詰めマーカーが ASCII だけで構成されていること（DESIGN.md の
     * 「ASCII-only CLI diagnostics」）。以前は省略記号 U+2026 を使っていたため、
     * LANG 未設定の C/POSIX ロケール（Docker の JDK ベースイメージや CI コンテナの既定）では
     * System.out の符号化が US-ASCII になってマーカーが "?" へ潰れ、"some messag?" のように
     * 「切り詰めの注記」なのか「出力の文字化け」なのか利用者が区別できなかった。
     */
    @Test
    void shortMessage_truncationMarkIsAsciiOnlySoItSurvivesAnyLocale() {
        // ASCII だけの入力を上限超えの長さで与える
        // （入力側に非 ASCII を混ぜないことで、マーカー由来の非 ASCII だけを検出できる）
        String truncated = CliFormat.shortMessage("x".repeat(100), 60);
        // 戻り値全体が US-ASCII で符号化できることを確認する
        // （1 文字でも非 ASCII が混じると US-ASCII 出力時に "?" へ潰れて情報が失われる）
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(truncated), truncated);
    }

    /**
     * 境界値: 上限がマーカー（3 文字）以下のときはマーカーを付けずに単純に切り、
     * 戻り値が上限を超えない（表の桁が崩れない）こと。
     */
    @Test
    void shortMessage_maxSmallerThanTruncationMarkStillRespectsLimit() {
        // 上限 3（マーカーと同じ長さ）: マーカーを足すと 6 文字になってしまうため付けない
        assertEquals("abc", CliFormat.shortMessage("abcdef", 3));
        // 上限 1: 1 文字だけに切り詰められる
        assertEquals("a", CliFormat.shortMessage("abcdef", 1));
        // 上限 0: 空文字になる（負の substring で例外を投げないことも兼ねて確認する）
        assertEquals("", CliFormat.shortMessage("abcdef", 0));
    }

    /** 制御文字を含まない日本語の通常メッセージは変化しないこと（偽陽性の除去がないこと） */
    @Test
    void shortMessage_plainJapaneseTextPassesUnchanged() {
        // 日本語のエラーメッセージがそのまま返る
        assertEquals("ジョブが失敗しました: 終了コード 1",
                CliFormat.shortMessage("ジョブが失敗しました: 終了コード 1", 60));
    }

    /**
     * セキュリティ回帰テスト: Unicode の双方向テキスト制御文字（bidi override:
     * U+202A〜U+202E、isolate: U+2066〜U+2069。いずれもカテゴリ Cf）が除去されること。
     * 旧パターン（\p{Cntrl} と C1 のみ）はこれらを素通ししており、ジョブ出力が
     * run/list のサマリー表の文字列を視覚的に並べ替えて表示内容を偽装できていた。
     */
    @Test
    void shortMessage_stripsBidiFormatCharacters() {
        // RLO（右→左上書き: U+202E）と PDF（U+202C）で表示順を偽装する攻撃的な入力
        // （不可視文字をソースへ直接埋め込まず、\ u エスケープで明示する）
        String hostile = "ok\u202Edetroba\u202C end";
        // サニタイズ後の表示文字列を取得する
        String sanitized = CliFormat.shortMessage(hostile, 60);
        // RLO（U+202E）が残っていないことを確認する
        assertTrue(sanitized.indexOf('\u202E') < 0, sanitized);
        // PDF（U+202C）が残っていないことを確認する
        assertTrue(sanitized.indexOf('\u202C') < 0, sanitized);
        // 制御文字だけが消え、可視文字はそのまま残ることを確認する
        assertEquals("okdetroba end", sanitized);
        // isolate 系（LRI=U+2066・RLI=U+2067・FSI=U+2068・PDI=U+2069）も同様に除去される
        assertEquals("abcd", CliFormat.shortMessage("a\u2066b\u2067c\u2068d\u2069", 60));
    }

    /** stripControlChars 単体でも Cf（フォーマット文字）が除去されることを確認する（runId 表示経路） */
    @Test
    void stripControlChars_stripsBidiAndFormatCharacters() {
        // runId 風の文字列に RLO（U+202E）と別種の Cf 文字（ソフトハイフン U+00AD）を混ぜる
        assertEquals("run-01-abc",
                CliFormat.stripControlChars("run\u202E-01\u00AD-abc"));
        // isolate 制御（U+2066）も除去されることを確認する
        assertEquals("xy", CliFormat.stripControlChars("x\u2066y"));
    }

    /**
     * ListCommand の runId 表示が使う stripControlChars 単体の挙動:
     * 制御文字だけを除去し、切り詰めは行わないこと。null は null のまま返すこと。
     */
    @Test
    void stripControlChars_removesControlsWithoutTruncating() {
        // UUID 風の runId に ESC シーケンスを混ぜても制御文字だけが消える
        assertEquals("0123456789abcdef-0123-0123[31m-esc",
                CliFormat.stripControlChars("0123456789abcdef-0123-0123\u001B[31m-esc"));
        // 長い文字列でも切り詰めは発生しない（65 文字がそのまま返る）
        assertEquals(65, CliFormat.stripControlChars("y".repeat(65)).length());
        // null は null のまま返す
        assertEquals(null, CliFormat.stripControlChars(null));
    }
}
