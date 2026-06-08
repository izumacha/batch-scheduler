package io.github.izumacha.batch.config;

import io.github.izumacha.batch.model.Batch;
import io.github.izumacha.batch.model.Job;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchConfigLoaderTest {

    private final BatchConfigLoader loader = new BatchConfigLoader();

    private static final String VALID_YAML = """
            name: etl
            jobs:
              - id: extract
                command: ["sh", "-c", "echo extract"]
              - id: transform
                command: ["sh", "-c", "echo transform"]
                dependsOn: [extract]
                retries: 2
                timeoutSeconds: 30
              - id: load
                command: ["sh", "-c", "echo load"]
                dependsOn: [transform]
            """;

    @Test
    void parsesValidYaml() {
        Batch batch = loader.loadFromString(VALID_YAML);

        assertEquals("etl", batch.name());
        assertEquals(3, batch.jobs().size());

        Job extract = batch.job("extract").orElseThrow();
        // name defaults to id when not provided
        assertEquals("extract", extract.name());
        assertEquals(0, extract.retries());
        assertEquals(0L, extract.timeoutSeconds());
        assertTrue(extract.dependsOn().isEmpty());
        assertEquals(3, extract.command().size());

        Job transform = batch.job("transform").orElseThrow();
        assertEquals(2, transform.retries());
        assertEquals(30L, transform.timeoutSeconds());
        assertEquals(java.util.List.of("extract"), transform.dependsOn());

        Job load = batch.job("load").orElseThrow();
        assertEquals(java.util.List.of("transform"), load.dependsOn());
    }

    @Test
    void parsesJsonContent() {
        String json = """
                {"name":"j","jobs":[{"id":"a","command":["sh","-c","echo a"]}]}
                """;
        Batch batch = loader.loadFromString(json);
        assertEquals("j", batch.name());
        assertEquals(1, batch.jobs().size());
        assertEquals("a", batch.jobs().get(0).id());
    }

    @Test
    void blankIdSurfacesConfigException() {
        String yaml = """
                name: bad
                jobs:
                  - id: ""
                    command: ["sh", "-c", "echo x"]
                """;
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        assertTrue(ex.getMessage().contains("job id is required"),
                "message should mention the validation issue, was: " + ex.getMessage());
    }

    @Test
    void negativeRetriesSurfacesConfigException() {
        String yaml = """
                name: bad
                jobs:
                  - id: a
                    command: ["sh", "-c", "echo x"]
                    retries: -1
                """;
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        assertTrue(ex.getMessage().contains("retries must be >= 0"),
                "message should mention the validation issue, was: " + ex.getMessage());
    }

    @Test
    void malformedYamlSurfacesConfigException() {
        String yaml = "name: etl\njobs: [oops: : :";
        assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
    }

    @Test
    void emptyContentSurfacesConfigException() {
        assertThrows(ConfigException.class, () -> loader.loadFromString("   "));
    }

    @Test
    void loadsFromFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("batch.yaml");
        Files.writeString(file, VALID_YAML);

        Batch batch = loader.load(file);
        assertNotNull(batch);
        assertEquals(3, batch.jobs().size());
    }

    @Test
    void missingFileThrowsConfigExceptionWithPath(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.yaml");
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.load(missing));
        assertTrue(ex.getMessage().contains(missing.toString()),
                "message should contain the path, was: " + ex.getMessage());
    }

    @Test
    void invalidFileThrowsConfigExceptionWithPath(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad.yaml");
        Files.writeString(file, """
                name: bad
                jobs:
                  - id: ""
                    command: ["sh"]
                """);
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.load(file));
        assertTrue(ex.getMessage().contains(file.toString()),
                "message should contain the path, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("job id is required"),
                "message should mention the validation issue, was: " + ex.getMessage());
    }
}
