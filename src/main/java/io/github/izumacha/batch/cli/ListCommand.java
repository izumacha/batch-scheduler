package io.github.izumacha.batch.cli;

import io.github.izumacha.batch.model.ExecutionResult;
import io.github.izumacha.batch.state.JsonExecutionStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 過去に記録されたバッチ実行を最新順で一覧表示するサブコマンド。
 */
@Command(
        name = "list",
        description = "List recorded batch runs, most recent first.",
        mixinStandardHelpOptions = true
)
public final class ListCommand implements Callable<Integer> {

    // 実行状態の読み取り元ディレクトリ（デフォルトは ".batch-state"）
    @Option(names = {"--state-dir"}, defaultValue = ".batch-state",
            description = "directory for run state (default: ${DEFAULT-VALUE})")
    Path stateDir;

    @Override
    public Integer call() {
        // 過去の実行結果リストを格納する変数を宣言する
        List<ExecutionResult> runs;
        try {
            // 状態ディレクトリからすべての実行記録を読み込む（最新順にソート済み）
            runs = new JsonExecutionStore(stateDir).findAll();
        } catch (RuntimeException e) {
            // 読み込みに失敗した場合はエラーメッセージを標準エラーに出力して終了する
            System.err.println("error: failed to read run state: " + e.getMessage());
            return BatchCli.EXIT_CONFIG;
        }

        // 実行記録が 1 件もない場合はその旨を出力して正常終了する
        if (runs.isEmpty()) {
            System.out.println("no runs found");
            return BatchCli.EXIT_OK;
        }

        // テーブルの列ヘッダーを組み立てる（固定幅で整形する）
        String header = String.format("%-36s  %-20s  %-9s  %-19s  %10s",
                "RUN ID", "BATCH", "STATUS", "STARTED", "DURATION");
        // ヘッダー行を出力する
        System.out.println(header);
        // ヘッダーと同じ幅の区切り線を出力する
        System.out.println("-".repeat(header.length()));
        // 各実行記録を 1 行ずつ表示する
        for (ExecutionResult run : runs) {
            // 実行 ID・バッチ名・ステータス・開始時刻・実行時間を整形して出力する
            System.out.printf("%-36s  %-20s  %-9s  %-19s  %10s%n",
                    run.runId(),
                    CliFormat.shortMessage(run.batchName(), 20),
                    run.status(),
                    CliFormat.instant(run.startedAt()),
                    CliFormat.duration(run.duration()));
        }
        // 正常終了として EXIT_OK を返す
        return BatchCli.EXIT_OK;
    }
}
