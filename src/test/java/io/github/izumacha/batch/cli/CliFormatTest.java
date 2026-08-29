package io.github.izumacha.batch.cli;

import io.github.izumacha.batch.text.SafeText;

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
     * セキュリティ回帰テスト: safeMessage 自身が制御文字を除去することを確認する。
     * 例外のメッセージには外部由来の値が入る（--rerun-failed のバッチ名不一致は
     * state ファイルの batchName をそのまま本文にする）ため、呼び出し側の掛け忘れが
     * そのまま端末への注入になる。名前が約束している「安全なメッセージ」を
     * このメソッド自身が満たすようにしている。
     */
    @Test
    void safeMessage_stripsTerminalControlCharacters() {
        // 端末制御文字（ESC・BEL）を含むメッセージを持つ例外を作る
        Exception e = new IllegalArgumentException(
                "priorResult belongs to a different batch ('etl\u001b]0;pwned\u0007')");
        String rendered = CliFormat.safeMessage(e);
        // 生の ESC / BEL は出力へ漏れていない
        assertFalse(rendered.contains("\u001b"), rendered);
        assertFalse(rendered.contains("\u0007"), rendered);
        // 診断に必要な文言そのものは残っている
        assertTrue(rendered.contains("different batch"), rendered);
    }

    /**
     * runId が無い記録では、文字列 "null" ではなく他の列と同じプレースホルダ "-" を
     * 返すことを確認する。"null" のままだと、本当に "null" という ID の実行と
     * 見分けが付かず、運用者が --rerun-failed null に貼って行き止まりになる。
     */
    @Test
    void runId_missingValue_returnsPlaceholder() {
        assertEquals("-", CliFormat.runId(null));
        assertEquals("-", CliFormat.runId("   "));
        // 空白ではないが整形すると消える文字だけの ID もプレースホルダになる。
        // 空文字のまま返すと 36 桁の空白として描画され、運用者には表示バグと
        // 区別が付かず、--rerun-failed へ貼るものも得られない
        assertEquals("-", CliFormat.runId("\u202E"));
        assertEquals("-", CliFormat.runId("\u0007"));
        // 値があるときは 1 行へ整形して返す（切り詰めはしない）
        assertEquals("run1 bbb", CliFormat.runId("run1\nbbb"));
    }

    /**
     * 整形すると空になるメッセージでも、クラス名へ落ちて「error: 」だけの行に
     * ならないことを確認する。判定を整形の前に置くと、非 null だが表示すると
     * 消えるメッセージがフォールバックを素通りし、診断価値の無い表示を防ぐという
     * このメソッドの存在理由そのものが消える。
     */
    @Test
    void safeMessage_messageThatSanitizesToEmpty_fallsBackToClassName() {
        // 空白ではないが整形すると消える文字だけのメッセージ（bidi 制御）
        assertEquals("IllegalStateException",
                CliFormat.safeMessage(new IllegalStateException("\u202E")));
        // 空白のみのメッセージも同じ
        assertEquals("IllegalStateException",
                CliFormat.safeMessage(new IllegalStateException("   ")));
    }

    /**
     * 必ず値があるはずの列では、値が無いときに空白ではなくプレースホルダを返すことを
     * 確認する。空白だけのセルは「表示バグ」と区別が付かず、runId の Javadoc が
     * 挙げているのと同じ行き止まりになる。
     */
    @Test
    void requiredCell_missingValue_returnsPlaceholder() {
        assertEquals("-", CliFormat.requiredCell(null, 20));
        assertEquals("-", CliFormat.requiredCell("   ", 20));
        // 空白ではないが整形すると消える値もプレースホルダになる
        assertEquals("-", CliFormat.requiredCell("\u202E", 20));
        // 値があるときは通常どおり切り詰めて返す
        assertEquals("etl", CliFormat.requiredCell("etl", 20));
    }

    /** ステータスが無い記録では "null" ではなくプレースホルダ "-" を返すことを確認する */
    @Test
    void status_missingValue_returnsPlaceholder() {
        assertEquals("-", CliFormat.status(null));
        assertEquals("SUCCEEDED",
                CliFormat.status(io.github.izumacha.batch.model.JobStatus.SUCCEEDED));
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
     * 原因のメッセージに前後の空白が含まれていても、同じ文が 2 回並ばないことを確認する。
     * 冒頭は safeMessage が整形済みで返すため、比較の片側だけを整形していると
     * 空白の有無だけで一致しなくなり、この抑止が存在する理由そのものが消える。
     */
    @Test
    void safeMessageWithCause_dedupesEvenWhenTheCauseHasSurroundingWhitespace() {
        // 末尾に空白を含むメッセージで包む（--state-dir "foo " のような正当な入力で起きる）
        Exception cause = new java.io.IOException("refusing to use a symlinked state directory: /x ");
        Exception outer = new java.io.UncheckedIOException((java.io.IOException) cause);
        String rendered = CliFormat.safeMessageWithCause(outer);
        // 併記は 1 回だけ（丸括弧での繰り返しが付かない）
        assertFalse(rendered.contains("("), rendered);
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

    /**
     * 連鎖がちょうど上限 +1 段のとき、実際には何も落ちていないので打ち切りの印を
     * 付けないことを確認する。無条件に付けると「落ちていないのに落ちたと告げる」
     * ことになり、運用者は存在しない情報を探しに行く。
     */
    @Test
    void safeMessageWithCause_doesNotClaimTruncationWhenNothingWasDropped() {
        // 原因が上限（5 段）+ 1 段ちょうどの連鎖を作る（打ち切った先が根元そのものになる）
        Throwable root = new IllegalStateException("REALROOT");
        Throwable current = root;
        for (int i = 0; i < 6; i++) {
            current = new IllegalStateException("layer" + i, current);
        }
        String rendered = CliFormat.safeMessageWithCause(current);
        // 根元は根元として載る
        assertTrue(rendered.contains("root cause: java.lang.IllegalStateException: REALROOT"),
                rendered);
        // 落ちた段は無いので印は付かない
        assertFalse(rendered.contains("further causes omitted"), rendered);
    }

    /**
     * 別々の失敗がたまたま同じ文字列に描画されても、2 件目が黙って消えないことを
     * 確認する。全体との完全一致で抑止すると、印も付かずに診断が 1 件消える。
     */
    @Test
    void safeMessageWithCause_keepsDistinctSuppressedThatRenderIdentically() {
        // 同じ文面になる別々の失敗を 2 件添える（別々のステップの AccessDenied 等の模擬）
        java.io.IOException primary = new java.io.IOException("primary");
        primary.addSuppressed(new java.io.IOException("same"));
        primary.addSuppressed(new java.io.IOException("same"));
        Exception outer = new java.io.UncheckedIOException("failed to save execution result", primary);
        String rendered = CliFormat.safeMessageWithCause(outer);
        // "also:" が 2 回出る（＝ 2 件目が黙って消えていない）
        int first = rendered.indexOf("also:");
        assertTrue(first >= 0, rendered);
        assertTrue(rendered.indexOf("also:", first + 1) > first, rendered);
    }

    /**
     * エラー行が有界であることを確認する。例外のメッセージには state ファイル由来の
     * 値がそのまま入り（--rerun-failed のバッチ名不一致は記録の batchName を本文にする）、
     * 記録 1 件の上限は 16MiB なので、切らないとそれを丸ごと 1 行で stderr へ吐ける。
     * このツールは他のあらゆる外部由来の出力を有界にしているので、ここも揃える。
     */
    @Test
    void safeMessageWithCause_boundsEachSegmentSoATamperedRecordCannotFloodStderr() {
        // 極端に長いメッセージを持つ例外を、原因付きで組み立てる
        String huge = "x".repeat(100_000);
        Exception cause = new java.io.IOException(huge);
        Exception outer = new java.io.UncheckedIOException(huge, (java.io.IOException) cause);
        String rendered = CliFormat.safeMessageWithCause(outer);
        // 断片ごとに切られているので、全体も現実的な長さに収まる
        assertTrue(rendered.length() < 10_000, "rendered length was " + rendered.length());
        // 切り詰めたことが分かる印が付いている（黙って落とさない）
        assertTrue(rendered.contains("..."), rendered);
    }

    /**
     * 「断片ごとに切る」ことを実際に固定する。全体を 1 回切る実装に置き換えても
     * 長さの検査だけなら通ってしまうが、それは MAX_MESSAGE_CHARS の Javadoc が
     * 禁じている形（末尾に付く根本原因が落ちる）。外側が極端に長くても、
     * 根本原因の見分けが付く文字列が出力に残ることを確かめる。
     */
    @Test
    void safeMessageWithCause_keepsTheRootCauseEvenWhenTheHeadIsHuge() {
        // どの段も長い連鎖を作り、それぞれに見分けの付く目印を置く。
        // 全体を 1 回だけ中略する実装だと、両端（外側の先頭と根本原因の末尾）は
        // 残るが「途中の段」がまるごと消えるので、中段の目印で見分けが付く
        java.io.IOException root = new java.io.IOException("c".repeat(5_000) + "ROOT-MARKER");
        java.io.IOException middle = new java.io.IOException("MIDDLE-MARKER" + "b".repeat(5_000), root);
        Exception outer = new java.io.UncheckedIOException("OUTER" + "a".repeat(5_000), middle);
        String rendered = CliFormat.safeMessageWithCause(outer);
        // 断片ごとに切っていれば、どの段の目印も残る
        assertTrue(rendered.contains("OUTER"), "head lost");
        assertTrue(rendered.contains("MIDDLE-MARKER"), "middle segment lost");
        assertTrue(rendered.contains("ROOT-MARKER"), "root cause lost");
    }

    /**
     * 中略が末尾ではなく中央で行われることを確かめる。末尾から切ると、外部由来の値の
     * 長さを選ぶだけで、その後ろに付く「なぜ駄目だったか」の説明を消せてしまう
     * （Jackson の line:/column:、BatchExecutor の「流用を拒否した」という結び）。
     */
    @Test
    void safeMessage_elidesTheMiddleSoTheTrailingExplanationSurvives() {
        // 途中に長い外部由来の値、末尾に対処に必要な説明、という実際の形を作る
        String message = "invalid batch config ('" + "x".repeat(5_000)
                + "') at [Source: bad.yaml; line: 4, column: 14]";
        String rendered = CliFormat.safeMessage(new IllegalArgumentException(message));
        // 先頭（どの値が問題か）が残る
        assertTrue(rendered.startsWith("invalid batch config"), rendered);
        // 末尾（なぜ駄目か）も残る
        assertTrue(rendered.endsWith("line: 4, column: 14]"), rendered);
        // 中略した印が付いている
        assertTrue(rendered.contains("..."), rendered);
    }

    /**
     * 中略がサロゲートペアの途中で切らないことを確認する。片割れのサロゲートは
     * Unicode カテゴリ Cs で制御文字の除去でも落ちないため、端末で "?" や U+FFFD に
     * なり、ASCII のマーカーをわざわざ選んで避けた「壊れた出力に見える」状態を
     * 自分で作ってしまう。
     */
    @Test
    void bounded_neverSplitsASurrogatePair() {
        // 補助文字（絵文字）だけを並べ、どこで切ってもペアの境界に当たりうる形にする
        String emoji = "\uD83D\uDE00";
        // 切る位置が 1 文字ずつずれるよう、いろいろな上限で試す。0〜3 も含めるのは、
        // マーカーすら入らない極端な上限の分岐にだけ穴が残るのを防ぐため
        for (int max = 0; max <= 40; max++) {
            String bounded = SafeText.bounded(emoji.repeat(50), max);
            // 片割れのサロゲートが残っていない
            for (int i = 0; i < bounded.length(); i++) {
                char c = bounded.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    assertTrue(i + 1 < bounded.length()
                            && Character.isLowSurrogate(bounded.charAt(i + 1)),
                            "lone high surrogate at " + i + " for max=" + max);
                } else if (Character.isLowSurrogate(c)) {
                    assertTrue(i > 0 && Character.isHighSurrogate(bounded.charAt(i - 1)),
                            "lone low surrogate at " + i + " for max=" + max);
                }
            }
            // 上限は超えない
            assertTrue(bounded.length() <= max, "max=" + max + " got " + bounded.length());
        }
    }

    /**
     * 表のセル向けの切り詰め（末尾を落とす方）でもサロゲートを分断しないことを
     * 確認する。run のサマリ表はジョブ出力を、list の BATCH 列は state ファイル由来の
     * 値をここへ流すので、桁を揃えるために壊れた文字を作ってはいけない。
     */
    @Test
    void shortMessage_neverSplitsASurrogatePair() {
        String emoji = "\uD83D\uDE00";
        for (int max = 0; max <= 40; max++) {
            String shortened = CliFormat.shortMessage("x".repeat(10) + emoji.repeat(20), max);
            for (int i = 0; i < shortened.length(); i++) {
                char c = shortened.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    assertTrue(i + 1 < shortened.length()
                            && Character.isLowSurrogate(shortened.charAt(i + 1)),
                            "lone high surrogate at " + i + " for max=" + max);
                } else if (Character.isLowSurrogate(c)) {
                    assertTrue(i > 0 && Character.isHighSurrogate(shortened.charAt(i - 1)),
                            "lone low surrogate at " + i + " for max=" + max);
                }
            }
            // 表の桁を崩さない（上限は超えない）
            assertTrue(shortened.length() <= max, "max=" + max + " got " + shortened.length());
        }
    }

    /**
     * RUN ID 列にも長さの上限があることを確認する。ExecutionResult は runId の長さを
     * 検証せず、記録 1 件は 16MiB まで許されるので、上限が無いと改変された記録 1 件で
     * list の 1 行が数メガバイトになる。
     */
    @Test
    void runId_isBounded() {
        String rendered = CliFormat.runId("a".repeat(100_000));
        assertTrue(rendered.length() <= 256, "length was " + rendered.length());
        assertTrue(rendered.contains("..."), rendered);
        // 生成される runId（22 文字）はそのまま通る
        assertEquals("20260102-030405-abcdef", CliFormat.runId("20260102-030405-abcdef"));
    }

    /**
     * 打ち切りの先で見つけた根元に添えられた診断も併記されることを確認する。
     * ここだけ落とすと、他の段では必ず出る判断材料が印も付かずに消える。
     */
    @Test
    void safeMessageWithCause_rendersSuppressedOnTheRootCauseToo() {
        // 上限を超える深さの連鎖を作り、根元にだけ診断を添える
        java.io.IOException root = new java.io.IOException("root");
        root.addSuppressed(new java.io.IOException("atomic move unsupported"));
        Throwable current = root;
        for (int i = 0; i < 8; i++) {
            current = new java.io.IOException("layer" + i, current);
        }
        String rendered = CliFormat.safeMessageWithCause(current);
        // 根元そのものと、そこに添えられた診断の両方が出る
        assertTrue(rendered.contains("root"), rendered);
        assertTrue(rendered.contains("atomic move unsupported"), rendered);
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
                SafeText.stripControlChars("run\u202E-01\u00AD-abc"));
        // isolate 制御（U+2066）も除去されることを確認する
        assertEquals("xy", SafeText.stripControlChars("x\u2066y"));
    }

    /**
     * ListCommand の runId 表示が使う stripControlChars 単体の挙動:
     * 制御文字だけを除去し、切り詰めは行わないこと。null は null のまま返すこと。
     */
    @Test
    void stripControlChars_removesControlsWithoutTruncating() {
        // UUID 風の runId に ESC シーケンスを混ぜても制御文字だけが消える
        assertEquals("0123456789abcdef-0123-0123[31m-esc",
                SafeText.stripControlChars("0123456789abcdef-0123-0123\u001B[31m-esc"));
        // 長い文字列でも切り詰めは発生しない（65 文字がそのまま返る）
        assertEquals(65, SafeText.stripControlChars("y".repeat(65)).length());
        // null は null のまま返す
        assertEquals(null, SafeText.stripControlChars(null));
    }
}
