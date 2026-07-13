package io.github.izumacha.batch.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandTest {

    // 指定したプローブファイルを touch するだけの 1 ジョブ YAML を書き出すヘルパー
    private static Path writeTouchBatch(Path dir, Path probe) throws IOException {
        // バッチ定義ファイルの出力先パスを組み立てる
        Path config = dir.resolve("batch.yaml");
        // ジョブが実行されたかどうかをプローブファイルの有無で判定できる YAML を書き込む
        Files.writeString(config, """
                name: probe
                jobs:
                  - id: touch
                    command: ["sh", "-c", "touch '%s'"]
                """.formatted(probe));
        // 書き出した設定ファイルのパスを返す
        return config;
    }

    @Test
    void unusableStateDirFailsFastBeforeRunningAnyJob(@TempDir Path dir) throws IOException {
        // ジョブ実行の有無を検知するためのプローブファイルのパスを決める（まだ存在しない）
        Path probe = dir.resolve("probe.txt");
        // プローブファイルを touch するバッチ定義を書き出す
        Path config = writeTouchBatch(dir, probe);
        // --state-dir に「既存の通常ファイル」を指定してディレクトリ作成を失敗させる
        Path notADir = dir.resolve("state-as-file");
        // 保存先として使えない通常ファイルを実際に作成する
        Files.writeString(notADir, "not a directory");

        // run コマンドを実行する（保存先の検証はジョブ実行前に行われるはず）
        int code = BatchCli.run("run", config.toString(), "--state-dir", notADir.toString(), "-q");

        // 使えない保存先は設定・IO エラーとして終了コード 3 になるはず
        assertEquals(BatchCli.EXIT_CONFIG, code);
        // fail fast の検証: プローブファイルが存在しない = ジョブが 1 つも実行されていない
        assertFalse(Files.exists(probe),
                "state-dir validation must fail before any job runs");
    }

    @Test
    void invalidBatchExitsValidationWithoutCreatingStateDir(@TempDir Path dir) throws IOException {
        // バッチ定義ファイルの出力先パスを組み立てる
        Path config = dir.resolve("batch.yaml");
        // 存在しないジョブに依存する（構造が無効な）バッチ定義を書き込む
        Files.writeString(config, """
                name: invalid
                jobs:
                  - id: a
                    command: ["sh", "-c", "true"]
                    dependsOn: [ghost]
                """);
        // まだ存在しない保存先ディレクトリのパスを用意する（作られないことを検証する）
        Path stateDir = dir.resolve("brand/new/state");

        // run コマンドを実行する（検証は保存先ディレクトリの作成より先に行われるはず）
        int code = BatchCli.run("run", config.toString(), "--state-dir", stateDir.toString(), "-q");

        // 保存先エラー（3）ではなく検証エラー（2）が優先して報告されるはず
        assertEquals(BatchCli.EXIT_VALIDATION, code);
        // 無効なバッチでは --state-dir のディレクトリツリーが副作用として作られていないこと
        assertFalse(Files.exists(stateDir),
                "an invalid batch must not create the state directory as a side effect");
    }

    @Test
    void successfulRunSavesStateAndExitsOk(@TempDir Path dir) throws IOException {
        // ジョブ実行を確認するためのプローブファイルのパスを決める
        Path probe = dir.resolve("probe.txt");
        // プローブファイルを touch するバッチ定義を書き出す
        Path config = writeTouchBatch(dir, probe);
        // 保存先として通常のディレクトリパスを用意する（run が作成する）
        Path stateDir = dir.resolve("state");

        // run コマンドを実行する
        int code = BatchCli.run("run", config.toString(), "--state-dir", stateDir.toString(), "-q");

        // バッチ成功は終了コード 0 になるはず
        assertEquals(BatchCli.EXIT_OK, code);
        // ジョブが実際に実行されプローブファイルが作成されていること
        assertTrue(Files.exists(probe), "the job should have run and touched the probe file");
        // 実行記録の JSON が保存先ディレクトリに 1 件保存されていること
        try (var files = Files.list(stateDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".json")),
                    "a run record JSON should be persisted in the state directory");
        }
    }

    // 終了コードと捕捉した標準エラー出力をまとめて返すテスト用の入れ物
    private record RunOutcome(int code, String stderr) {}

    // run コマンドを実行し、標準エラー出力を文字列として捕捉して終了コードと一緒に返すヘルパー
    private static RunOutcome runCapturingStderr(String... args) {
        // 元の標準エラー出力を退避する
        PrintStream original = System.err;
        // 出力を貯めるバッファを用意する
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            // 標準エラー出力をバッファ付きの PrintStream に差し替える
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            // run コマンドを実行して終了コードを受け取る
            int code = BatchCli.run(args);
            // 終了コードと捕捉した標準エラー出力を組にして返す
            return new RunOutcome(code, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            // 何があっても標準エラー出力を元に戻す
            System.setErr(original);
        }
    }

    // 実行中に保存先ディレクトリを破壊して通常ファイルに置き換えるジョブ 1 件の
    // バッチ定義を書き出すヘルパー（保存が実行後に失敗する状況を再現する）
    private static Path writeStateDirDestroyingBatch(Path dir, Path stateDir, int exitCode)
            throws IOException {
        // バッチ定義ファイルの出力先パスを組み立てる
        Path config = dir.resolve("batch.yaml");
        // 保存先ディレクトリを削除して同名の通常ファイルを作り、指定の終了コードで終わる YAML を書き込む
        Files.writeString(config, """
                name: destroyer
                jobs:
                  - id: destroy
                    command: ["sh", "-c", "rm -rf '%s' && touch '%s'; exit %d"]
                """.formatted(stateDir.toAbsolutePath(), stateDir.toAbsolutePath(), exitCode));
        // 書き出した設定ファイルのパスを返す
        return config;
    }

    @Test
    void persistFailureAfterFailedBatchPrefersBatchExitCode(@TempDir Path dir) throws IOException {
        // 保存先ディレクトリのパスを決める（run が事前に作成し、ジョブが破壊する）
        Path stateDir = dir.resolve("state");
        // 保存先を破壊してから終了コード 1 で失敗するバッチ定義を書き出す
        Path config = writeStateDirDestroyingBatch(dir, stateDir, 1);

        // run コマンドを実行し、終了コードと標準エラー出力を捕捉する
        RunOutcome outcome = runCapturingStderr(
                "run", config.toString(), "--state-dir", stateDir.toString(), "-q");

        // 保存失敗（3）よりバッチ失敗（1）が優先して報告されるはず
        assertEquals(BatchCli.EXIT_FAILED, outcome.code());
        // 保存に失敗した旨のエラーメッセージは標準エラーに出力されているはず
        assertTrue(outcome.stderr().contains("failed to persist"), outcome.stderr());
    }

    @Test
    void persistFailureAfterSuccessfulBatchExitsConfig(@TempDir Path dir) throws IOException {
        // 保存先ディレクトリのパスを決める（run が事前に作成し、ジョブが破壊する）
        Path stateDir = dir.resolve("state");
        // 保存先を破壊してから終了コード 0 で成功するバッチ定義を書き出す
        Path config = writeStateDirDestroyingBatch(dir, stateDir, 0);

        // run コマンドを実行し、終了コードと標準エラー出力を捕捉する
        RunOutcome outcome = runCapturingStderr(
                "run", config.toString(), "--state-dir", stateDir.toString(), "-q");

        // バッチが成功していた場合のみ、保存失敗が設定・IO エラー（3）として報告されるはず
        assertEquals(BatchCli.EXIT_CONFIG, outcome.code());
        // 保存に失敗した旨のエラーメッセージは標準エラーに出力されているはず
        assertTrue(outcome.stderr().contains("failed to persist"), outcome.stderr());
    }

    @Test
    void failedBatchStillPersistsAndExitsFailed(@TempDir Path dir) throws IOException {
        // バッチ定義ファイルの出力先パスを組み立てる
        Path config = dir.resolve("batch.yaml");
        // 必ず失敗するジョブ 1 件のバッチ定義を書き込む
        Files.writeString(config, """
                name: failing
                jobs:
                  - id: boom
                    command: ["sh", "-c", "exit 1"]
                """);
        // 保存先として通常のディレクトリパスを用意する
        Path stateDir = dir.resolve("state");

        // run コマンドを実行する
        int code = BatchCli.run("run", config.toString(), "--state-dir", stateDir.toString(), "-q");

        // バッチ失敗は終了コード 1 になるはず（保存先の事前検証が結果コードを覆い隠さない）
        assertEquals(BatchCli.EXIT_FAILED, code);
        // 失敗した実行の記録も JSON として保存されていること
        try (var files = Files.list(stateDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".json")),
                    "the failed run record should still be persisted");
        }
    }
}
