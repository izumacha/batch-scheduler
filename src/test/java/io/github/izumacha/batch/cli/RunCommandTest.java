package io.github.izumacha.batch.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
