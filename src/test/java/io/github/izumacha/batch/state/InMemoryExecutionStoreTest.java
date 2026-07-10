package io.github.izumacha.batch.state;

import io.github.izumacha.batch.model.ExecutionResult;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryExecutionStoreTest {

    private static ExecutionResult run(String runId, Instant start, JobStatus status) {
        Instant end = start.plusSeconds(3);
        JobResult a = new JobResult("a", JobStatus.SUCCEEDED, 0, 1, start, end, "exit 0");
        return new ExecutionResult(runId, "etl", status, start, end, List.of(a));
    }

    @Test
    void saveThenFindById() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        ExecutionResult r = run("run1", Instant.now(), JobStatus.SUCCEEDED);

        store.save(r);

        Optional<ExecutionResult> found = store.findById("run1");
        assertTrue(found.isPresent());
        // In-memory store returns the very same instance.
        assertSame(r, found.get());
    }

    @Test
    void findByIdMissingReturnsEmpty() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        assertTrue(store.findById("nope").isEmpty());
    }

    @Test
    void findByIdNullReturnsEmpty() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        assertTrue(store.findById(null).isEmpty());
    }

    @Test
    void saveOverwritesByRunId() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        Instant start = Instant.now();
        store.save(run("run1", start, JobStatus.FAILED));
        store.save(run("run1", start, JobStatus.SUCCEEDED));

        ExecutionResult loaded = store.findById("run1").orElseThrow();
        assertEquals(JobStatus.SUCCEEDED, loaded.status());
        assertEquals(1, store.findAll().size());
    }

    @Test
    void findAllOrdersByStartedAtDescending() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        Instant older = Instant.parse("2024-01-01T00:00:00Z");
        Instant middle = Instant.parse("2024-03-01T00:00:00Z");
        Instant newer = Instant.parse("2024-06-01T00:00:00Z");

        store.save(run("old", older, JobStatus.SUCCEEDED));
        store.save(run("new", newer, JobStatus.SUCCEEDED));
        store.save(run("mid", middle, JobStatus.SUCCEEDED));

        List<ExecutionResult> all = store.findAll();
        assertEquals(3, all.size());
        assertEquals("new", all.get(0).runId());
        assertEquals("mid", all.get(1).runId());
        assertEquals("old", all.get(2).runId());
    }

    @Test
    void findAllHandlesNullStartedAtSafely() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        store.save(new ExecutionResult("withTime", "etl", JobStatus.SUCCEEDED,
                start, start.plusSeconds(1), List.of()));
        store.save(new ExecutionResult("noTime", "etl", JobStatus.SKIPPED,
                null, null, List.of()));

        List<ExecutionResult> all = store.findAll();
        assertEquals(2, all.size());
        // Runs with a startedAt sort ahead of those without.
        assertEquals("withTime", all.get(0).runId());
        assertEquals("noTime", all.get(1).runId());
    }

    @Test
    void findAllEmptyWhenNothingSaved() {
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        assertTrue(store.findAll().isEmpty());
    }

    @Test
    void findRecentLimitsToNewestViaDefaultMethod() {
        // findRecent は ExecutionStore の default 実装。InMemory ストアで最新順に
        // 上限が掛かることを確認する（最新順は findAll の並びに従う）。
        InMemoryExecutionStore store = new InMemoryExecutionStore();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        store.save(run("old", base, JobStatus.SUCCEEDED));
        store.save(run("new", base.plusSeconds(120), JobStatus.SUCCEEDED));
        store.save(run("mid", base.plusSeconds(60), JobStatus.SUCCEEDED));

        List<ExecutionResult> recent = store.findRecent(2);
        assertEquals(2, recent.size());
        assertEquals("new", recent.get(0).runId());
        assertEquals("mid", recent.get(1).runId());

        // 0 以下は上限なしで全件を返す（境界値）
        assertEquals(3, store.findRecent(0).size());
    }
}
