package io.github.izumacha.batch.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.izumacha.batch.model.Batch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a {@link Batch} definition from a YAML (or JSON) document.
 *
 * <p>The loader handles parsing and per-field model validation only. The
 * canonical constructors of {@link io.github.izumacha.batch.model.Job} and
 * {@link Batch} enforce light, per-field invariants (e.g. non-blank id,
 * non-negative retries). Cross-job structural validation (duplicate ids,
 * missing dependencies, cycles) is performed elsewhere when the batch is
 * turned into a dependency graph.
 *
 * <p>Any failure to read or parse the document is surfaced as a
 * {@link ConfigException} whose message identifies the offending source.
 */
public final class BatchConfigLoader {

    private final ObjectMapper mapper;

    public BatchConfigLoader() {
        this.mapper = new ObjectMapper(new YAMLFactory())
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Reads and parses the YAML/JSON file at {@code path} into a {@link Batch}.
     *
     * @throws ConfigException if the file is missing, unreadable, malformed, or
     *                         describes an invalid batch
     */
    public Batch load(Path path) {
        if (path == null) {
            throw new ConfigException("config path must not be null");
        }
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new ConfigException(
                    "failed to read batch config file: " + path + " (" + e.getMessage() + ")", e);
        }
        return parse(content, path.toString());
    }

    /**
     * Parses YAML/JSON text directly into a {@link Batch}. Useful for tests.
     *
     * @throws ConfigException if the content is malformed or describes an
     *                         invalid batch
     */
    public Batch loadFromString(String content) {
        return parse(content, "<string>");
    }

    private Batch parse(String content, String source) {
        if (content == null || content.isBlank()) {
            throw new ConfigException("batch config is empty: " + source);
        }
        Batch batch;
        try {
            batch = mapper.readValue(content, Batch.class);
        } catch (ValueInstantiationException e) {
            // Jackson wraps exceptions thrown from a record's canonical
            // constructor (e.g. IllegalArgumentException for a blank id or
            // negative retries) in a ValueInstantiationException. Surface the
            // underlying validation message together with the source.
            String detail = rootMessage(e);
            throw new ConfigException(
                    "invalid batch config: " + source + " (" + detail + ")", e);
        } catch (JsonMappingException e) {
            String detail = rootMessage(e);
            throw new ConfigException(
                    "invalid batch config: " + source + " (" + detail + ")", e);
        } catch (IOException e) {
            throw new ConfigException(
                    "failed to parse batch config: " + source + " (" + rootMessage(e) + ")", e);
        }
        if (batch == null) {
            throw new ConfigException("batch config is empty: " + source);
        }
        return batch;
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? cause.toString() : message;
    }
}
