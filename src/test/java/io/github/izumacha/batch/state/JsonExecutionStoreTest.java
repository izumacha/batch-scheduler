package io.github.izumacha.batch.state;

import io.github.izumacha.batch.model.ExecutionResult;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonExecutionStoreTest {

    private static ExecutionResult sampleRun(String runId, Instant start) {
        Instant end = start.plusSeconds(5);
        JobResult a = new JobResult("a", JobStatus.SUCCEEDED, 0, 1,
                start, start.plusSeconds(2), "exit 0");
        JobResult b = new JobResult("b", JobStatus.FAILED, 1, 2,
                start.plusSeconds(2), end, "exit 1");
        return new ExecutionResult(runId, "etl", JobStatus.FAILED, start, end, List.of(a, b));
    }

    @Test
    void saveThenFindByIdRoundTrips(@TempDir Path dir) {
        JsonExecutionStore store = new JsonExecutionStore(dir);
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ExecutionResult run = sampleRun("run1", start);

        store.save(run);

        // The JSON file is named after the runId.
        assertTrue(Files.isRegularFile(dir.resolve("run1.json")));

        Optional<ExecutionResult> found = store.findById("run1");
        assertTrue(found.isPresent());
        ExecutionResult loaded = found.get();

        assertEquals(run.runId(), loaded.runId());
        assertEquals(run.batchName(), loaded.batchName());
        assertEquals(run.status(), loaded.status());
        assertEquals(run.startedAt(), loaded.startedAt());
        assertEquals(run.finishedAt(), loaded.finishedAt());
        assertEquals(run.jobResults().size(), loaded.jobResults().size());

        JobResult firstOriginal = run.jobResults().get(0);
        JobResult firstLoaded = loaded.jobResults().get(0);
        assertEquals(firstOriginal.jobId(), firstLoaded.jobId());
        assertEquals(firstOriginal.status(), firstLoaded.status());
        assertEquals(firstOriginal.exitCode(), firstLoaded.exitCode());
        assertEquals(firstOriginal.attempts(), firstLoaded.attempts());
        assertEquals(firstOriginal.startedAt(), firstLoaded.startedAt());
        assertEquals(firstOriginal.finishedAt(), firstLoaded.finishedAt());
        assertEquals(firstOriginal.message(), firstLoaded.message());

        // Full record equality (records implement value-based equals).
        assertEquals(run, loaded);
    }

    @Test
    void findByIdMissingReturnsEmpty(@TempDir Path dir) {
        JsonExecutionStore store = new JsonExecutionStore(dir);
        assertTrue(store.findById("nope").isEmpty());
    }

    @Test
    void findAllReturnsSavedRun(@TempDir Path dir) {
        JsonExecutionStore store = new JsonExecutionStore(dir);
        ExecutionResult run = sampleRun("run1", Instant.now().truncatedTo(ChronoUnit.MILLIS));
        store.save(run);

        List<ExecutionResult> all = store.findAll();
        assertEquals(1, all.size());
        assertEquals("run1", all.get(0).runId());
    }

    @Test
    void findAllOrdersByStartedAtDescending(@TempDir Path dir) {
        JsonExecutionStore store = new JsonExecutionStore(dir);
        Instant older = Instant.parse("2024-01-01T00:00:00Z");
        Instant newer = Instant.parse("2024-06-01T00:00:00Z");

        store.save(sampleRun("old", older));
        store.save(sampleRun("new", newer));

        List<ExecutionResult> all = store.findAll();
        assertEquals(2, all.size());
        assertEquals("new", all.get(0).runId());
        assertEquals("old", all.get(1).runId());
    }

    @Test
    void findAllSkipsUnparseableFiles(@TempDir Path dir) throws Exception {
        JsonExecutionStore store = new JsonExecutionStore(dir);
        store.save(sampleRun("good", Instant.now()));
        Files.writeString(dir.resolve("garbage.json"), "{ this is not valid json");

        List<ExecutionResult> all = store.findAll();
        assertEquals(1, all.size());
        assertEquals("good", all.get(0).runId());
    }

    @Test
    void saveOverwritesByRunId(@TempDir Path dir) {
        JsonExecutionStore store = new JsonExecutionStore(dir);
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.save(new ExecutionResult("run1", "etl", JobStatus.FAILED, start,
                start.plusSeconds(1), List.of()));
        store.save(new ExecutionResult("run1", "etl", JobStatus.SUCCEEDED, start,
                start.plusSeconds(1), List.of()));

        ExecutionResult loaded = store.findById("run1").orElseThrow();
        assertEquals(JobStatus.SUCCEEDED, loaded.status());
        assertEquals(1, store.findAll().size());
    }

    @Test
    void createsBaseDirIfAbsent(@TempDir Path dir) {
        Path nested = dir.resolve("nested/store");
        assertFalse(Files.exists(nested));
        new JsonExecutionStore(nested);
        assertTrue(Files.isDirectory(nested));
    }
}
