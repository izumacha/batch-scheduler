package io.github.izumacha.batch.config;

/**
 * Thrown when a batch configuration file cannot be read or parsed.
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
