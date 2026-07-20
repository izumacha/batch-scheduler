package io.github.izumacha.batch.state;

import io.github.izumacha.batch.model.ExecutionResult;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
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
    void findByIdSkipsOversizedFile(@TempDir Path dir) throws Exception {
        // MAX_RECORD_BYTES を超えるファイルは、パースを試みる前にサイズだけで
        // 読み飛ばされるべき（BatchConfigLoader のオーバーサイズ拒否と同じ防御）。
        // 有効な JSON かどうかは無関係にサイズだけで弾かれることを確認するため、
        // 内容は単純な埋め草文字列にする（パースまで到達しないことの裏付けにもなる）。
        JsonExecutionStore store = new JsonExecutionStore(dir);
        byte[] filler = new byte[(int) JsonExecutionStore.MAX_RECORD_BYTES + 1];
        java.util.Arrays.fill(filler, (byte) ' ');
        Files.write(dir.resolve("huge.json"), filler);

        assertTrue(store.findById("huge").isEmpty());
    }

    @Test
    void findAllSkipsOversizedFileButReturnsOthers(@TempDir Path dir) throws Exception {
        // findAll の候補一覧に混ざっていても、他の正常なファイルの読み込みは妨げないこと
        JsonExecutionStore store = new JsonExecutionStore(dir);
        store.save(sampleRun("good", Instant.now()));
        byte[] filler = new byte[(int) JsonExecutionStore.MAX_RECORD_BYTES + 1];
        java.util.Arrays.fill(filler, (byte) ' ');
        Files.write(dir.resolve("huge.json"), filler);

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
    void constructorDoesNotCreateBaseDir(@TempDir Path dir) {
        // 読み取り専用の list コマンドが副作用でディレクトリを作らないよう、
        // コンストラクタはベースディレクトリを作成しないこと
        Path nested = dir.resolve("nested/store");
        assertFalse(Files.exists(nested));
        new JsonExecutionStore(nested);
        assertFalse(Files.exists(nested));
    }

    @Test
    void saveCreatesBaseDirIfAbsent(@TempDir Path dir) {
        // 書き込み時（save）には保存先ディレクトリが自動的に作成されること
        Path nested = dir.resolve("nested/store");
        JsonExecutionStore store = new JsonExecutionStore(nested);
        store.save(sampleRun("r1", Instant.parse("2024-01-01T00:00:00Z")));
        assertTrue(Files.isDirectory(nested));
        assertTrue(Files.isRegularFile(nested.resolve("r1.json")));
    }

    @Test
    void ensureBaseDirectoryCreatesDirAndFailsOnFileCollision(@TempDir Path dir) throws IOException {
        // ensureBaseDirectory は保存先を作成し、既存の通常ファイルと衝突する場合は
        // fail fast で UncheckedIOException を投げること（run コマンドの早期検証用）
        Path nested = dir.resolve("nested/store");
        new JsonExecutionStore(nested).ensureBaseDirectory();
        assertTrue(Files.isDirectory(nested));

        Path collision = dir.resolve("collision");
        Files.writeString(collision, "not a directory");
        JsonExecutionStore broken = new JsonExecutionStore(collision);
        assertThrows(UncheckedIOException.class, broken::ensureBaseDirectory);
    }

    @Test
    void ensureBaseDirectoryRejectsSymlinkedBaseDir(@TempDir Path dir) throws IOException {
        // baseDir 自体がシンボリックリンクの場合、たとえリンク先が正当なディレクトリでも
        // createDirectories でリンクをそのまま辿らせず、fail-closed で拒否すること（CWE-59 対策）
        Path realTarget = dir.resolve("real-target");
        Files.createDirectory(realTarget);
        Path link = dir.resolve("linked-store");
        Files.createSymbolicLink(link, realTarget);

        JsonExecutionStore store = new JsonExecutionStore(link);
        assertThrows(UncheckedIOException.class, store::ensureBaseDirectory);
    }

    @Test
    void saveRejectsSymlinkedBaseDirAndDoesNotWriteIntoLinkTarget(@TempDir Path dir) throws IOException {
        // save() の公開エントリポイントとしての回帰テスト。ensureBaseDirectory() 単体のテスト
        // (ensureBaseDirectoryRejectsSymlinkedBaseDir) はこの内部ヘルパーだけを直接検証しており、
        // save() が実際にそれを呼び出しているか・書き込み直前の再チェックまで含めて拒否できているか
        // は別途検証していなかった。baseDir がシンボリックリンクの場合、save() 自体が拒否し、
        // かつリンク先の実ディレクトリには一切ファイルが書き込まれない（＝リンクを辿った先への
        // 誤書き込みが起きていない）ことまで確認する
        Path realTarget = dir.resolve("real-target");
        Files.createDirectory(realTarget);
        Path link = dir.resolve("linked-store");
        Files.createSymbolicLink(link, realTarget);

        JsonExecutionStore store = new JsonExecutionStore(link);
        assertThrows(UncheckedIOException.class,
                () -> store.save(sampleRun("run1", Instant.now().truncatedTo(ChronoUnit.MILLIS))));
        // リンク先の実ディレクトリが空のままであること（書き込みが素通りしていないこと）を確認する
        try (var files = Files.list(realTarget)) {
            assertTrue(files.findAny().isEmpty());
        }
    }

    @Test
    void findAllAndFindRecentTreatSymlinkedBaseDirAsMissing(@TempDir Path dir) throws IOException {
        // 読み取り系（findAll/findRecent）も同じシンボリックリンクを辿らず、
        // baseDir が存在しない場合と同様に空リストを返すこと（書き込み経路だけでなく
        // 読み取り経路でも攻撃者が差し替えたリンク先の内容を読ませない）
        JsonExecutionStore store = newStoreWithHiddenRunBehindSymlink(dir);
        assertTrue(store.findAll().isEmpty());
        assertTrue(store.findRecent(10).isEmpty());
    }

    @Test
    void findByIdTreatsSymlinkedBaseDirAsMissing(@TempDir Path dir) throws IOException {
        // findById も findAll/findRecent と同じシンボリックリンク対策を持つこと（regression）。
        // Files.isRegularFile(file, NOFOLLOW_LINKS) は解決済みパスの「末尾コンポーネント」が
        // リンクでないことしか保証しないため、baseDir 自体がリンクだと親ディレクトリの
        // 中間コンポーネントとして素通りに辿られ、リンク先に置かれた <runId>.json を
        // そのまま読めてしまっていた（このテストが直すバグ）
        JsonExecutionStore store = newStoreWithHiddenRunBehindSymlink(dir);
        assertTrue(store.findById("hidden").isEmpty());
    }

    /**
     * {@code dir} 配下に実行結果を 1 件保存した実ディレクトリを作り、それを指す
     * シンボリックリンク経由の {@link JsonExecutionStore} を返す共通セットアップ
     * （findAll/findRecent/findById の 3 つのシンボリックリンク回帰テストで重複していた
     * 手順を 1 箇所にまとめたもの。§6 DRY: 2〜3 箇所目で重複したら共通化する）。
     * 保存した実行結果の runId は常に {@code "hidden"} で、各テストはそれが
     * リンク経由では「見えない」ことを検証する。
     */
    private static JsonExecutionStore newStoreWithHiddenRunBehindSymlink(Path dir) throws IOException {
        // シンボリックリンクの先となる実ディレクトリを作成する
        Path realTarget = dir.resolve("real-target");
        Files.createDirectory(realTarget);
        // リンク先には実行結果ファイルを置いておき、それが「見えない」ことを確認する
        new JsonExecutionStore(realTarget).save(sampleRun("hidden", Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        // 実ディレクトリを指すシンボリックリンクを作成する
        Path link = dir.resolve("linked-store");
        Files.createSymbolicLink(link, realTarget);
        // シンボリックリンクの方を baseDir として使うストアを返す
        return new JsonExecutionStore(link);
    }

    @Test
    void findAllAndFindRecentReturnEmptyWithoutCreatingMissingDir(@TempDir Path dir) {
        // ディレクトリ未存在でも読み取り系は空を返し、副作用で作成しないこと
        // （読み取り専用の場所でも list コマンドが動作するための前提）
        Path nested = dir.resolve("nested/store");
        JsonExecutionStore store = new JsonExecutionStore(nested);
        assertTrue(store.findAll().isEmpty());
        assertTrue(store.findRecent(10).isEmpty());
        assertFalse(Files.exists(nested));
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
    void findRecentClampsLimitAboveUnboundedSafetyCeiling(@TempDir Path dir) {
        // MAX_UNBOUNDED_RESULTS件そのものを実ファイルで作って超過させるのはテスト実行時間の
        // 観点で非現実的なため（findAllReturnsAllWhenUnderTheSafetyCeilingと同じ理由）、
        // ここでは「安全上限を超えるlimitを渡してもクラッシュせず、実際に保存された分だけが
        // そのまま返る」ことを確認する回帰テストに留める。クランプ後の切り詰めロジック自体は
        // keepMostRecentByFilenameTruncatesToNewestByLexicographicOrderで純粋ロジックとして
        // カバー済み。
        JsonExecutionStore store = new JsonExecutionStore(dir);
        store.save(sampleRun("only", Instant.parse("2024-01-01T00:00:00Z")));

        // 安全上限(100,000)を超えるlimitを渡しても、内部でクランプされ正常に動作する
        assertEquals(1, store.findRecent(JsonExecutionStore.MAX_UNBOUNDED_RESULTS + 1).size());
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

    @Test
    void keepMostRecentByFilenameReturnsAllWhenUnderOrAtCeiling() {
        // ちょうど ceiling 件、および ceiling 未満の件数はソートせずそのまま返る（境界値）
        List<Path> two = List.of(Path.of("b.json"), Path.of("a.json"));
        assertEquals(two, JsonExecutionStore.keepMostRecentByFilename(two, 2));
        assertEquals(two, JsonExecutionStore.keepMostRecentByFilename(two, 10));
    }

    @Test
    void keepMostRecentByFilenameTruncatesToNewestByLexicographicOrder() {
        // ファイル I/O なしで、100,000 件規模でも一瞬で検証できる純粋ロジックのテスト。
        // runId は "yyyyMMdd-HHmmss-XXXXXX" 形式のため辞書順=時系列順になる前提を模して、
        // ゼロ埋め連番のファイル名で新しい順（大きい番号が先頭）に切り詰められることを確認する
        List<Path> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add(Path.of(String.format("run-%02d.json", i)));
        }

        List<Path> kept = JsonExecutionStore.keepMostRecentByFilename(candidates, 3);

        assertEquals(
                List.of(Path.of("run-09.json"), Path.of("run-08.json"), Path.of("run-07.json")),
                kept);
    }

    @Test
    void findAllReturnsAllWhenUnderTheSafetyCeiling(@TempDir Path dir) {
        // MAX_UNBOUNDED_RESULTS 件そのものを実ファイルで作って超過させるのはテスト実行時間の
        // 観点で非現実的なため、切り詰めロジック自体は keepMostRecentByFilename の単体テストで
        // 純粋ロジックとしてカバーする。ここでは findAll() が通常時（上限未満）に全件をそのまま
        // 返す既存契約を壊していないことだけを回帰確認する。
        JsonExecutionStore store = new JsonExecutionStore(dir);
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            store.save(sampleRun("run" + i, base.plus(i, ChronoUnit.DAYS)));
        }

        assertEquals(5, store.findAll().size());
    }
}
