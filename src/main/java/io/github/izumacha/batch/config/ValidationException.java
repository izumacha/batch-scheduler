package io.github.izumacha.batch.config;

import java.util.List;

/**
 * Thrown when a batch is structurally invalid (e.g. duplicate job ids, a
 * missing dependency, an empty command, or a dependency cycle). Carries the
 * full list of problems so the caller can report them all at once.
 */
public class ValidationException extends RuntimeException {

    private final List<String> errors;

    public ValidationException(List<String> errors) {
        super(buildMessage(errors));
        this.errors = List.copyOf(errors);
    }

    /** The individual validation problems, in detection order. */
    public List<String> errors() {
        return errors;
    }

    private static String buildMessage(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return "batch validation failed";
        }
        StringBuilder sb = new StringBuilder("batch validation failed with ")
                .append(errors.size())
                .append(errors.size() == 1 ? " error:" : " errors:");
        for (String e : errors) {
            sb.append("\n  - ").append(e);
        }
        return sb.toString();
    }
}
