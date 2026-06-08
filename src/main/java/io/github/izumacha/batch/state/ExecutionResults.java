package io.github.izumacha.batch.state;

import io.github.izumacha.batch.model.ExecutionResult;

import java.time.Instant;
import java.util.Comparator;

/**
 * Shared helpers for working with collections of {@link ExecutionResult}s in
 * the persistence layer.
 */
final class ExecutionResults {

    /**
     * Orders results most recent first by {@code startedAt}, placing results
     * with a {@code null} {@code startedAt} last regardless of direction.
     */
    static final Comparator<ExecutionResult> BY_STARTED_AT_DESC = (a, b) -> {
        Instant sa = a.startedAt();
        Instant sb = b.startedAt();
        if (sa == null && sb == null) {
            return 0;
        }
        if (sa == null) {
            return 1; // a after b
        }
        if (sb == null) {
            return -1; // a before b
        }
        return sb.compareTo(sa); // descending
    };

    private ExecutionResults() {
    }
}
