package io.github.izumacha.batch.state;

import io.github.izumacha.batch.model.ExecutionResult;

import java.util.List;
import java.util.Optional;

/**
 * Persists and retrieves {@link ExecutionResult}s so that the history and
 * current state of batch runs can be inspected after the fact.
 */
public interface ExecutionStore {

    /** Persists (or overwrites) the given run report. */
    void save(ExecutionResult result);

    /** Returns the run with the given id, if present. */
    Optional<ExecutionResult> findById(String runId);

    /** Returns all stored runs, most recent first. */
    List<ExecutionResult> findAll();
}
