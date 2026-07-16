package io.github.izumacha.batch.cli;

import io.github.izumacha.batch.config.BatchConfigLoader;
import io.github.izumacha.batch.config.ConfigException;
import io.github.izumacha.batch.config.ValidationException;
import io.github.izumacha.batch.core.BatchExecutor;
import io.github.izumacha.batch.core.DependencyGraph;
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

    // 再実行モード: 指定した runId の前回結果のうち SUCCEEDED だったジョブは再実行せず流用し、
    // FAILED/SKIPPED だったジョブ（および前回に存在しなかった新規ジョブ）だけを実行する
    @Option(names = {"--rerun-failed"}, paramLabel = "RUN_ID",
            description = "reuse succeeded job results from a prior run (looked up by run id "
                    + "under --state-dir) and only (re-)execute jobs that were FAILED or "
                    + "SKIPPED in it, or that did not exist in it")
    String rerunFailedRunId;

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

        try {
            // 保存先ディレクトリを作る前にバッチの構造（依存 DAG）を明示的に検証する。
            // 検証をストア構築より後に回すと、無効なバッチでも --state-dir の
            // ディレクトリツリーが副作用として作られてしまい、さらに保存先エラー（3）が
            // 本来ユーザーに見せるべき検証エラーの一覧（2）を覆い隠してしまうため
            DependencyGraph.build(batch);
        } catch (ValidationException e) {
            // バッチの構造が無効な場合は各エラーを標準エラーに出力して終了する
            for (String error : e.errors()) {
                System.err.println("invalid: " + error);
            }
            return BatchCli.EXIT_VALIDATION;
        }

        // 状態保存ストアを格納する変数を宣言する
        JsonExecutionStore store;
        try {
            // ジョブを 1 つも実行する前に保存先ディレクトリを作成・検証する（fail fast）。
            // 使えない --state-dir（例: 既存の通常ファイル）を実行後に発見すると、
            // ジョブは走ったのに記録が残らず、終了コード 3 が本来のバッチ結果を
            // 覆い隠してしまうため、先にストアを構築し保存先を明示的に作成・検証する
            // （コンストラクタはディレクトリを作らないため、書き込み系のここでだけ呼ぶ）
            store = new JsonExecutionStore(stateDir);
            store.ensureBaseDirectory();
        } catch (RuntimeException e) {
            // 失敗の根本原因（例: 既存ファイルと衝突・権限不足）を取り出す
            Throwable cause = e.getCause();
            // 原因がある場合は「 (原因)」の形で 1 行に併記する（スタックトレースは出さない）
            String detail = cause != null ? " (" + cause + ")" : "";
            // 保存先が使えない場合はエラーメッセージを標準エラーに出力して終了する
            System.err.println("error: failed to prepare state directory: " + e.getMessage() + detail);
            return BatchCli.EXIT_CONFIG;
        }

        // --rerun-failed が指定されていれば、その runId の前回結果を state ディレクトリから
        // 読み込む。ジョブを 1 つも実行する前に検証することで、存在しない runId を
        // 指定した設定ミスをジョブ実行後ではなく fail fast で発見できる
        ExecutionResult priorResult = null;
        if (rerunFailedRunId != null) {
            try {
                // 前回結果を runId で検索する（見つからなければ空の Optional）。
                // runId に "/"・"\"・".."・NUL 等が含まれる場合、findById が委譲する
                // JsonExecutionStore.fileFor はパストラバーサル対策として
                // IllegalArgumentException を投げる（他の失敗経路と同様この try で
                // 捕捉し、スタックトレースを外部に出さず 1 行のエラーに変換する）
                priorResult = store.findById(rerunFailedRunId).orElse(null);
            } catch (IllegalArgumentException e) {
                // runId の形式が不正な場合は設定・IO エラーとして終了する
                System.err.println("error: invalid --rerun-failed run id '" + rerunFailedRunId
                        + "': " + e.getMessage());
                return BatchCli.EXIT_CONFIG;
            }
            if (priorResult == null) {
                // 指定された runId の記録が無い場合は設定・IO エラーとして終了する
                System.err.println("error: no prior run found with id '" + rerunFailedRunId
                        + "' under " + stateDir.toAbsolutePath());
                return BatchCli.EXIT_CONFIG;
            }
        }

        // バッチを実行して結果を取得する。BatchExecutor.execute は内部で依存グラフを
        // もう一度構築するが、構築は軽量な処理であり、検証は上で済んでいるため
        // 同じバッチに対して ValidationException が再発することはない。
        // priorResult が null なら通常実行、非 null なら rerun-failed モードで実行する
        ExecutionResult result;
        try {
            result = new BatchExecutor().execute(batch, priorResult);
        } catch (IllegalArgumentException e) {
            // priorResult が別バッチのものだった場合（batch.name() の不一致）はここで拒否される。
            // ジョブを 1 つも実行していない段階のエラーなので設定・IO エラーとして終了する
            System.err.println("error: " + e.getMessage());
            return BatchCli.EXIT_CONFIG;
        }

        // 状態の保存に失敗しても実行結果は表示できるよう、先にサマリーを出力する
        printSummary(result);

        try {
            // 実行結果を状態ディレクトリに JSON ファイルとして保存する
            store.save(result);
        } catch (RuntimeException e) {
            // 保存に失敗した場合はエラーメッセージを標準エラーに出力する
            System.err.println("error: failed to persist run state: " + e.getMessage());
            // 保存先は事前検証済みなのでここに来るのは稀（実行中のディスク満杯など）。
            // バッチ自体が失敗している場合は、記録漏れ（3）よりもバッチ失敗（1）の方が
            // ラッパースクリプトの分岐にとって重要な情報なので EXIT_FAILED を優先して返す。
            // バッチが成功していた場合のみ、保存失敗を EXIT_CONFIG として報告する
            return result.succeeded() ? BatchCli.EXIT_CONFIG : BatchCli.EXIT_FAILED;
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
                // ジョブ ID・ステータス・終了コード・実行時間・メッセージを整形して出力する。
                // jobId は %-20s の固定幅列のため、ListCommand の batchName 表示と同様に
                // shortMessage で 20 文字に切り詰めて表の桁ずれを防ぐ
                System.out.printf("%-20s  %-9s  %5s  %10s  %s%n",
                        CliFormat.shortMessage(job.jobId(), 20),
                        job.status(),
                        exit,
                        CliFormat.duration(job.duration()),
                        CliFormat.shortMessage(job.message(), 60));
            }
        }
    }
}
