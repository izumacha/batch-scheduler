package io.github.izumacha.batch.core;

import io.github.izumacha.batch.model.Job;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JobRunner} が {@link Job#workingDir()}(作業ディレクトリ)を
 * 正しく扱うことを検証するテスト。正常系・存在しないディレクトリ・null・空白の
 * 各ケースを網羅し、実装(JobRunner.runOnce の ProcessBuilder.directory 設定)の
 * 回帰を防ぐ。
 */
class JobRunnerWorkingDirTest {

    /** リトライ待機なしで実行する JobRunner を生成する（テストを高速に保つため）。 */
    private static JobRunner fastRunner() {
        // 出力50行・バックオフ0・エコーなしの設定でランナーを作る
        return new JobRunner(50, Duration.ZERO, false);
    }

    /** 作業ディレクトリだけを変えたジョブを生成するヘルパー（他の引数は既定値）。 */
    private static Job jobWithWorkingDir(String id, List<String> command, String workingDir) {
        // 依存なし・リトライ0・タイムアウト0・環境変数なしで、作業ディレクトリのみ指定する
        return new Job(id, null, command, List.of(), 0, 0, Map.of(), workingDir);
    }

    @Test
    void 指定した有効な作業ディレクトリでコマンドが実行される(@TempDir Path tempDir) throws Exception {
        // 一時ディレクトリ内に目印となるファイルを作成する
        Path marker = tempDir.resolve("marker.txt");
        // 目印ファイルへ任意の内容を書き込む
        Files.writeString(marker, "content");

        // 作業ディレクトリが正しく設定されていれば marker.txt が見つかり exit 0 になるジョブを作る
        Job job = jobWithWorkingDir(
                "valid_dir",
                List.of("sh", "-c", "test -f marker.txt"),
                tempDir.toString());

        // ジョブを実行して結果を取得する
        JobResult result = fastRunner().run(job);

        // 指定ディレクトリで実行され目印ファイルを見つけられたので成功するはず（失敗時はメッセージを表示）
        assertEquals(JobStatus.SUCCEEDED, result.status(), result.message());
        // 終了コードは 0 のはず
        assertEquals(0, result.exitCode());
    }

    @Test
    void 存在しない作業ディレクトリを指定するとジョブが失敗する() {
        // 実在しないディレクトリを作業ディレクトリに指定したジョブを作る
        Job job = jobWithWorkingDir(
                "missing_dir",
                List.of("sh", "-c", "exit 0"),
                "/nonexistent/directory/does/not/exist");

        // ジョブを実行して結果を取得する
        JobResult result = fastRunner().run(job);

        // プロセス起動に失敗するため、クラッシュせず FAILED として返るはず（fail-closed）
        assertEquals(JobStatus.FAILED, result.status());
        // 起動失敗時は終了コードが取得できないため番兵値になるはず
        assertEquals(JobResult.NO_EXIT_CODE, result.exitCode());
        // 起動失敗を示す "failed to start" がメッセージに含まれるはず
        assertTrue(result.message().contains("failed to start"), result.message());
    }

    @Test
    void 作業ディレクトリがnullのときは既定のディレクトリで実行される() {
        // 作業ディレクトリを指定しない（null）ジョブを作る
        Job job = jobWithWorkingDir("null_dir", List.of("sh", "-c", "exit 0"), null);

        // ジョブを実行して結果を取得する
        JobResult result = fastRunner().run(job);

        // 起動側の既定ディレクトリで実行され成功するはず
        assertEquals(JobStatus.SUCCEEDED, result.status(), result.message());
        // 終了コードは 0 のはず
        assertEquals(0, result.exitCode());
    }

    @Test
    void 空白の作業ディレクトリはnullに正規化されて既定ディレクトリで実行される() {
        // 空白文字だけの作業ディレクトリを指定したジョブを作る
        Job job = jobWithWorkingDir("blank_dir", List.of("sh", "-c", "exit 0"), "   ");

        // Job のコンストラクタで空白は null へ正規化されているはず
        assertNull(job.workingDir(), "空白の作業ディレクトリは null に正規化されるべき");

        // ジョブを実行して結果を取得する
        JobResult result = fastRunner().run(job);

        // null 扱いとなり既定ディレクトリで実行され成功するはず
        assertEquals(JobStatus.SUCCEEDED, result.status(), result.message());
    }
}
