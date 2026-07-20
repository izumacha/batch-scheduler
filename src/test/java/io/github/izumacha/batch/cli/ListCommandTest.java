package io.github.izumacha.batch.cli;

import io.github.izumacha.batch.model.ExecutionResult;
import io.github.izumacha.batch.model.JobStatus;
import io.github.izumacha.batch.state.JsonExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListCommandTest {

    // 指定した runId・開始時刻で最小限の実行記録を状態ディレクトリに保存するヘルパー
    private static void seed(Path stateDir, String runId, Instant start) {
        // 開始から 1 秒後を終了時刻とするダミーの実行結果を組み立てる
        ExecutionResult run = new ExecutionResult(runId, "etl", JobStatus.SUCCEEDED,
                start, start.plusSeconds(1), List.of());
        // JSON ストアへ保存する（<runId>.json が作られる）
        new JsonExecutionStore(stateDir).save(run);
    }

    // list サブコマンドを実行し、標準出力の内容を文字列として捕捉して返すヘルパー
    private static String runListCapturingStdout(String... args) {
        // 元の標準出力を退避する
        PrintStream original = System.out;
        // 出力を貯めるバッファを用意する
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            // 標準出力をバッファ付きの PrintStream に差し替える
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            // list コマンドを実行する（終了コードは本テストでは検証済みの別ケースに委ねる）
            BatchCli.run(args);
        } finally {
            // 何があっても標準出力を元に戻す
            System.setOut(original);
        }
        // 捕捉した出力を UTF-8 文字列にして返す
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void limitCapsRowsAndPrintsFooter(@TempDir Path dir) {
        // 3 件を開始時刻をずらして保存する
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        seed(dir, "run-a", base);
        seed(dir, "run-b", base.plus(1, ChronoUnit.DAYS));
        seed(dir, "run-c", base.plus(2, ChronoUnit.DAYS));

        // 上限 2 件で一覧を実行する
        String out = runListCapturingStdout("list", "--state-dir", dir.toString(), "--limit", "2");

        // 最新 2 件（run-c, run-b）だけが表示され、最古の run-a は出ない
        assertTrue(out.contains("run-c"), out);
        assertTrue(out.contains("run-b"), out);
        assertFalse(out.contains("run-a"), out);
        // 上限ちょうどのため切り詰め注記フッターが出る
        assertTrue(out.contains("--limit 0 to list all"), out);
    }

    @Test
    void limitExactlyMatchingRunCountOmitsFooter(@TempDir Path dir) {
        // 保存件数がちょうど limit と同数のとき、切り詰めは実際には発生していないので
        // フッターを出してはいけない（regression: 以前は runs.size() == limit だけで
        // 判定しており、「全件がちょうど limit 件」と「limit を超えて切り詰められた」を
        // 区別できず、常に誤ってフッターを表示していた）。
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        seed(dir, "run-a", base);
        seed(dir, "run-b", base.plus(1, ChronoUnit.DAYS));

        // 保存件数と同じ上限 2 件で実行する
        String out = runListCapturingStdout("list", "--state-dir", dir.toString(), "--limit", "2");

        // 2 件とも表示されるが、隠れた実行は無いのでフッターは出ない
        assertTrue(out.contains("run-a"), out);
        assertTrue(out.contains("run-b"), out);
        assertFalse(out.contains("--limit 0 to list all"), out);
    }

    @Test
    void zeroLimitListsAllWithoutFooter(@TempDir Path dir) {
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        seed(dir, "run-a", base);
        seed(dir, "run-b", base.plus(1, ChronoUnit.DAYS));

        // 上限 0（＝全件）で実行する
        String out = runListCapturingStdout("list", "--state-dir", dir.toString(), "--limit", "0");

        // 全件が表示され、切り詰め注記は出ない
        assertTrue(out.contains("run-a"), out);
        assertTrue(out.contains("run-b"), out);
        assertFalse(out.contains("use --limit 0"), out);
    }

    @Test
    void emptyStateDirReportsNoRuns(@TempDir Path dir) {
        // 記録が 1 件もない状態で実行する
        String out = runListCapturingStdout("list", "--state-dir", dir.toString());
        // 「no runs found」を表示する
        assertTrue(out.contains("no runs found"), out);
    }

    @Test
    void invalidLimitValueMapsToConfigExitCode(@TempDir Path dir) {
        // --limit に数値でない値を渡すと入力エラー（EXIT_CONFIG）になる
        int code = BatchCli.run("list", "--state-dir", dir.toString(), "--limit", "abc");
        assertEquals(BatchCli.EXIT_CONFIG, code);
    }

    // 以下は JsonExecutionStore.MAX_UNBOUNDED_RESULTS（100,000）付近の境界動作を検証する
    // ユニットテスト。実際に 100,000 個の状態ファイルを用意するのは非現実的なため、
    // ストアの安全上限をパラメータとして受け取れる形に切り出した純粋関数
    // （effectiveLimit / fetchLimitFor / isTruncated）を、小さな値（storeCeiling=3）で
    // 代用して検証する（regression: 以前は limit がストアの安全上限ちょうど・それ以上のとき、
    // 「+1 件多く要求して切り詰めを検出する」トリックが static にクランプされて機能せず、
    // 切り詰められていても常にフッターが出なかった）。

    @Test
    void effectiveLimitClampsToStoreCeiling() {
        // 上限なし（0 以下）はそのまま素通しする
        assertEquals(0, ListCommand.effectiveLimit(0, 3));
        assertEquals(-1, ListCommand.effectiveLimit(-1, 3));
        // ストアの安全上限未満はそのまま
        assertEquals(2, ListCommand.effectiveLimit(2, 3));
        // ストアの安全上限ちょうど・それ以上はクランプされる
        assertEquals(3, ListCommand.effectiveLimit(3, 3));
        assertEquals(3, ListCommand.effectiveLimit(1000, 3));
    }

    @Test
    void fetchLimitForAddsOneOnlyBelowStoreCeiling() {
        // ストアの安全上限未満なら「+1 件多く要求する」トリックを使う
        assertEquals(3, ListCommand.fetchLimitFor(2, 3));
        // ストアの安全上限ちょうどの場合は +1 すると内部でクランプされてしまうので加算しない
        assertEquals(3, ListCommand.fetchLimitFor(3, 3));
        // 上限なしはそのまま
        assertEquals(0, ListCommand.fetchLimitFor(0, 3));
    }

    @Test
    void isTruncatedDetectsCutoffAtStoreCeilingBoundary() {
        // 通常時（ストアの安全上限未満）: +1 件多く返ってきたときだけ切り詰めありと判定する
        assertTrue(ListCommand.isTruncated(3, 2, 3), "fetched > effectiveLimit のとき切り詰めあり");
        assertFalse(ListCommand.isTruncated(2, 2, 3), "fetched == effectiveLimit のとき切り詰めなし");

        // regression: effectiveLimit がストアの安全上限ちょうどのとき、+1 トリックが使えないため
        // 「上限ぴったり返ってきた」こと自体を安全側に倒して切り詰めありと判定しなければならない
        assertTrue(ListCommand.isTruncated(3, 3, 3),
                "effectiveLimit がストア上限ちょうどのとき、上限ぴったり返ってきたら切り詰めありとみなす");
        // 実際に保存件数が上限未満だった場合は誤って切り詰めありとしない
        assertFalse(ListCommand.isTruncated(2, 3, 3),
                "effectiveLimit がストア上限ちょうどでも、実際の件数がそれ未満なら切り詰めなし");

        // 上限なし指定なら常に切り詰めなし
        assertFalse(ListCommand.isTruncated(100, 0, 3));
    }
}
