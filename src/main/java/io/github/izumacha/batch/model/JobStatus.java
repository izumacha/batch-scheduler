package io.github.izumacha.batch.model;

/**
 * Lifecycle state of a single job within a batch run.
 */
public enum JobStatus {

    /** Defined but not yet started. */
    PENDING,

    /** Currently executing. */
    RUNNING,

    /** Finished with a zero (success) exit code. */
    SUCCEEDED,

    /** Finished with a non-zero exit code, timed out, or failed to start. */
    FAILED,

    /** Not executed because one of its dependencies did not succeed. */
    SKIPPED;

    /** A terminal state is one a job will not transition out of. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED;
    }

    /** Whether this state should block dependent jobs from running. */
    public boolean blocksDependents() {
        return this == FAILED || this == SKIPPED;
    }
}
