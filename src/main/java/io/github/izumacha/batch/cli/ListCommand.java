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
            System.err.println("error: failed to read run state: " + CliFormat.safeMessage(e));
            return BatchCli.EXIT_CONFIG;
        }
        // 実際に切り詰められた（隠れた古い実行が存在し得る）かどうか。limit <= 0（全件表示）
        // でも JsonExecutionStore.findAll の安全上限で切り詰められることがあるため、
        // 判定は共通ヘルパー isTruncated に委譲する（詳細はそちらの Javadoc 参照）
        boolean truncated = isTruncated(limit, fetched.size());
        // 画面に表示するのは常に limit 件まで（limit > 0 で切り詰め判定用に多く取った
        // 1 件は表示しない。limit <= 0 のときは取得できた全件をそのまま表示する）
        List<ExecutionResult> runs = (limit > 0 && truncated) ? fetched.subList(0, limit) : fetched;

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
        // （注記文の組み立ては limit の正負で案内が変わるためヘルパーに委譲する）
        if (truncated) {
            System.out.printf("%n%s%n", truncationNotice(limit));
        }
        // 正常終了として EXIT_OK を返す
        return BatchCli.EXIT_OK;
    }

    /**
     * 取得結果が切り詰められた（＝表示されない古い実行が存在し得る）かどうかを判定する。
     * {@code limit > 0} のときは判定用に 1 件多く要求している前提で「上限より多く取れた」
     * 場合のみ真。{@code limit <= 0}（全件表示）のときは {@link JsonExecutionStore#findAll}
     * が内部の安全上限 {@link JsonExecutionStore#MAX_UNBOUNDED_RESULTS} で候補を黙って
     * 切り詰めることがあるため、取得件数がその上限に達した場合も真とする。ちょうど上限件数
     * だけが保存されていた（実際には切り詰められていない）場合も真になるが、その区別には
     * 安全上限を超える全件パースが必要になり本末転倒なため、「最大 N 件まで表示」という
     * 注記を安全側で出すことを選ぶ（§9 fail-safe。注記文もその曖昧さを含む表現にしている）。
     * 逆に、安全上限で切り詰められたにもかかわらず破損・読み取り不能ファイルのスキップで
     * 取得件数が上限を下回った場合は注記が出ない（偽陰性）が、これは上限規模（10 万件超）と
     * ファイル破損が同時に起きた場合に限られ、注記はあくまでベストエフォートの案内であるため
     * 許容する（厳密化には上限超の全件パースが必要になり本末転倒）。
     *
     * <p>安全上限のケースは {@code MAX_UNBOUNDED_RESULTS} 件の実ファイルを要しコマンド単位の
     * テストが非現実的なため、{@code JsonExecutionStore.keepMostRecentByFilename} と同様に
     * パッケージプライベートの純粋関数として切り出し、単体テストで検証する。
     */
    static boolean isTruncated(int limit, int fetchedCount) {
        // 上限指定あり: +1 件多く要求しているため、上限より多く取れた場合のみ切り詰めと判定する
        if (limit > 0) {
            return fetchedCount > limit;
        }
        // 上限なし（全件表示）: 取得件数が JsonExecutionStore の安全上限（サーキットブレーカー）
        // に達していれば、それより古い実行が切り捨てられた可能性があるため切り詰めと判定する
        return fetchedCount >= JsonExecutionStore.MAX_UNBOUNDED_RESULTS;
    }

    /**
     * 切り詰め発生時に表示する注記文を組み立てる。{@code limit > 0} なら従来どおり
     * {@code --limit 0} で全件表示できる旨を案内し、{@code limit <= 0} なら（既に全件表示を
     * 要求している利用者へ同じ案内をしても意味がないため）内部の安全上限に達したことを注記する。
     * {@link #isTruncated} と同じ理由でテスト可能な純粋関数として切り出している。
     */
    static String truncationNotice(int limit) {
        // 上限指定あり: 全件表示の方法（--limit 0）を案内する従来の注記文を返す
        if (limit > 0) {
            return String.format("(showing up to %d most recent runs; use --limit 0 to list all)",
                    limit);
        }
        // 上限なし: 安全上限で切り詰められたことを、上限件数とともに注記する
        return String.format(
                "(showing up to %d most recent runs; unbounded-list safety ceiling reached)",
                JsonExecutionStore.MAX_UNBOUNDED_RESULTS);
    }
}
