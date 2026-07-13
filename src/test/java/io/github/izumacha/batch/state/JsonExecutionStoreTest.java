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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void findByIdSkipsUnparseableFile(@TempDir Path dir) throws Exception {
        // 壊れた JSON を runId 名のファイルとして直接置く（途中書き込みや手動改変を模擬）
        JsonExecutionStore store = new JsonExecutionStore(dir);
        Files.writeString(dir.resolve("broken.json"), "{ this is not valid json");

        // findById は findAll と同じく壊れたファイルを読み飛ばし、例外を投げず空を返すべき
        assertTrue(store.findById("broken").isEmpty());
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
    void rejectsRunIdsWithPathSeparatorsToPreventTraversal(@TempDir Path dir) throws Exception {
        JsonExecutionStore store = new JsonExecutionStore(dir.resolve("state"));
        Instant start = Instant.now();

        for (String evil : List.of("../escape", "a/b", "..", "a\\b")) {
            ExecutionResult run = new ExecutionResult(evil, "etl", JobStatus.SUCCEEDED,
                    start, start.plusSeconds(1), List.of());
            assertThrows(IllegalArgumentException.class, () -> store.save(run), evil);
            assertThrows(IllegalArgumentException.class, () -> store.findById(evil), evil);
        }

        // Nothing escaped the state directory.
        assertFalse(Files.exists(dir.resolve("escape.json")));
    }

    @Test
    void skippedJobsWithNullInstantsRoundTrip(@TempDir Path dir) {
        JsonExecutionStore store = new JsonExecutionStore(dir);
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        JobResult skipped = JobResult.skipped("b", "skipped: dependency 'a' did not succeed");
        ExecutionResult run = new ExecutionResult("skip1", "etl", JobStatus.FAILED,
                start, start.plusSeconds(1), List.of(skipped));

        store.save(run);

        JobResult loaded = store.findById("skip1").orElseThrow().result("b").orElseThrow();
        assertEquals(JobStatus.SKIPPED, loaded.status());
        assertEquals(JobResult.NO_EXIT_CODE, loaded.exitCode());
        assertNull(loaded.startedAt());
        assertNull(loaded.finishedAt());
    }

    @Test
    void findAllSkipsFileWithNullJobIdInsteadOfThrowing(@TempDir Path dir) throws Exception {
        // JobResult.jobId は必須（本クラスと同じ「壊れたファイルは読み飛ばす」契約）。
        // jobId が null の jobResults エントリを含む手動改変ファイルは、JobResult の
        // コンストラクタ検証によって ValueInstantiationException(IOException 系)になり、
        // tryRead が他の壊れたファイルと同様に読み飛ばすべきで、未捕捉例外にならないことを確認する。
        JsonExecutionStore store = new JsonExecutionStore(dir);
        store.save(sampleRun("good", Instant.now()));
        Files.writeString(dir.resolve("badjobid.json"), """
                {
                  "runId": "badjobid",
                  "batchName": "etl",
                  "status": "SUCCEEDED",
                  "startedAt": "2024-01-01T00:00:00Z",
                  "finishedAt": "2024-01-01T00:00:01Z",
                  "jobResults": [
                    { "jobId": null, "status": "SUCCEEDED", "exitCode": 0, "attempts": 1 }
                  ]
                }
                """);

        List<ExecutionResult> all = store.findAll();
        assertEquals(1, all.size());
        assertEquals("good", all.get(0).runId());
    }

    @Test
    void savesRunWithVeryShortRunId(@TempDir Path dir) {
        // A one-character runId must not blow up the temp-file prefix.
        JsonExecutionStore store = new JsonExecutionStore(dir);
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.save(new ExecutionResult("a", "etl", JobStatus.SUCCEEDED, start,
                start.plusSeconds(1), List.of()));

        assertTrue(Files.isRegularFile(dir.resolve("a.json")));
        assertEquals("a", store.findById("a").orElseThrow().runId());
    }

    @Test
    void createsBaseDirIfAbsent(@TempDir Path dir) {
        Path nested = dir.resolve("nested/store");
        assertFalse(Files.exists(nested));
        new JsonExecutionStore(nested);
        assertTrue(Files.isDirectory(nested));
    }

    @Test
    void findRecentReturnsOnlyNewestUpToLimit(@TempDir Path dir) {
        JsonExecutionStore store = new JsonExecutionStore(dir);
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        // 5 件を古い順に保存する（開始時刻を 1 日ずつずらす）
        for (int i = 0; i < 5; i++) {
            store.save(sampleRun("run" + i, base.plus(i, ChronoUnit.DAYS)));
        }

        // 上限 2 件を要求すると、最新順（開始時刻の降順）で先頭 2 件だけが返る
        List<ExecutionResult> recent = store.findRecent(2);
        assertEquals(2, recent.size());
        assertEquals("run4", recent.get(0).runId());
        assertEquals("run3", recent.get(1).runId());
    }

    @Test
    void findRecentWithZeroOrNegativeLimitReturnsAll(@TempDir Path dir) {
        JsonExecutionStore store = new JsonExecutionStore(dir);
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            store.save(sampleRun("run" + i, base.plus(i, ChronoUnit.DAYS)));
        }

        // 0 と負の上限はどちらも「上限なし」として全件を返す（境界値）
        assertEquals(3, store.findRecent(0).size());
        assertEquals(3, store.findRecent(-1).size());
    }

    @Test
    void findRecentWithLimitAboveCountReturnsAll(@TempDir Path dir) {
        JsonExecutionStore store = new JsonExecutionStore(dir);
        store.save(sampleRun("only", Instant.parse("2024-01-01T00:00:00Z")));

        // 保存件数より大きい上限では全件がそのまま返る
        assertEquals(1, store.findRecent(10).size());
    }

    @Test
    void findRecentSkipsUnparseableFileWithinWindow(@TempDir Path dir) throws Exception {
        // findRecent は候補ファイル名を絞り込んだ後に初めてパースするため、絞り込んだ
        // 少数件（limit 件）の中に壊れたファイルが混ざっていても読み飛ばせることを確認する
        JsonExecutionStore store = new JsonExecutionStore(dir);
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        store.save(sampleRun("run0", base));
        store.save(sampleRun("run2", base.plus(2, ChronoUnit.DAYS)));
        // "run1" は破損した JSON として直接置く（実行結果としては保存しない）
        Files.writeString(dir.resolve("run1.json"), "{ this is not valid json");

        // 上限 3 件を要求しても、壊れたファイルは読み飛ばされ有効な 2 件だけが返る
        List<ExecutionResult> recent = store.findRecent(3);
        assertEquals(2, recent.size());
        assertEquals("run2", recent.get(0).runId());
        assertEquals("run0", recent.get(1).runId());
    }
}
