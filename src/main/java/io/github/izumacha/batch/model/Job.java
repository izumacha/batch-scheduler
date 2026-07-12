package io.github.izumacha.batch.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Immutable definition of a single executable unit within a batch.
 *
 * <p>Instances are normalized on construction: blank optional values become
 * sensible defaults and collections are defensively copied into immutable
 * views. Light, per-field validation happens here; cross-job validation
 * (duplicate ids, missing dependencies, cycles) is performed when a
 * {@link Batch} is turned into a dependency graph.
 *
 * @param id             unique identifier of the job (required)
 * @param name           human-friendly label; defaults to {@code id} when blank
 * @param command        the command and its arguments to execute (required, non-empty)
 * @param dependsOn      ids of jobs that must succeed before this one runs
 * @param retries        number of <em>additional</em> attempts after the first failure ({@code 0..}{@value #MAX_RETRIES})
 * @param timeoutSeconds per-attempt timeout in seconds; {@code 0} means no timeout
 * @param env            extra environment variables for the spawned process
 * @param workingDir     working directory for the process; {@code null} inherits the launcher's
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public record Job(
        String id,
        String name,
        List<String> command,
        List<String> dependsOn,
        int retries,
        long timeoutSeconds,
        Map<String, String> env,
        String workingDir
) {

    /**
     * リトライ回数の上限（この値までを許容する）。
     * <p>{@code maxAttempts()} が {@code retries + 1} を int で計算するため、
     * {@code Integer.MAX_VALUE} 付近の値だと桁あふれ（オーバーフロー）して
     * 最大試行回数が負になり、ジョブが 1 度も実行されなくなる。それを防ぐ現実的な上限。
     */
    public static final int MAX_RETRIES = 1_000_000;

    public Job {
        // id が null または空白の場合は例外を投げる（ジョブ ID は必須）
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("job id is required");
        }
        // id の前後の空白を除去して正規化する
        id = id.trim();
        // name が空白なら id をデフォルトの表示名として使う
        name = (name == null || name.isBlank()) ? id : name.trim();
        // command が null なら空リストに、そうでなければ変更不可のコピーにする
        command = command == null ? List.of() : List.copyOf(command);
        // dependsOn が null なら空リストにする。null でなければ各依存 ID の前後の空白を
        // 除去して変更不可のコピーにする。id を trim して正規化している（54 行目）のに対し
        // 依存側を trim しないと、DependencyGraph.build が trim 済み ID と突き合わせる際に
        // 「build 」→「build」が一致せず、自己整合なバッチが unknown job として誤って弾かれる。
        // 依存要素の null は他フィールドと同様ここで明示的に弾く。旧実装の List.copyOf は
        // null 要素を NPE で拒否し BatchConfigLoader が ConfigException に変換していたため、
        // Stream.toList()（null 許容）へ置き換えると null が DependencyGraph まで素通りして
        // 未捕捉 NPE＋スタックトレース露出になる（§6/§9 違反）。それを防ぐ。
        if (dependsOn != null && dependsOn.stream().anyMatch(d -> d == null)) {
            throw new IllegalArgumentException("dependsOn entry must not be null (job '" + id + "')");
        }
        dependsOn = dependsOn == null ? List.of()
                : dependsOn.stream().map(String::trim).toList();
        // env が null なら空マップに、そうでなければ変更不可のコピーにする
        env = env == null ? Map.of() : Map.copyOf(env);
        // workingDir が空白なら null に、そうでなければ前後の空白を除去する
        workingDir = (workingDir == null || workingDir.isBlank()) ? null : workingDir.trim();
        // retries が負の値、または上限 MAX_RETRIES を超える場合は例外を投げる
        // （下限 0 は既存要件、上限は maxAttempts() の int オーバーフロー防止のため）
        if (retries < 0 || retries > MAX_RETRIES) {
            throw new IllegalArgumentException(
                    "retries must be between 0 and " + MAX_RETRIES + " (job '" + id + "')");
        }
        // timeoutSeconds が負の値の場合は例外を投げる（タイムアウト秒数は 0 以上が必須）
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeoutSeconds must be >= 0 (job '" + id + "')");
        }
    }

    /** Total number of attempts this job may make (first attempt + retries). */
    public int maxAttempts() {
        // 最大試行回数 = 初回実行 1 回 + リトライ回数 を返す
        return retries + 1;
    }

    /** Whether a per-attempt timeout is configured. */
    public boolean hasTimeout() {
        // timeoutSeconds が 0 より大きければタイムアウトが設定されていると判定する
        return timeoutSeconds > 0;
    }
}
