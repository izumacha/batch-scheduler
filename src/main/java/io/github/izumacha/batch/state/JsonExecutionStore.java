package io.github.izumacha.batch.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.izumacha.batch.model.ExecutionResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed {@link ExecutionStore} that persists one pretty-printed JSON
 * document per run, named {@code <runId>.json}, under a base directory.
 *
 * <p>Instants are written as ISO-8601 strings (timestamps disabled) and reads
 * tolerate unparseable files by skipping them.
 */
public final class JsonExecutionStore implements ExecutionStore {

    private static final String SUFFIX = ".json";

    private final Path baseDir;
    private final ObjectMapper mapper;

    public JsonExecutionStore(Path baseDir) {
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir must not be null");
        }
        this.baseDir = baseDir;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to create execution store directory: " + baseDir, e);
        }
    }

    @Override
    public void save(ExecutionResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        if (result.runId() == null || result.runId().isBlank()) {
            throw new IllegalArgumentException("result.runId must not be null or blank");
        }
        try {
            Files.createDirectories(baseDir);
            Path target = fileFor(result.runId());
            // Write to a temp file in the same directory, then move atomically
            // so readers never observe a half-written file.
            Path tmp = Files.createTempFile(baseDir, result.runId() + "-", ".tmp");
            try {
                mapper.writeValue(tmp.toFile(), result);
                try {
                    Files.move(tmp, target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    // Some filesystems don't support atomic moves; fall back.
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to save execution result '" + result.runId() + "' under " + baseDir, e);
        }
    }

    @Override
    public Optional<ExecutionResult> findById(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        Path file = fileFor(runId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), ExecutionResult.class));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to read execution result '" + runId + "' from " + file, e);
        }
    }

    @Override
    public List<ExecutionResult> findAll() {
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        List<ExecutionResult> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(baseDir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .forEach(p -> {
                        try {
                            results.add(mapper.readValue(p.toFile(), ExecutionResult.class));
                        } catch (IOException ignored) {
                            // Skip files that fail to parse; they may be partial
                            // writes or unrelated documents.
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to list execution results under " + baseDir, e);
        }
        results.sort(ExecutionResults.BY_STARTED_AT_DESC);
        return results;
    }

    private Path fileFor(String runId) {
        return baseDir.resolve(runId + SUFFIX);
    }
}
