package io.github.izumacha.batch.cli;

import io.github.izumacha.batch.config.BatchConfigLoader;
import io.github.izumacha.batch.config.ConfigException;
import io.github.izumacha.batch.config.ValidationException;
import io.github.izumacha.batch.core.BatchExecutor;
import io.github.izumacha.batch.model.Batch;
import io.github.izumacha.batch.model.ExecutionResult;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;
import io.github.izumacha.batch.state.JsonExecutionStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * YAML からバッチを読み込んで実行し、実行レポートを永続化するサブコマンド。
 */
@Command(
        name = "run",
        description = "Execute a batch defined in a YAML file and record the run.",
        mixinStandardHelpOptions = true
)
public final class RunCommand implements Callable<Integer> {

    // バッチ YAML ファイルのパス（コマンドライン引数として受け取る）
    @Parameters(index = "0", paramLabel = "CONFIG", description = "path to the batch YAML file")
    Path config;

    // 実行状態の保存先ディレクトリ（デフォルトは ".batch-state"）
    @Option(names = {"--state-dir"}, defaultValue = ".batch-state",
            description = "directory for run state (default: ${DEFAULT-VALUE})")
    Path stateDir;

    // true の場合はジョブごとのサマリーテーブルを表示しない
    @Option(names = {"-q", "--quiet"}, description = "suppress the per-job summary table")
    boolean quiet;

    @Override
    public Integer call() {
        // バッチ設定オブジェクトを格納する変数を宣言する
        Batch batch;
        try {
            // YAML ファイルを読み込んで Batch オブジェクトに変換する
            batch = new BatchConfigLoader().load(config);
        } catch (ConfigException e) {
            // 読み込みまたは解析エラーの場合はエラーメッセージを標準エラーに出力して終了する
            System.err.println("error: " + e.getMessage());
            return BatchCli.EXIT_CONFIG;
        }

        // バッチ実行結果を格納する変数を宣言する
        ExecutionResult result;
        try {
            // バッチを実行して結果を取得する
            result = new BatchExecutor().execute(batch);
        } catch (ValidationException e) {
            // バッチの構造が無効な場合は各エラーを標準エラーに出力して終了する
            for (String error : e.errors()) {
                System.err.println("invalid: " + error);
            }
            return BatchCli.EXIT_VALIDATION;
        }

        // 状態の保存に失敗しても実行結果は表示できるよう、先にサマリーを出力する
        printSummary(result);

        try {
            // 実行結果を状態ディレクトリに JSON ファイルとして保存する
            new JsonExecutionStore(stateDir).save(result);
        } catch (RuntimeException e) {
            // 保存に失敗した場合はエラーメッセージを標準エラーに出力して終了する
            System.err.println("error: failed to persist run state: " + e.getMessage());
            return BatchCli.EXIT_CONFIG;
        }
        // 保存先ディレクトリの絶対パスを標準出力に出力する
        System.out.printf("%nState saved to %s%n", stateDir.toAbsolutePath());

        // バッチが成功した場合は EXIT_OK、失敗した場合は EXIT_FAILED を返す
        return result.succeeded() ? BatchCli.EXIT_OK : BatchCli.EXIT_FAILED;
    }

    // バッチの実行サマリーを標準出力に出力するメソッド
    private void printSummary(ExecutionResult result) {
        // バッチ名を出力する
        System.out.printf("Batch:  %s%n", result.batchName());
        // 実行 ID を出力する
        System.out.printf("Run ID: %s%n", result.runId());
        // バッチ全体のステータスを出力する
        System.out.printf("Status: %s%n", result.status());
        // 成功・失敗・スキップのジョブ数と合計数、総実行時間を出力する
        System.out.printf("Jobs:   %d succeeded, %d failed, %d skipped (%d total) in %s%n",
                result.countByStatus(JobStatus.SUCCEEDED),
                result.countByStatus(JobStatus.FAILED),
                result.countByStatus(JobStatus.SKIPPED),
                result.jobResults().size(),
                CliFormat.duration(result.duration()));

        // quiet モードでない場合かつジョブが 1 件以上ある場合はジョブ別の一覧を表示する
        if (!quiet && !result.jobResults().isEmpty()) {
            // 見出しとの区切りのために空行を出力する
            System.out.println();
            // テーブルの列ヘッダーを組み立てる（固定幅で整形する）
            String header = String.format("%-20s  %-9s  %5s  %10s  %s",
                    "JOB", "STATUS", "EXIT", "DURATION", "MESSAGE");
            // ヘッダー行を出力する
            System.out.println(header);
            // ヘッダーと同じ幅の区切り線を出力する
            System.out.println("-".repeat(header.length()));
            // 各ジョブの結果を 1 行ずつ出力する
            for (JobResult job : result.jobResults()) {
                // 終了コードが取得できない場合は "-" を表示する
                String exit = job.exitCode() == JobResult.NO_EXIT_CODE
                        ? "-" : Integer.toString(job.exitCode());
                // ジョブ ID・ステータス・終了コード・実行時間・メッセージを整形して出力する
                System.out.printf("%-20s  %-9s  %5s  %10s  %s%n",
                        job.jobId(),
                        job.status(),
                        exit,
                        CliFormat.duration(job.duration()),
                        CliFormat.shortMessage(job.message(), 60));
            }
        }
    }
}
