package io.github.izumacha.batch.state;

import io.github.izumacha.batch.model.ExecutionResult;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers {@link Durability} and its wiring into {@link JsonExecutionStore}.
 *
 * <p>A successful {@code fsync} is invisible from inside the JVM, so these
 * tests observe the {@code FINE}/{@code WARNING} records {@link Durability}
 * emits rather than the syscall itself. That is enough to pin the two things
 * that would otherwise regress silently: that {@code save} performs every
 * durability step, and that a step that cannot run degrades to a warning
 * instead of failing the save.
 */
class DurabilityTest {

    // Durability が使うロガー（テスト中だけ FINE まで拾えるように設定を差し替える）
    private static final Logger DURABILITY_LOGGER = Logger.getLogger(Durability.class.getName());

    // 環境がディレクトリを同期できるかを調べるときに借りる段階。ディレクトリを対象に
    // する段階ならどれでもよく、判定で出たログと使った予算は直後に元へ戻すため、
    // どの検査の期待値にも影響しない
    private static final Durability.Step PROBE_STEP = Durability.Step.RECORD_RENAME;

    // 別ファイルストアの候補（アトミック移動を確実に失敗させるために使う）
    private static final List<Path> OTHER_FILE_STORE_CANDIDATES =
            List.of(Path.of("/dev/shm"), Path.of("/run/shm"));

    // このテストが取り付けたハンドラ（後片付けで取り外すために保持する）
    private Handler handler;
    // 取り付けたハンドラが記録したログの一覧
    private List<LogRecord> records;
    // 差し替える前のログレベル（後片付けで元に戻すために保持する）
    private Level originalLevel;
    // 差し替える前の親ロガーへの伝播設定（同上）
    private boolean originalUseParentHandlers;
    // テストごとに新しく作る同期担当（予算がインスタンス単位なので自然に独立する）
    private Durability durability;

    @BeforeEach
    void attachLogCapture() {
        // 記録先のリストを新しくして、テストごとに独立させる
        records = new ArrayList<>();
        // ログを受け取ってリストへ溜めるだけのハンドラを組み立てる
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                // 受け取ったログをそのままリストへ追加する
                records.add(record);
            }

            @Override
            public void flush() {
                // 溜め込むだけなので書き出す先は無い
            }

            @Override
            public void close() {
                // 解放すべき資源を持たない
            }
        };
        // FINE も含めてすべて受け取るようハンドラ側のしきい値を下げる
        handler.setLevel(Level.ALL);
        // 元のログレベルと伝播設定を控えておく（後片付けで戻すため）
        originalLevel = DURABILITY_LOGGER.getLevel();
        originalUseParentHandlers = DURABILITY_LOGGER.getUseParentHandlers();
        // ロガー側のしきい値も下げて FINE のログが捨てられないようにする
        DURABILITY_LOGGER.setLevel(Level.ALL);
        // 親ロガーへ流さないことで、想定した警告がテスト出力を汚すのを防ぐ
        DURABILITY_LOGGER.setUseParentHandlers(false);
        // 組み立てたハンドラを取り付ける
        DURABILITY_LOGGER.addHandler(handler);
        // 予算はインスタンスが持つので、新しく作るだけでテストごとに独立する
        durability = new Durability();
    }

    @AfterEach
    void detachLogCapture() {
        // 取り付けたハンドラを外す
        DURABILITY_LOGGER.removeHandler(handler);
        // ログレベルと伝播設定を元に戻し、他のテストへ影響を残さない
        DURABILITY_LOGGER.setLevel(originalLevel);
        DURABILITY_LOGGER.setUseParentHandlers(originalUseParentHandlers);
    }

    /** 完了した段階を、記録された順番どおりに取り出す。 */
    private List<Durability.Step> completedSteps() {
        // 見つけた段階を記録順に積んでいく入れ物を用意する
        List<Durability.Step> steps = new ArrayList<>();
        // 捕まえたログを古い順に 1 件ずつ調べる
        for (LogRecord record : records) {
            // 完了ログ以外（警告など）は数えない
            if (!record.getMessage().contains("completed")) {
                continue;
            }
            // どの段階の完了ログかを判定する。段階名だけの部分一致にしないのは、
            // ある段階名が別の段階名を丸ごと含みうるため（PUBLISHED_RECORD_CONTENT は
            // RECORD_CONTENT を含む）。前後の決まり文句ごと照合すれば取り違えない
            for (Durability.Step step : Durability.Step.values()) {
                // 「Durability step <名前> completed」という形で一致するかを見る
                if (record.getMessage().contains("Durability step " + step.name() + " completed")) {
                    // 見つけた段階を記録順のまま積む
                    steps.add(step);
                    // 1 件のログが指す段階は 1 つだけなので、残りの候補は見ない
                    break;
                }
            }
        }
        // 記録された順番のまま返す
        return steps;
    }

    /** 完了ログに書かれた「実際に同期した対象のパス」を、記録された順番どおりに取り出す。 */
    private List<Path> syncedPaths() {
        // 見つけたパスを記録順に積んでいく入れ物を用意する
        List<Path> paths = new ArrayList<>();
        // 捕まえたログを古い順に 1 件ずつ調べる
        for (LogRecord record : records) {
            // 完了ログ以外（警告など）は対象にしない
            if (!record.getMessage().contains(" completed for '")) {
                continue;
            }
            // 本文の末尾に「for '<パス>'」の形で入っているので、引用符の間を取り出す
            String message = record.getMessage();
            int start = message.indexOf(" completed for '") + " completed for '".length();
            int end = message.lastIndexOf('\'');
            // 取り出した文字列をパスとして積む
            paths.add(Path.of(message.substring(start, end)));
        }
        // 記録された順番のまま返す
        return paths;
    }

    /** WARNING レベルで記録されたログの本文だけを取り出す。 */
    private List<String> warnings() {
        // 警告の本文を記録順に集めるための入れ物を用意する
        List<String> messages = new ArrayList<>();
        // 捕まえたログを古い順に 1 件ずつ調べる
        for (LogRecord record : records) {
            // WARNING のものだけを対象にする（FINE の完了ログは数えない）
            if (record.getLevel() == Level.WARNING) {
                // 警告の本文をそのまま積む
                messages.add(record.getMessage());
            }
        }
        // 集めた警告本文を返す
        return messages;
    }

    /**
     * この環境でディレクトリを同期できるかどうかを実際に試して判定する。
     *
     * <p>ディレクトリをチャネルとして開けるのは POSIX だけで Windows では失敗する
     * （{@link Durability} の Javadoc 参照）。その差を定数や {@code os.name} で
     * 決め打ちにせず本物の操作で確かめるのは、CLAUDE.md §11 の「特定 OS でしか
     * 通らないテストを作らない」を守りつつ、判定の根拠を実挙動に置くため。
     */
    private boolean directorySyncSupported(Path probeDir) {
        // 本番の仕組みそのものを呼んで判定する。開き方をテスト側へ書き写すと、
        // 本番が変わったときに「もう試験対象ではないコードの可否」を報告しかねない。
        // 借りる段階は WARN 方針なので、この呼び出しが例外になることはない
        // 判定には使い捨てのインスタンスを使う。検査対象のインスタンスの予算を
        // 消費してしまうと、その段階の警告を後から観測できなくなるため
        try {
            new Durability().sync(probeDir, PROBE_STEP);
        } catch (IOException e) {
            // 到達しない（ディレクトリ対象の段階は保存を失敗させない）。届いたら未対応とみなす
            return false;
        }
        // 完了ログが出ていればこの環境でディレクトリを同期できたということ
        boolean supported = completedSteps().contains(PROBE_STEP);
        // 判定のために出したログは、この後の検査が数える対象から取り除く
        records.clear();
        // 判定結果を返す
        return supported;
    }

    /**
     * ディレクトリを同期できない環境では、期待する段階の並びからディレクトリ側の
     * 段階を落とす。落とした環境でも残りの段階と順序は変わらず検証できる。
     */
    private List<Durability.Step> expectedSteps(Path probeDir, Durability.Step... steps) {
        // 同期できる環境ではすべての段階がそのまま記録される
        if (directorySyncSupported(probeDir)) {
            return List.of(steps);
        }
        // 同期できない環境で残る段階だけを詰め直すための入れ物を用意する
        List<Durability.Step> reduced = new ArrayList<>();
        // 期待していた段階を順番どおりに 1 つずつ見る
        for (Durability.Step step : steps) {
            // 対象がファイルかどうかは段階自身が持っている。ここで名前を並べると、
            // 新しい段階を足したときに書き足し忘れても誰も気づけない（黙って期待値から
            // 消えるので、その段階の退行が緑のまま通る）ため、必ず enum 側から読む
            if (step.target() == Durability.Step.Target.FILE) {
                // ファイルを対象にする段階はどの環境でも成功するので残す
                reduced.add(step);
            }
        }
        // 変更されない一覧にして返す
        return List.copyOf(reduced);
    }

    /**
     * {@code sameStoreAs} とは別のファイルストア上にある、書き込み可能なディレクトリを
     * 返す（見つからなければ {@code null}）。
     *
     * <p>アトミック移動はファイルシステムをまたぐと
     * {@code AtomicMoveNotSupportedException} になる。フォールバック分岐を実物で
     * 踏める唯一の手段がこれなので、候補は定数に持たせて増やせるようにしてある
     * （§6 マジック文字列を散らさない）。候補が無い環境（多くの macOS / Windows）では
     * 呼び出し元が検査を飛ばす。
     */
    private static Path otherFileStoreDirectory(Path sameStoreAs) {
        // 別ストアになりやすい場所を順に試す（POSIX の共有メモリ領域は多くの Linux で tmpfs）
        for (Path candidate : OTHER_FILE_STORE_CANDIDATES) {
            try {
                // 書き込めるディレクトリでなければ使えない
                if (!Files.isDirectory(candidate) || !Files.isWritable(candidate)) {
                    continue;
                }
                // 同じストアだとアトミック移動が成功してしまうので、別ストアのものだけ返す
                if (!Files.getFileStore(candidate).equals(Files.getFileStore(sameStoreAs))) {
                    return candidate;
                }
            } catch (IOException e) {
                // ストアを調べられない候補は単に使わない（次の候補へ進む）
                continue;
            }
        }
        // どの候補も使えなかった
        return null;
    }

    /** テスト用の最小限の実行結果を組み立てる。 */
    private static ExecutionResult sampleRun(String runId) {
        // 開始時刻を固定する（内容はこのテストの関心事ではないので毎回同じでよい）
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        // 成功した 1 件のジョブ結果を組み立てる
        JobResult job = new JobResult("a", JobStatus.SUCCEEDED, 0, 1,
                start, start.plusSeconds(1), "exit 0");
        // そのジョブ 1 件だけを含む実行結果にして返す
        return new ExecutionResult(runId, "etl", JobStatus.SUCCEEDED,
                start, start.plusSeconds(1), List.of(job));
    }

    @Test
    void saveSyncsContentsThenRenameInThatOrder(@TempDir Path dir) {
        // 期待値は保存より前に組み立てる。判定そのものが本番の仕組みを呼ぶため、
        // 保存の後に呼ぶと、これから数えるログを判定用のログが汚してしまう
        List<Durability.Step> expected =
                expectedSteps(dir, Durability.Step.RECORD_CONTENT, Durability.Step.RECORD_RENAME);
        // 保存先ディレクトリは既に存在するので、記録されるのは中身と改名の 2 段階だけ
        JsonExecutionStore store = new JsonExecutionStore(dir);
        // 実行結果を 1 件保存する
        store.save(sampleRun("run1"));
        // 中身を確定させてから改名を確定させる、という順序どおりであることを確認する
        // （改名を先に確定させると、中身が空のまま記録が「存在する」状態になりうる）
        assertEquals(expected, completedSteps());
    }

    @Test
    void saveSyncsEachDirectoryLevelItCreatesBeforeTheRecord(@TempDir Path dir) {
        // 期待値を先に組み立てる（判定が出すログを数えないようにするため）
        List<Durability.Step> expected = expectedSteps(dir,
                Durability.Step.BASE_DIRECTORY,
                Durability.Step.BASE_DIRECTORY,
                Durability.Step.RECORD_CONTENT,
                Durability.Step.RECORD_RENAME);
        // まだ存在しない 2 階層下を保存先に指定する（作成が必要な状況を作る）
        Path nested = dir.resolve("a").resolve("b");
        JsonExecutionStore store = new JsonExecutionStore(nested);
        // 実行結果を保存する（保存先ディレクトリはこの中で作成される）
        store.save(sampleRun("run1"));
        // 作成した 2 階層ぶんの確定が先に来て、そのあとに記録の中身と改名が続く
        assertEquals(expected, completedSteps());
        // 保存そのものも成功していることを確認する（確定処理が保存を壊していない）
        assertTrue(store.findById("run1").isPresent());
    }

    @Test
    void createDirectoriesDurablySyncsEachCreatedLevelShallowestFirst(@TempDir Path dir) throws IOException {
        // ディレクトリを同期できない環境では確定のログ自体が出ないので飛ばす
        assumeTrue(directorySyncSupported(dir), "この環境ではディレクトリを同期できない");
        // 3 階層ぶん存在しないパスを用意する
        Path deep = dir.resolve("x").resolve("y").resolve("z");
        // ディレクトリを作成する
        durability.createDirectoriesDurably(deep);
        // 実際に確定させたディレクトリを、浅い方から深い方への順で確認する。
        // 「作成したと主張する一覧」ではなく「実際に同期した対象」を見ているので、
        // 同期先を取り違える退行もこの 1 件で捕まえられる
        assertEquals(List.of(dir, dir.resolve("x"), dir.resolve("x").resolve("y")), syncedPaths());
        // 実際にディレクトリができていることを確認する
        assertTrue(Files.isDirectory(deep));
    }

    @Test
    void createDirectoriesDurablySyncsNothingWhenDirectoryAlreadyExists(@TempDir Path dir) throws IOException {
        // 既に存在するディレクトリを渡す
        durability.createDirectoriesDurably(dir);
        // 何も作成していない以上、確定させるべき階層も無い
        assertEquals(List.of(), completedSteps());
    }

    @Test
    void directoryHoldingResolvesBareRelativePathAgainstWorkingDirectory() {
        // 既定の --state-dir と同じ形（単一要素の相対パス）を用意する
        Path bare = Path.of(".batch-state");
        // 素の getParent() は null になり「同期する相手がいない」と誤読されてしまう
        assertNull(bare.getParent());
        // このヘルパーはカレントディレクトリを踏まえて、含む側のディレクトリを返す
        assertEquals(Path.of("").toAbsolutePath(), Durability.directoryHolding(bare));
    }

    @Test
    void directoryHoldingReturnsNullForFilesystemRoot() {
        // ルートを含むディレクトリは存在しないので null を返す（同期対象なし）
        Path root = Path.of("").toAbsolutePath().getRoot();
        assertNull(Durability.directoryHolding(root));
    }

    @Test
    void publishRecordReportsWhetherItHadToFallBackToANonAtomicMove(@TempDir Path dir) throws IOException {
        // 同じファイルシステム内ならアトミック移動が使えるので false が返る
        Path sameFsTmp = dir.resolve("run-1.tmp");
        Files.writeString(sameFsTmp, "{}");
        assertFalse(JsonExecutionStore.publishRecord(sameFsTmp, dir.resolve("run1.json")));

        // 別のファイルシステムをまたぐとアトミック移動は使えず、フォールバックが走る。
        // save() 自身は同一ディレクトリ内で移動するためこの分岐を踏めないので、
        // 実際に踏める形（跨ぎ移動）でここだけ検証する
        Path otherFs = otherFileStoreDirectory(dir);
        assumeTrue(otherFs != null,
                "別のファイルストア上の書き込み可能なディレクトリが見つからないので"
                        + "アトミック移動の失敗を再現できない");
        Path crossFsTmp = Files.createTempFile(otherFs, "run-", ".tmp");
        try {
            Files.writeString(crossFsTmp, "{}");
            Path target = dir.resolve("run2.json");
            // フォールバックを使ったことが true として返る（save() はこれを見て再同期する）
            assertTrue(JsonExecutionStore.publishRecord(crossFsTmp, target));
            // 移動そのものは成功しており、中身も移っている
            assertEquals("{}", Files.readString(target));
        } finally {
            // 跨ぎ先に一時ファイルが残らないよう片付ける
            Files.deleteIfExists(crossFsTmp);
        }
    }

    @Test
    void commitPublishedRecordReSyncsOnlyWhenTheMoveWasNotAtomic(@TempDir Path dir) throws IOException {
        // 期待値は観測の前に組み立てる（判定そのものが本番の仕組みを呼んでログを出すため）
        List<Durability.Step> afterAtomic = expectedSteps(dir, Durability.Step.RECORD_RENAME);
        List<Durability.Step> afterFallback = expectedSteps(dir,
                Durability.Step.PUBLISHED_RECORD_CONTENT,
                Durability.Step.RECORD_RENAME);
        // 公開済みの記録に見立てたファイルを用意する
        Path target = dir.resolve("run1.json");
        Files.writeString(target, "{}");
        JsonExecutionStore store = new JsonExecutionStore(dir);

        // アトミック移動できた場合は、移動先は一時ファイルと同じ実体なので再同期しない。
        // 確定するのは改名だけ
        store.commitPublishedRecord(target, dir, false, true);
        assertEquals(afterAtomic, completedSteps());

        // 記録を消して次の観測に備える
        records.clear();
        // コピー→削除で公開された場合は、移動先を同期し直してから改名を確定させる。
        // ここが繋がっていないと、アトミック移動を持たない配備でだけ
        // 「エントリは確定・中身は未確定」の記録が公開される
        store.commitPublishedRecord(target, dir, true, true);
        assertEquals(afterFallback, completedSteps());
    }

    @Test
    void saveLeavesNoTemporaryFileBehindWhenPublishingFails(@TempDir Path dir) throws IOException {
        // 記録の名前でディレクトリを作り、そこへの移動が必ず失敗する状況を用意する
        // （移動は一時ファイルの作成と書き込みの後に来るので、後始末の経路を確実に通る）
        Path blocked = dir.resolve("run1.json");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("occupant"), "x");
        JsonExecutionStore store = new JsonExecutionStore(dir);

        // 保存は失敗する
        assertThrows(RuntimeException.class, () -> store.save(sampleRun("run1")));

        // 一時ファイルが残っていないことを確かめる。ここが崩れると、失敗のたびに
        // run-*.tmp が状態ディレクトリへ積み上がり、誰も掃除も報告もしない
        try (var entries = Files.list(dir)) {
            assertEquals(List.of(), entries
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".tmp"))
                    .toList());
        }
    }

    @Test
    void commitPublishedRecordReFlushesThroughTheVerifiedBaseNotTheOriginalPath(@TempDir Path dir)
            throws IOException {
        // ディレクトリを同期できない環境では完了ログが出ないので飛ばす
        assumeTrue(directorySyncSupported(dir), "この環境ではディレクトリを同期できない");
        // 検証済みの実体ディレクトリと、そこに実在する記録を用意する
        Path verifiedBase = Files.createDirectories(dir.resolve("real"));
        Path published = verifiedBase.resolve("run1.json");
        Files.writeString(published, "{}");
        // 呼び出し元が持っている target は baseDir 経由の経路（＝もう一度たどると
        // 差し替え後のディレクトリを掴みうる）を模して、別の場所を指させる
        Path viaBaseDir = dir.resolve("other").resolve("run1.json");

        // 再同期は検証済みの実体パスから組み立て直した先に対して行われる
        new JsonExecutionStore(dir).commitPublishedRecord(viaBaseDir, verifiedBase, true, true);

        // 実在する published が同期されている（存在しない viaBaseDir を開こうとしていない）
        assertEquals(List.of(published, verifiedBase), syncedPaths());
        // 開けない経路を掴んでいれば警告が出るはずなので、出ていないことも確かめる
        assertEquals(List.of(), warnings());
    }

    @Test
    void everyFileStepFailsTheSaveAndEveryDirectoryStepOnlyWarns() {
        // まず、どの段階が何を対象にしているかを 1 つずつ書き下して固定する。
        // 実装と同じ式（target == FILE）から期待値を導くと、分類を取り違えても
        // 両辺が一緒に動いて検査が素通りしてしまうため、ここは必ず直接書く
        assertEquals(Durability.Step.Target.FILE, Durability.Step.RECORD_CONTENT.target());
        assertEquals(Durability.Step.Target.FILE,
                Durability.Step.PUBLISHED_RECORD_CONTENT.target());
        assertEquals(Durability.Step.Target.DIRECTORY, Durability.Step.BASE_DIRECTORY.target());
        assertEquals(Durability.Step.Target.DIRECTORY, Durability.Step.RECORD_RENAME.target());
        // 記録のバイト列を対象にする段階は、失敗したら保存を失敗させる。
        // ここが緩むと、fsync が「書けていない」と言った後でも成功と報告してしまう
        assertTrue(Durability.Step.RECORD_CONTENT.failureFailsTheSave());
        assertTrue(Durability.Step.PUBLISHED_RECORD_CONTENT.failureFailsTheSave());
        // ディレクトリを対象にする段階は、環境によっては実行できないので警告に留める
        assertFalse(Durability.Step.BASE_DIRECTORY.failureFailsTheSave());
        assertFalse(Durability.Step.RECORD_RENAME.failureFailsTheSave());
        // 段階を足したときに上の書き下しが古くなっていないことも確かめる
        assertEquals(4, Durability.Step.values().length,
                "段階を増減したら、この検査の書き下しも合わせて更新すること");
    }

    @Test
    void directoryFailureWordingSeparatesAPlatformGapFromARealError() {
        // 書き戻しまで到達していない場合は、環境差の可能性と本物の問題の可能性を両方示す。
        // どちらかを断定しないのは、Windows の「そもそも開けない」も POSIX の
        // AccessDenied も同じ IOException として届き、型からは区別できないため
        String openFailed = Durability.describeFailure(Durability.Step.RECORD_RENAME, false);
        assertTrue(openFailed.contains("never possible on platforms such as Windows"), openFailed);
        assertTrue(openFailed.contains("real problem"), openFailed);
        // 開けたうえで同期に失敗した場合は、環境差ではなく本物のエラーだと言い切る。
        // ここを取り違えると、警告は段階ごとに 1 回きりなので、記録が確定していない
        // という唯一の合図を「この環境ではよくあること」として読み飛ばさせてしまう
        assertTrue(Durability.describeFailure(Durability.Step.RECORD_RENAME, true)
                .contains("real error"));
        // 通常ファイルには環境差の言い訳が無い。到達したかどうかに関わらず同じ文面で、
        // 「この環境の制約」と読める言い回しは決して使わない
        assertTrue(Durability.describeFailure(Durability.Step.PUBLISHED_RECORD_CONTENT, true)
                .contains("could not sync the file"));
        assertTrue(Durability.describeFailure(Durability.Step.RECORD_CONTENT, false)
                .contains("could not sync the file"));
    }

    @Test
    void saveStillSucceedsWhenTheDirectorySyncCannotRun(@TempDir Path dir) throws IOException {
        // 保存先を用意し、読み取り権限だけ落として「開けないが書ける」状態にする。
        // これでディレクトリの同期だけが失敗し、一時ファイルの作成と改名は通る
        Files.createDirectories(dir);
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class),
                "この環境では POSIX 権限を操作できない");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("-wx------"));
        try {
            // 権限を落としても root は素通りするため、狙いどおり同期が失敗するかを先に確かめる。
            // 失敗させられない環境でも検査自体は飛ばさない（保存が通ることの確認は
            // どの環境でも意味があり、skip にすると配線の検証が丸ごと消えてしまう）
            boolean syncActuallyFails = !directorySyncSupported(dir);
            JsonExecutionStore store = new JsonExecutionStore(dir);
            // 同期が失敗しても保存そのものは例外にならない。ここが崩れると、記録が
            // ディスクに載っている実行に対して RunCommand が「保存に失敗しました」と
            // 報告し、終了コードまで変わってしまう
            assertDoesNotThrow(() -> store.save(sampleRun("run1")));
            // 記録は実際に読み戻せる（＝保存は成立している）
            assertTrue(store.findById("run1").isPresent());
            // 同期を実際に失敗させられた環境でだけ、握り潰しではなく警告が残ることも確かめる。
            // root で走る CI ではここが素通りするため、「失敗しても警告は必ず残る」ことは
            // uid に依存しない aDirectorySyncFailureWarnsInsteadOfFailingTheSave が別途固定する
            if (syncActuallyFails) {
                assertTrue(warnings().stream().anyMatch(w -> w.contains("RECORD_RENAME")),
                        warnings().toString());
            }
        } finally {
            // 後片付け（@TempDir が消せるよう権限を戻す）
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aDirectorySyncFailureWarnsInsteadOfFailingTheSave(@TempDir Path dir) {
        // 存在しないディレクトリを渡すと、権限の細工なしに open が必ず失敗する。
        // POSIX 権限を落とす手は root（CI コンテナの既定）だと素通りしてしまい、
        // 「警告が残る」ことの検証が黙って実行されなくなるため、uid に依存しない
        // この形で固定する
        Path missing = dir.resolve("gone").resolve("deeper");
        // ディレクトリの段階は失敗しても保存を止めない（best-effort）
        assertDoesNotThrow(() -> durability.sync(missing, Durability.Step.RECORD_RENAME));
        // 握り潰しではなく、段階名つきの警告として必ず残る
        assertEquals(1, warnings().size(), warnings().toString());
        assertTrue(warnings().get(0).contains("RECORD_RENAME"), warnings().get(0));
        // 完了ログは出ない（＝確定していないのに確定したと記録しない）
        assertEquals(List.of(), completedSteps());
    }

    @Test
    void aFifoInTheRecordsPlaceIsRefusedInsteadOfBlockingForever(@TempDir Path dir)
            throws IOException, InterruptedException {
        // 記録の名前で名前付きパイプ（FIFO）を作る。同居プロセスが state ディレクトリへ
        // 置いた場合の再現で、open(2) は相手が現れるまで無期限にブロックする
        Path fifo = dir.resolve("run1.json");
        Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString())
                .redirectErrorStream(true).start();
        // mkfifo が使えない環境（Windows など）ではこの検査を飛ばす
        assumeTrue(mkfifo.waitFor() == 0, "mkfifo が使えないので FIFO を用意できない");
        // 通常ファイルでないと分かった時点で開かずに拒否するので、ここで固まらない。
        // 固まればテストはタイムアウトで落ちる（＝ブロックの回帰を検出できる）
        assertDoesNotThrow(() -> durability.sync(fifo, Durability.Step.RECORD_CONTENT));
        // 握り潰しではなく、理由の分かる警告として残る
        assertEquals(1, warnings().size(), warnings().toString());
        assertTrue(warnings().get(0).contains("pipe, socket, or device"), warnings().get(0));
        // 通常ファイルとして扱えないことも分かる文面になっている
        assertTrue(warnings().get(0).contains("block"), warnings().get(0));
        // 同期していないので完了ログは出ない
        assertEquals(List.of(), completedSteps());
    }

    @Test
    void theRenameIsNotCommittedWhenTheContentsWereNeverConfirmed(@TempDir Path dir)
            throws IOException {
        // ディレクトリを同期できる環境でだけ意味のある検査（できない環境では
        // そもそも RECORD_RENAME の完了ログが出ないため、差が観測できない）
        assumeTrue(directorySyncSupported(dir), "この環境ではディレクトリを同期できない");
        Path target = dir.resolve("run1.json");
        Files.writeString(target, "{}");
        // 中身を確定できた場合は、これまでどおり改名を確定させる
        JsonExecutionStore store = new JsonExecutionStore(dir);
        store.commitPublishedRecord(target, dir, false, true);
        assertEquals(List.of(Durability.Step.RECORD_RENAME), completedSteps());

        // 記録を取り直して、確定できなかった場合を試す
        records.clear();
        // 中身が未確定なら改名も確定させない。ここで改名だけ確定させると
        // 「エントリは耐久・中身は非耐久」という最悪の組み合わせを自分で作ってしまう
        new JsonExecutionStore(dir).commitPublishedRecord(target, dir, false, false);
        assertEquals(List.of(), completedSteps());
    }

    @Test
    void aFallbackPublishThatCannotBeConfirmedDoesNotCommitTheRename(@TempDir Path dir)
            throws IOException, InterruptedException {
        // 公開された記録の位置に FIFO を置き、その中身の同期を確実に失敗させる
        Path published = dir.resolve("run1.json");
        Process mkfifo = new ProcessBuilder("mkfifo", published.toString())
                .redirectErrorStream(true).start();
        assumeTrue(mkfifo.waitFor() == 0, "mkfifo が使えないので FIFO を用意できない");
        // ディレクトリの同期ができる環境でだけ「改名を確定させていない」ことを観測できる
        assumeTrue(directorySyncSupported(dir), "この環境ではディレクトリを同期できない");
        // 非アトミック移動で公開した体で呼ぶ。一時ファイル側は確定できていた（true）と
        // しても、この経路の公開先は別の inode なので、それを引き継いではいけない
        new JsonExecutionStore(dir).commitPublishedRecord(published, dir, true, true);
        // 公開先の中身が未確定なので改名も確定させない（＝完了ログが 1 つも出ない）。
        // ここで RECORD_RENAME が出ると「エントリは耐久・中身は非耐久」を作ってしまう
        assertEquals(List.of(), completedSteps());
    }

    @Test
    void aFallbackPublishWhoseFlushFailsStillReportsTheFailure() {
        // procfs のファイルは通常ファイルとして開けるが fsync は EINVAL で失敗する。
        // 「開けたのに書き戻しに失敗した」＝保存を失敗させるべき側の失敗を、モックを
        // 使わず実物で再現できる数少ない経路（存在しないファイルや FIFO では open の
        // 段階で落ちてしまい、警告どまりになってこの分岐を踏めない）
        Path published = Path.of("/proc/self/comm");
        assumeTrue(Files.isRegularFile(published), "procfs が無いので書き戻しの失敗を再現できない");
        // 公開先はベース + ファイル名で組み立てられるので、そうなる形で渡す
        JsonExecutionStore store = new JsonExecutionStore(published.getParent());
        // 非アトミック公開の中身の同期が失敗したら、保存は失敗として報告される。
        // ここを握り潰すと、カーネルが書き込みエラーを報告した記録に対して
        // 「State saved to ...」と出して終了コード 0 を返してしまう
        IOException reported = assertThrows(IOException.class, () ->
                store.commitPublishedRecord(published, published.getParent(), true, true));
        // 文面は「公開はされたが耐久性を確認できなかった」で、原因も添えられている
        assertTrue(reported.getMessage().contains("could not be confirmed durable"),
                reported.getMessage());
        assertNotNull(reported.getCause());
        // 中身が未確定なので改名は確定させていない
        assertFalse(completedSteps().contains(Durability.Step.RECORD_RENAME),
                completedSteps().toString());
    }

    @Test
    void onlyAFlushFailureOnARecordContentStepFailsTheSave() {
        // 「書き戻しまで到達したか」の 2 通りを用意する（false=開けなかった / true=書き戻しで失敗）
        boolean openFailed = false;
        boolean flushFailed = true;
        // 記録のバイト列を対象にする 2 段階は、書き戻しの失敗なら保存を失敗させる。
        // ここを握り潰すと、fsync が ENOSPC を返してダーティページが捨てられた後でも
        // 改名まで進み、空の記録を公開して「保存できました」と報告してしまう
        assertTrue(Durability.shouldFailTheSave(Durability.Step.RECORD_CONTENT, flushFailed));
        assertTrue(Durability.shouldFailTheSave(
                Durability.Step.PUBLISHED_RECORD_CONTENT, flushFailed));
        // 同じ段階でも「開けなかった」だけなら保存は失敗させない。バイト列は既に
        // 書かれて閉じられており、開き直せないことは中身の良し悪しを何も語らない
        assertFalse(Durability.shouldFailTheSave(Durability.Step.RECORD_CONTENT, openFailed));
        assertFalse(Durability.shouldFailTheSave(
                Durability.Step.PUBLISHED_RECORD_CONTENT, openFailed));
        // ディレクトリを対象にする段階は、どちらの失敗でも保存を失敗させない
        assertFalse(Durability.shouldFailTheSave(Durability.Step.RECORD_RENAME, flushFailed));
        assertFalse(Durability.shouldFailTheSave(Durability.Step.BASE_DIRECTORY, openFailed));
    }

    @Test
    void aRecordContentStepThatCannotBeOpenedWarnsInsteadOfFailingTheSave(@TempDir Path dir) {
        // 存在しないファイルを渡すと開けずに失敗する（＝「試せなかった」側の失敗）
        assertDoesNotThrow(() ->
                durability.sync(dir.resolve("gone.tmp"), Durability.Step.RECORD_CONTENT));
        // 例外にはならないが、握り潰しでもなく警告として残る
        assertEquals(1, warnings().size());
        // 通常ファイルには環境差の言い訳をしない文面が使われる
        assertTrue(warnings().get(0).contains("could not sync the file"), warnings().get(0));
        // 原因は open が出した本物（NoSuchFile）で、種別チェックの自前の文面ではない。
        // 警告は段階につき 1 回きり＝運用者が受け取る唯一の通知なので、行方不明の
        // ファイルに対して「パイプを探せ」と言うとその 1 回を無駄にしてしまう
        assertTrue(warnings().get(0).contains("NoSuchFile"), warnings().get(0));
        assertFalse(warnings().get(0).contains("pipe, socket, or device"), warnings().get(0));
    }

    @Test
    void anUncheckedFailureWarnsWithTheSameWordingAsAnyOtherFileFailure() {
        // JDK 自身のランタイムイメージ（jrt:/）は読み取り専用のファイルシステムで、
        // 書き込みモードで開こうとすると検査例外ではない UnsupportedOperationException に
        // なる。既定以外のプロバイダが非検査例外を投げる状況を、モックを使わず実物で
        // 再現できる唯一の経路。
        // 固定しているのは「flush() が open 時の非検査例外も OpenFailure に包むので、
        // sync() から見れば検査例外と同じ扱いになり、保存を失敗させず警告に留まる」こと。
        // sync() 側の catch (RuntimeException) はさらに内側（force/close が宣言に反して
        // 非検査例外を投げる場合）に備えた保険で、この経路では踏まない
        Path readOnly;
        try {
            FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            readOnly = jrt.getPath("/modules/java.base/java/lang/Object.class");
        } catch (RuntimeException e) {
            // ランタイムイメージを開けない環境ではこの経路を再現できないので飛ばす
            assumeTrue(false, "jrt ファイルシステムが使えないので非検査例外を再現できない");
            return;
        }
        // 対象が実在することを確かめてから試す（存在しないと別の失敗になってしまう）
        assumeTrue(Files.exists(readOnly), "ランタイムイメージ内の対象が見つからない");
        // 非検査例外は方針によらず警告どまりで、保存を失敗させない
        assertDoesNotThrow(() -> durability.sync(readOnly, Durability.Step.RECORD_CONTENT));
        // 文面は他のファイル失敗と同じ。ここで独自の文言を持つと、通常ファイルの失敗まで
        // 「この環境の制約」と読めてしまい、記録が確定していないという唯一の合図を
        // 読み飛ばさせる（describeFailure を通すことがその歯止めになっている）
        assertEquals(1, warnings().size());
        assertTrue(warnings().get(0).contains("could not sync the file"), warnings().get(0));
        assertFalse(warnings().get(0).contains("platform"), warnings().get(0));
    }

    @Test
    void aRecordContentStepRefusesToFollowASymlink(@TempDir Path dir) throws IOException {
        // state ディレクトリの外に、書き込まれては困るファイルを置く
        Path outside = dir.resolve("outside.txt");
        Files.writeString(outside, "untouched");
        // 記録の名前でそこへのシンボリックリンクを張る（同居プロセスによる差し替えの再現）
        Path link = dir.resolve("run1.json");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            // シンボリックリンクを作れない環境ではこの検査を飛ばす
            assumeTrue(false, "この環境ではシンボリックリンクを作成できない");
            return;
        }
        // リンクを追従せず開けないので、例外ではなく警告になる（best-effort の対象）
        assertDoesNotThrow(() -> durability.sync(link, Durability.Step.PUBLISHED_RECORD_CONTENT));
        // 同期できなかったことは警告として残る
        assertEquals(1, warnings().size());
        // 内部の目印クラス（OpenFailure）の名前ではなく、実際の原因が出ている。
        // 段階ごとに 1 回きりの警告に内部の型名を混ぜても運用者の役には立たない
        assertFalse(warnings().get(0).contains("OpenFailure"), warnings().get(0));
        // 完了ログは出ない＝リンク先を開いて fsync してはいない。ここが追従すると、
        // state ディレクトリの外のファイルを書き込みモードで開くことになる
        assertEquals(List.of(), completedSteps());
    }

    @Test
    void syncFailureWarnsInsteadOfThrowing(@TempDir Path dir) {
        // 存在しないディレクトリを指して、同期が必ず失敗する状況を作る
        Path missingDir = dir.resolve("gone-dir");
        // ディレクトリ対象の 2 段階は、失敗しても例外を投げずに戻る
        // （環境によっては実行できないため。保存の成功を失敗へ変えない契約）
        assertDoesNotThrow(() -> durability.sync(missingDir, Durability.Step.BASE_DIRECTORY));
        assertDoesNotThrow(() -> durability.sync(missingDir, Durability.Step.RECORD_RENAME));
        // 失敗は握り潰さず警告として残る
        assertEquals(2, warnings().size());
        // 警告には「省略すると何が起こりうるか」が含まれ、読んだ人が影響を判断できる
        assertTrue(warnings().get(0).contains("taking every record in it"), warnings().get(0));
        assertTrue(warnings().get(1).contains("vanish or roll back"), warnings().get(1));
    }

    @Test
    void warningBudgetIsPerStepAndSpentOnlyOnce(@TempDir Path dir) throws IOException {
        // 同じ段階で 2 回失敗させる（ディレクトリ対象なので例外にはならない）
        Path missing = dir.resolve("gone-dir");
        durability.sync(missing, Durability.Step.BASE_DIRECTORY);
        durability.sync(missing, Durability.Step.BASE_DIRECTORY);
        // 予算は 1 回きりなので、警告は 1 件しか出ない
        assertEquals(1, warnings().size());
        // 別の段階の予算は使われていないため、そちらは今からでも警告できる
        durability.sync(dir.resolve("gone-dir-2"), Durability.Step.RECORD_RENAME);
        // 段階ごとに独立していることを、2 件目が出ることで確認する
        // （予算を共有すると、先に失敗した段階が唯一の枠を使い切り、
        //   実際にデータが危うい段階の警告が二度と出なくなる）
        assertEquals(2, warnings().size());
    }

    @Test
    void syncSucceedsOnRealFileAndDirectory(@TempDir Path dir) throws IOException {
        // ディレクトリを同期できない環境では「警告ゼロ」を期待できないので飛ばす
        // （この検査の主題はディレクトリ同期が実際に成功することそのもの）
        assumeTrue(directorySyncSupported(dir), "この環境ではディレクトリを同期できない");
        // 実在するファイルを用意する
        Path file = dir.resolve("real.json");
        Files.writeString(file, "{}");
        // ファイルとディレクトリの両方を同期する
        durability.sync(file, Durability.Step.RECORD_CONTENT);
        durability.sync(dir, Durability.Step.RECORD_RENAME);
        // 正常系では警告が出ない（＝この環境では両方とも実際に同期できている）
        assertEquals(List.of(), warnings());
        // 完了ログが 2 件そろっていることを確認する
        assertEquals(List.of(Durability.Step.RECORD_CONTENT, Durability.Step.RECORD_RENAME),
                completedSteps());
    }
}
