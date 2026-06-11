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
 * @param retries        number of <em>additional</em> attempts after the first failure ({@code >= 0})
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
        // dependsOn が null なら空リストに、そうでなければ変更不可のコピーにする
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        // env が null なら空マップに、そうでなければ変更不可のコピーにする
        env = env == null ? Map.of() : Map.copyOf(env);
        // workingDir が空白なら null に、そうでなければ前後の空白を除去する
        workingDir = (workingDir == null || workingDir.isBlank()) ? null : workingDir.trim();
        // retries が負の値の場合は例外を投げる（リトライ回数は 0 以上が必須）
        if (retries < 0) {
            throw new IllegalArgumentException("retries must be >= 0 (job '" + id + "')");
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
