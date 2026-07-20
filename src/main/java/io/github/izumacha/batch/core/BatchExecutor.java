package io.github.izumacha.batch.core;

import io.github.izumacha.batch.model.Batch;
import io.github.izumacha.batch.model.ExecutionResult;
import io.github.izumacha.batch.model.Job;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Batch} の実行をオーケストレーションするクラス。バッチを {@link DependencyGraph}
 * に変換し、トポロジカル順で {@link JobRunner} を使ってジョブを実行する。依存ジョブが
 * 成功しなかったジョブはスキップし、すべての結果を {@link ExecutionResult} にまとめる。
 */
public final class BatchExecutor {

    // 実行 ID に使うタイムスタンプフォーマット（UTC の「yyyyMMdd-HHmmss」形式）
    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    // 実行 ID に付ける乱数部分を生成する暗号学的に安全な乱数生成器
    private static final SecureRandom RANDOM = new SecureRandom();
    // 16 進数の文字テーブル（乱数を 16 進数文字列に変換するため）
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    // 実行 ID の乱数部分として生成する 16 進数文字の桁数（12桁 = 48ビット ≒ 約 281 兆通りの組み合わせ）
    // タイムスタンプ部の解像度は 1 秒のため、同じ秒に開始した複数実行は乱数部だけで区別する。
    // 6桁(24ビット)では同一秒に多数実行すると誕生日問題で衝突し、JsonExecutionStore が
    // 同名ファイルを無警告で上書きして過去の実行履歴を失う恐れがあった。12桁に広げて
    // 同一秒内の衝突確率を実質ゼロにし、履歴の消失を防ぐ。
    private static final int RUN_ID_LENGTH = 12;

    // 実際にジョブを実行する JobRunner インスタンス
    private final JobRunner runner;

    // デフォルトコンストラクタ：JobRunner をデフォルト設定で作成する
    public BatchExecutor() {
        this(new JobRunner());
    }

    // runner を注入するコンストラクタ（テスト時にモックを使えるようにする）
    public BatchExecutor(JobRunner runner) {
        // runner が null の場合は例外を投げる
        if (runner == null) {
            throw new IllegalArgumentException("runner must not be null");
        }
        // JobRunner インスタンスを格納する
        this.runner = runner;
    }

    /**
     * バッチを実行する。不正なバッチは最初のジョブが実行される前に
     * {@link io.github.izumacha.batch.config.ValidationException} をスローする。
     * 個々のジョブの失敗は記録され、例外にはならない。
     */
    public ExecutionResult execute(Batch batch) {
        // 再利用する前回結果が無い（＝全ジョブを新規実行する）通常実行に委譲する
        return execute(batch, null);
    }

    /**
     * バッチを実行する。{@code priorResult} が非 null の場合は「再実行（rerun-failed）」
     * モードとして動作し、そこで {@link JobStatus#SUCCEEDED} だったジョブは再実行せず
     * 前回の結果をそのまま採用する（docs/DESIGN.md Future extensions
     * 「Resume / rerun-failed」に対応）。前回 FAILED/SKIPPED だったジョブや、
     * 前回に存在しなかった新規ジョブは通常どおり実行する。ただし前回 SUCCEEDED だった
     * ジョブでも、その依存ジョブが「今回の実行内で」FAILED/SKIPPED になった場合は流用せず、
     * 新規実行と同様に SKIPPED として記録する（依存が失敗したのに SUCCEEDED という矛盾した
     * 実行記録を残さないため）。
     *
     * <p>不正なバッチは最初のジョブが実行される前に
     * {@link io.github.izumacha.batch.config.ValidationException} をスローする。
     * {@code priorResult} が非 null かつ {@code batch.name()} と異なるバッチ名を持つ場合は
     * 誤用（別バッチの結果の取り違え）として {@link IllegalArgumentException} をスローする。
     * ただし同名バッチ同士の取り違えまでは検出できない（バッチ名は一意性を強制されない
     * 人間向けラベルのため）。再実行対象のジョブ定義（コマンド・依存関係等）が前回実行時から
     * 変更されていないかどうかも検証しない。運用者が同じ --state-dir を共有する複数バッチを
     * 管理する場合や、成功済みジョブの定義を変更してから再実行する場合はこれらの残余リスクに
     * 留意すること（docs/DESIGN.md 参照）。個々のジョブの失敗は記録され、例外にはならない。
     *
     * @param batch 実行するバッチ定義
     * @param priorResult 再利用する前回の実行結果。{@code null} なら通常実行（全ジョブ新規実行）
     */
    public ExecutionResult execute(Batch batch, ExecutionResult priorResult) {
        // rerun-failed モードで渡された前回結果が「別のバッチ」のものだと、たまたま一致した
        // jobId の結果が無関係なバッチから紛れ込んで誤って流用されてしまう（例: 複数のバッチ
        // 定義が同じ --state-dir を共有し、たまたま同じ job id を持つ場合）。バッチ名の一致を
        // 最低限のガードとして早期に検証し、一致しなければ誤用として拒否する
        // （§9 fail-closed: 不明なら拒否。同名バッチ同士の取り違えまでは検出できない残余
        // リスクだが、docs/DESIGN.md に明記したうえで許容する）
        if (priorResult != null && !batch.name().equals(priorResult.batchName())) {
            throw new IllegalArgumentException(
                    "priorResult belongs to a different batch ('" + priorResult.batchName()
                            + "') than the one being executed ('" + batch.name()
                            + "'); refusing to reuse its job results");
        }
        // バッチを検証して依存グラフを構築する（不正なら ValidationException がスローされる）
        DependencyGraph graph = DependencyGraph.build(batch);

        // バッチ実行の開始時刻を記録する
        Instant startedAt = Instant.now();
        // 開始時刻を使って一意の実行 ID を生成する
        String runId = generateRunId(startedAt);

        try {
            // トポロジカル順にジョブリストを取得する
            List<Job> order = graph.topologicalOrder();
            // ジョブ ID をキー、実行結果を値とするマップ（宣言順を保持）
            Map<String, JobResult> results = new LinkedHashMap<>();
            // rerun-failed 用に前回結果を jobId で O(1) 参照できるようマップ化しておく。
            // ExecutionResult#result(String) はジョブ結果リストをその都度線形走査するため、
            // これをジョブごとの下のループ内で毎回呼ぶとバッチ全体では O(ジョブ数 × 前回結果数)
            // に劣化する（DependencyGraph.topologicalOrder が batch.job(id) の線形走査を避けて
            // jobsById を事前構築しているのと同じ理由でここも避ける。§8 パフォーマンス）
            Map<String, JobResult> priorResultsById = indexPriorResults(priorResult);

            // ジョブをトポロジカル順に実行する
            for (Job job : order) {
                // このジョブをブロックしている依存ジョブがあるか確認する。
                // なぜ前回結果の流用判定より「先に」行うか（§6 設計判断）: rerun-failed では
                // 前回 SUCCEEDED だったジョブでも、バッチ定義の編集（例: 新規ジョブへの依存追加）
                // により、その依存ジョブが「今回の実行内で」FAILED/SKIPPED になり得る。流用を
                // 先に判定すると「依存が失敗したのに SUCCEEDED」という、新規実行では決して
                // 起こらない矛盾した実行記録が生まれてしまうため、通常実行と同じ SKIPPED を優先する
                String blockingDep = firstBlockingDependency(job, results);
                // ブロックしている依存がある場合はジョブをスキップして結果を記録する
                if (blockingDep != null) {
                    results.put(job.id(), JobResult.skipped(
                            job.id(),
                            "skipped: dependency '" + blockingDep + "' did not succeed"));
                    continue;
                }
                // rerun-failed モードで、このジョブが前回 SUCCEEDED していれば再実行せず
                // 前回の結果をそのまま流用する（依存側のブロック判定にもこの結果を使う）
                JobResult reused = reusablePriorResult(priorResultsById, job.id());
                if (reused != null) {
                    results.put(job.id(), reused);
                    continue;
                }
                // ブロックがない場合は JobRunner でジョブを実行し、結果を記録する
                results.put(job.id(), runner.run(job));
            }

            // 実行順（トポロジカル順）でジョブ結果リストを作成する
            List<JobResult> jobResults = new ArrayList<>(results.values());
            // 全ジョブが成功した場合は SUCCEEDED、そうでなければ FAILED とする
            JobStatus overall = jobResults.stream().allMatch(r -> r.status() == JobStatus.SUCCEEDED)
                    ? JobStatus.SUCCEEDED
                    : JobStatus.FAILED;

            // バッチ実行の終了時刻を記録する
            Instant finishedAt = Instant.now();
            // バッチ全体の実行結果を組み立てて返す
            return new ExecutionResult(
                    runId,
                    batch.name(),
                    overall,
                    startedAt,
                    finishedAt,
                    jobResults);
        } catch (RuntimeException e) {
            // 予期しない例外をオーケストレーション失敗として包んでスローする
            throw new BatchExecutionException("unexpected error while executing batch '"
                    + batch.name() + "' (runId=" + runId + ")", e);
        }
    }

    /**
     * {@code priorResult} のジョブ結果を jobId で O(1) 参照できるようマップ化する。
     * {@code priorResult} が {@code null}（通常実行）の場合は空マップを返す。
     *
     * <p>前回結果は状態ディレクトリから読み込んだ、手動改変や破損の可能性がある入力
     * （docs/DESIGN.md「State-directory safety」）であるため、同じ jobId が複数回
     * 現れても例外にはせず、{@link ExecutionResult#result(String)}（最初に見つかった
     * ものを返す）と同じ「最初の出現を採用する」挙動を {@code putIfAbsent} で踏襲する。
     */
    private static Map<String, JobResult> indexPriorResults(ExecutionResult priorResult) {
        // 通常実行（priorResult が無い）なら空マップを返す
        if (priorResult == null) {
            return Map.of();
        }
        // 宣言（記録）順を保持しつつ jobId をキーにしたマップを組み立てる
        Map<String, JobResult> byId = new LinkedHashMap<>();
        for (JobResult r : priorResult.jobResults()) {
            // 同じ jobId が重複していても最初の 1 件だけを登録する
            byId.putIfAbsent(r.jobId(), r);
        }
        return byId;
    }

    /**
     * rerun-failed モードで、指定ジョブを再実行せず流用できる前回結果を返す。
     * 前回結果が無い（通常実行）・前回そのジョブが存在しなかった（新規ジョブ）・
     * 前回 SUCCEEDED でなかった（FAILED/SKIPPED は再実行対象）場合は {@code null} を返す。
     */
    private static JobResult reusablePriorResult(Map<String, JobResult> priorResultsById, String jobId) {
        // 前回結果マップからこのジョブ ID を検索し、SUCCEEDED だった場合のみ流用対象として返す
        JobResult prior = priorResultsById.get(jobId);
        return prior != null && prior.succeeded() ? prior : null;
    }

    /**
     * このジョブをブロックしている最初の依存ジョブ ID を返す。すべての依存が成功していれば
     * {@code null} を返す。スキップされた依存もブロック扱いになるためスキップは推移的に伝播する。
     */
    private static String firstBlockingDependency(Job job,
                                                  Map<String, JobResult> results) {
        // job.dependsOn() は宣言順を保持している（graph.dependenciesOf はセットで順序が不定）
        // ため、ブロック元として「宣言順で最初の」依存 ID を決定的に返せるこちらを使う
        for (String dep : job.dependsOn()) {
            // この依存ジョブの実行結果を取得する
            JobResult depResult = results.get(dep);
            // 依存ジョブが後続をブロックする状態（FAILED または SKIPPED）なら依存 ID を返す
            if (depResult != null && depResult.status().blocksDependents()) {
                return dep;
            }
        }
        // ブロックしている依存がない場合は null を返す
        return null;
    }

    // 開始時刻と乱数を組み合わせた一意の実行 ID を生成するメソッド
    private static String generateRunId(Instant when) {
        // RUN_ID_LENGTH 桁の 16 進数乱数を格納するバッファを作成する
        StringBuilder hex = new StringBuilder(RUN_ID_LENGTH);
        // RUN_ID_LENGTH 桁分の乱数 16 進数文字を生成する
        for (int i = 0; i < RUN_ID_LENGTH; i++) {
            // 0〜(HEX.length-1) のランダムな整数を添字として HEX 配列から 16 進数文字を取り出して追加する
            // HEX.length を使うことで HEX 配列の要素数と常に整合し、定数の値ずれを防ぐ
            hex.append(HEX[RANDOM.nextInt(HEX.length)]);
        }
        // 「yyyyMMdd-HHmmss-XXXXXX」形式の実行 ID を返す
        return RUN_ID_FORMAT.format(when) + "-" + hex;
    }
}
