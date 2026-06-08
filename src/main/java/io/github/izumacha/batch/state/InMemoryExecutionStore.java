package io.github.izumacha.batch.state;

import io.github.izumacha.batch.model.ExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ExecutionStore} backed by a thread-safe map keyed by runId.
 * State lives only for the lifetime of the JVM; useful for tests and ephemeral
 * runs.
 */
public final class InMemoryExecutionStore implements ExecutionStore {

    private final Map<String, ExecutionResult> byRunId = new ConcurrentHashMap<>();

    @Override
    public void save(ExecutionResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        if (result.runId() == null) {
            throw new IllegalArgumentException("result.runId must not be null");
        }
        byRunId.put(result.runId(), result);
    }

    @Override
    public Optional<ExecutionResult> findById(String runId) {
        if (runId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byRunId.get(runId));
    }

    @Override
    public List<ExecutionResult> findAll() {
        List<ExecutionResult> all = new ArrayList<>(byRunId.values());
        all.sort(ExecutionResults.BY_STARTED_AT_DESC);
        return all;
    }
}
