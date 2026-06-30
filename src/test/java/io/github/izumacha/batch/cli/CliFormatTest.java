package io.github.izumacha.batch.cli;

// アサーション（assertEquals 等）を静的インポートする
import static org.junit.jupiter.api.Assertions.assertEquals;

// 期間（Duration）を生成するために使う
import java.time.Duration;
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
}
