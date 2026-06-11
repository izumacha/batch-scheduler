package io.github.izumacha.batch.cli;

import io.github.izumacha.batch.config.BatchConfigLoader;
import io.github.izumacha.batch.config.ConfigException;
import io.github.izumacha.batch.config.ValidationException;
import io.github.izumacha.batch.core.DependencyGraph;
import io.github.izumacha.batch.model.Batch;
import io.github.izumacha.batch.model.Job;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * バッチ YAML ファイルを実行せずに検証し、構造上の問題を報告するサブコマンド。
 * 問題がなければ解決済みの実行順序も表示する。
 */
@Command(
        name = "validate",
        description = "Check a batch YAML file for structural problems without running it.",
        mixinStandardHelpOptions = true
)
public final class ValidateCommand implements Callable<Integer> {

    // バッチ YAML ファイルのパス（コマンドライン引数として受け取る）
    @Parameters(index = "0", paramLabel = "CONFIG", description = "path to the batch YAML file")
    Path config;

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

        // 依存グラフを格納する変数を宣言する
        DependencyGraph graph;
        try {
            // 読み込んだバッチから依存グラフを構築して構造を検証する
            graph = DependencyGraph.build(batch);
        } catch (ValidationException e) {
            // 構造上のエラーがある場合は各エラーを標準エラーに箇条書きで出力して終了する
            System.err.println("invalid:");
            for (String error : e.errors()) {
                System.err.println("  - " + error);
            }
            return BatchCli.EXIT_VALIDATION;
        }

        // 依存グラフからトポロジカル順のジョブリストを取得する
        List<Job> order = graph.topologicalOrder();
        // 検証成功メッセージとジョブ数を出力する
        System.out.printf("OK: %s (%d jobs)%n", batch.name(), batch.jobs().size());
        // 実行順序の見出しを出力する
        System.out.println("Execution order:");
        // ステップ番号のカウンタを初期化する
        int step = 1;
        // トポロジカル順にジョブを 1 件ずつ番号付きで出力する
        for (Job job : order) {
            System.out.printf("  %d. %s%n", step++, job.id());
        }
        // 検証成功として EXIT_OK を返す
        return BatchCli.EXIT_OK;
    }
}
