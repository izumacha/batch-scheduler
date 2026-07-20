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
    void isTruncatedTreatsSafetyCeilingHitAsTruncatedWhenLimitIsUnbounded() {
        // limit <= 0（全件表示）でも、取得件数が JsonExecutionStore の安全上限
        //（サーキットブレーカー）に達した場合は切り詰めとして扱う（regression: 以前は
        // limit > 0 のときしか切り詰めと判定せず、--limit 0 で上限に達しても注記フッターが
        // 出なかった）。上限件数分の実ファイルを作るのはテスト実行時間の観点で非現実的な
        // ため、JsonExecutionStore.keepMostRecentByFilename のテストと同様に、切り出された
        // 純粋関数として判定ロジックを検証する。
        assertTrue(ListCommand.isTruncated(0, JsonExecutionStore.MAX_UNBOUNDED_RESULTS));
        assertTrue(ListCommand.isTruncated(-1, JsonExecutionStore.MAX_UNBOUNDED_RESULTS));
        // 上限未満しか取得されていなければ切り詰めなし（境界値: 上限 - 1）
        assertFalse(ListCommand.isTruncated(0, JsonExecutionStore.MAX_UNBOUNDED_RESULTS - 1));
        // 少数件の通常ケースでも切り詰めなし
        assertFalse(ListCommand.isTruncated(0, 2));
        // limit > 0 の従来判定: 判定用の +1 件が取れた場合のみ切り詰め
        assertTrue(ListCommand.isTruncated(2, 3));
        assertFalse(ListCommand.isTruncated(2, 2));
    }

    @Test
    void truncationNoticeMentionsSafetyCeilingWhenLimitIsUnbounded() {
        // limit > 0 のとき: 従来どおり --limit 0 で全件表示できる旨を案内する
        assertTrue(ListCommand.truncationNotice(2).contains("--limit 0 to list all"));
        // limit <= 0 のとき: 既に全件表示を要求している利用者に --limit 0 を案内しても
        // 意味がないため、安全上限に達した旨と上限件数を注記する
        String notice = ListCommand.truncationNotice(0);
        assertTrue(notice.contains(String.valueOf(JsonExecutionStore.MAX_UNBOUNDED_RESULTS)),
                notice);
        assertTrue(notice.contains("safety ceiling"), notice);
        assertFalse(notice.contains("--limit 0 to list all"), notice);
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
}
