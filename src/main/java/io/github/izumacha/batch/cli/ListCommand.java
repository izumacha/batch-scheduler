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
        // JsonExecutionStore.findRecent は MAX_UNBOUNDED_RESULTS を超える limit を
        // 静かにクランプするため、先にこちら側でも同じ上限でクランプしておく
        // （effectiveLimit がストアの安全上限ちょうどになる境界を isTruncated/fetchLimitFor で
        // 特別扱いするため）
        int effectiveLimit = effectiveLimit(limit, JsonExecutionStore.MAX_UNBOUNDED_RESULTS);
        // 表示上限ちょうどの件数しかない場合と、上限を超えて切り詰められた場合を区別するため、
        // 実際に表示する件数より 1 件多く要求する（effectiveLimit がストアの安全上限ちょうどの
        // 場合は +1 すると意図せずクランプされてしまうので、その場合は加算しない）
        int fetchLimit = fetchLimitFor(effectiveLimit, JsonExecutionStore.MAX_UNBOUNDED_RESULTS);
        // 状態ディレクトリから読み込んだ実行結果リスト（切り詰め判定用に 1 件多い可能性がある）
        List<ExecutionResult> fetched;
        try {
            // 状態ディレクトリから最新順で最大 fetchLimit 件の実行記録を読み込む（limit<=0 は全件）
            fetched = new JsonExecutionStore(stateDir).findRecent(fetchLimit);
        } catch (RuntimeException e) {
            // 読み込みに失敗した場合はエラーメッセージを標準エラーに出力して終了する
            System.err.println("error: failed to read run state: " + CliFormat.safeMessage(e));
            return BatchCli.EXIT_CONFIG;
        }
        // 実際に effectiveLimit を超えて切り詰められたかどうか
        boolean truncated = isTruncated(fetched.size(), effectiveLimit,
                JsonExecutionStore.MAX_UNBOUNDED_RESULTS);
        // 画面に表示するのは常に effectiveLimit 件まで（切り詰め判定用に多く取った分は表示しない）
        List<ExecutionResult> runs = truncated
                ? fetched.subList(0, Math.min(effectiveLimit, fetched.size()))
                : fetched;

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
                    effectiveLimit);
        }
        // 正常終了として EXIT_OK を返す
        return BatchCli.EXIT_OK;
    }

    /**
     * ユーザー指定の limit を、ストアの絶対的な安全上限（storeCeiling）でクランプする。
     * limit <= 0（上限なし）はそのまま返す。
     */
    // JsonExecutionStore.findRecent 側の暗黙のクランプを CLI 側でも先取りして再現する
    // ヘルパー（実ファイルを大量に用意しなくても単体テストできるよう、ロジックを切り出した）
    static int effectiveLimit(int limit, int storeCeiling) {
        // 上限なし指定はそのまま素通しする
        if (limit <= 0) {
            return limit;
        }
        // ユーザー指定値とストアの安全上限の小さい方を採用する
        return Math.min(limit, storeCeiling);
    }

    /**
     * ストアに問い合わせる実際の件数を決める。切り詰め検出用の「+1 件多く要求する」トリックは、
     * effectiveLimit がストアの安全上限ちょうど（またはそれ以上）のときは使えない
     * （+1 しても findRecent 内部で storeCeiling までクランプされ、+1 分の余剰が消えてしまうため）。
     */
    static int fetchLimitFor(int effectiveLimit, int storeCeiling) {
        // 上限なし、またはちょうどストアの安全上限に達している場合はそのまま要求する
        if (effectiveLimit <= 0 || effectiveLimit >= storeCeiling) {
            return effectiveLimit;
        }
        // 通常時は「+1 件多く要求する」トリックで切り詰め有無を判定する
        return effectiveLimit + 1;
    }

    /**
     * fetched.size() と effectiveLimit（、ストアの安全上限）から、一覧が切り詰められたかを判定する。
     * effectiveLimit がストアの安全上限ちょうどに達している場合は「+1」トリックが使えないため、
     * 上限ぴったり返ってきたら（実際には切り詰められていない可能性もあるが）安全側に倒して
     * 「切り詰められた」とみなす。
     */
    static boolean isTruncated(int fetchedSize, int effectiveLimit, int storeCeiling) {
        // 上限なし指定なら切り詰めは発生しない
        if (effectiveLimit <= 0) {
            return false;
        }
        // ストアの安全上限ちょうどに達している場合は、上限ぴったり返ってきたことを
        // 「まだ隠れているかもしれない」の合図として扱う
        if (effectiveLimit >= storeCeiling) {
            return fetchedSize >= storeCeiling;
        }
        // 通常時は「+1 件多く要求して、実際に 1 件多く返ってきたか」で判定する
        return fetchedSize > effectiveLimit;
    }
}
