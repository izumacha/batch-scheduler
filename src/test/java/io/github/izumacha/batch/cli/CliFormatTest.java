package io.github.izumacha.batch.cli;

// アサーション（assertEquals 等）を静的インポートする
import static org.junit.jupiter.api.Assertions.assertEquals;
// 正規表現マッチのアサーションに使う
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** 制御文字を含む長いメッセージでも、切り詰め（最大文字数＋省略記号）が従来どおり働くこと */
    @Test
    void shortMessage_truncationStillWorksAfterSanitization() {
        // ESC を混ぜた 70 文字超の入力を用意する（サニタイズ後は "[31m"＋"x"×70 の 74 文字）
        String longMessage = "\u001B[31m" + "x".repeat(70);
        // 60 文字上限で切り詰めた結果を取得する
        String truncated = CliFormat.shortMessage(longMessage, 60);
        // 全体の長さが上限の 60 文字ちょうどになることを確認する
        assertEquals(60, truncated.length());
        // 末尾が省略記号（…）で終わることを確認する
        assertTrue(truncated.endsWith("…"), truncated);
    }

    /** 制御文字を含まない日本語の通常メッセージは変化しないこと（偽陽性の除去がないこと） */
    @Test
    void shortMessage_plainJapaneseTextPassesUnchanged() {
        // 日本語のエラーメッセージがそのまま返る
        assertEquals("ジョブが失敗しました: 終了コード 1",
                CliFormat.shortMessage("ジョブが失敗しました: 終了コード 1", 60));
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
