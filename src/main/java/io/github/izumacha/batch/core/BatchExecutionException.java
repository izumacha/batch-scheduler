package io.github.izumacha.batch.core;

/**
 * Thrown for unexpected, non-recoverable failures while orchestrating a batch
 * run. Individual job failures are <em>not</em> exceptions — they are recorded
 * as {@code FAILED} results — so this is reserved for problems with the
 * orchestration itself.
 */
public class BatchExecutionException extends RuntimeException {

    public BatchExecutionException(String message) {
        super(message);
    }

    public BatchExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
