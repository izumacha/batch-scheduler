package io.github.izumacha.batch.config;

/**
 * Thrown when a batch configuration file cannot be read or parsed.
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        // エラーメッセージだけを持つ例外を生成して親クラスに渡す
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        // エラーメッセージと原因例外の両方を持つ例外を生成して親クラスに渡す
        super(message, cause);
    }
}
