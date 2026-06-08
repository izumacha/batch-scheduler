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
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("job id is required");
        }
        id = id.trim();
        name = (name == null || name.isBlank()) ? id : name.trim();
        command = command == null ? List.of() : List.copyOf(command);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        env = env == null ? Map.of() : Map.copyOf(env);
        workingDir = (workingDir == null || workingDir.isBlank()) ? null : workingDir.trim();
        if (retries < 0) {
            throw new IllegalArgumentException("retries must be >= 0 (job '" + id + "')");
        }
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeoutSeconds must be >= 0 (job '" + id + "')");
        }
    }

    /** Total number of attempts this job may make (first attempt + retries). */
    public int maxAttempts() {
        return retries + 1;
    }

    /** Whether a per-attempt timeout is configured. */
    public boolean hasTimeout() {
        return timeoutSeconds > 0;
    }
}
