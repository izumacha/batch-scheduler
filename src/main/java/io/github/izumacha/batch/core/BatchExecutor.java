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
    // 実行 ID の乱数部分として生成する 16 進数文字の桁数（6桁 = 約 1677 万通りの組み合わせ）
    private static final int RUN_ID_LENGTH = 6;

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

            // ジョブをトポロジカル順に実行する
            for (Job job : order) {
                // このジョブをブロックしている依存ジョブがあるか確認する
                String blockingDep = firstBlockingDependency(job, results);
                // ブロックしている依存がある場合はジョブをスキップして結果を記録する
                if (blockingDep != null) {
                    results.put(job.id(), JobResult.skipped(
                            job.id(),
                            "skipped: dependency '" + blockingDep + "' did not succeed"));
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
