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

    // 一覧表示の既定上限件数（§8: 既定件数は定数で一元管理する）。picocli の defaultValue は
    // コンパイル時定数の文字列が必要なため、数値そのものではなく文字列として保持する。
    static final String DEFAULT_LIMIT = "20";

    // 実行状態の読み取り元ディレクトリ（デフォルトは ".batch-state"）
    @Option(names = {"--state-dir"}, defaultValue = ".batch-state",
            description = "directory for run state (default: ${DEFAULT-VALUE})")
    Path stateDir;

    // 表示する実行記録の最大件数（0 以下は上限なし＝全件表示）。履歴は溜まり続けるため
    // 既定で上限を掛け、無制限な一覧取得を避ける（§8・§9 のリソース枯渇防止）。
    @Option(names = {"-n", "--limit"}, defaultValue = DEFAULT_LIMIT,
            description = "maximum number of runs to show; 0 or negative shows all "
                    + "(default: ${DEFAULT-VALUE})")
    int limit;

    @Override
    public Integer call() {
        // 表示上限ちょうどの件数しかない場合と、上限を超えて切り詰められた場合を区別するため、
        // 実際に表示する件数より 1 件多く要求する（limit が Integer.MAX_VALUE 付近だと
        // +1 でオーバーフローするので、その場合は加算しない＝実質「上限なし」として扱う）
        int fetchLimit = (limit > 0 && limit < Integer.MAX_VALUE) ? limit + 1 : limit;
        // 状態ディレクトリから読み込んだ実行結果リスト（切り詰め判定用に 1 件多い可能性がある）
        List<ExecutionResult> fetched;
        try {
            // 状態ディレクトリから最新順で最大 fetchLimit 件の実行記録を読み込む（limit<=0 は全件）
            fetched = new JsonExecutionStore(stateDir).findRecent(fetchLimit);
        } catch (RuntimeException e) {
            // 読み込みに失敗した場合はエラーメッセージを標準エラーに出力して終了する
            System.err.println("error: failed to read run state: " + e.getMessage());
            return BatchCli.EXIT_CONFIG;
        }
        // 実際に limit を超えて切り詰められたかどうか（+1 件多く取れた場合のみ真）
        boolean truncated = limit > 0 && fetched.size() > limit;
        // 画面に表示するのは常に limit 件まで（切り詰め判定用に多く取った 1 件は表示しない）
        List<ExecutionResult> runs = truncated ? fetched.subList(0, limit) : fetched;

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
        // 実際に切り詰められた場合のみ、隠れた古い実行があることを注記する
        if (truncated) {
            System.out.printf("%n(showing up to %d most recent runs; use --limit 0 to list all)%n",
                    limit);
        }
        // 正常終了として EXIT_OK を返す
        return BatchCli.EXIT_OK;
    }
}
