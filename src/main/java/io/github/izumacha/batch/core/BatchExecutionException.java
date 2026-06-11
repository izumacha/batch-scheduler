package io.github.izumacha.batch.core;

/**
 * Thrown for unexpected, non-recoverable failures while orchestrating a batch
 * run. Individual job failures are <em>not</em> exceptions — they are recorded
 * as {@code FAILED} results — so this is reserved for problems with the
 * orchestration itself.
 */
public class BatchExecutionException extends RuntimeException {

    public BatchExecutionException(String message) {
        // エラーメッセージだけを持つ例外を生成して親クラスに渡す
        super(message);
    }

    public BatchExecutionException(String message, Throwable cause) {
        // エラーメッセージと原因例外の両方を持つ例外を生成して親クラスに渡す
        super(message, cause);
    }
}
