package io.github.izumacha.batch.state;

import io.github.izumacha.batch.model.ExecutionResult;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
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
    void createDirectoriesDurablySyncsBareRelativeStateDirectory() throws IOException {
        // 既定の --state-dir ".batch-state" と同じ形（単一要素の相対パス）を、
        // 他のテストとぶつからない一意な名前で用意する
        Path relative = Path.of(".batch-state-test-" + UUID.randomUUID());
        // カレントディレクトリの絶対パス（＝同期されるべき相手）を控えておく
        Path workingDirectory = Path.of("").toAbsolutePath();
        // ディレクトリを同期できる環境かどうかを、作成先を含むカレントディレクトリで確かめる
        assumeTrue(directorySyncSupported(workingDirectory), "この環境ではディレクトリを同期できない");
        try {
            // 相対パスのまま作成する（本番の既定設定と同じ経路を通す）
            durability.createDirectoriesDurably(relative);
            // 単一要素の相対パスでも、含む側（カレントディレクトリ）が同期される。
            // ここが抜けると「既定設定のときだけ同期が丸ごと無くなる」状態になる
            assertEquals(List.of(workingDirectory), syncedPaths());
        } finally {
            // 作業ディレクトリに痕跡を残さないよう必ず片付ける
            Files.deleteIfExists(relative);
        }
    }

    @Test
    void directoryHoldingReturnsNullForFilesystemRoot() {
        // ルートを含むディレクトリは存在しないので null を返す（同期対象なし）
        Path root = Path.of("").toAbsolutePath().getRoot();
        assertNull(Durability.directoryHolding(root));
    }

    @Test
    void nonAtomicMoveFallbackSyncsTheDestinationNotJustTheTempFile(@TempDir Path dir) throws IOException {
        // 一時ファイルと移動先を用意する
        Path tmp = dir.resolve("run-1.tmp");
        Path target = dir.resolve("run1.json");
        Files.writeString(tmp, "{}");
        // アトミック移動が使えない環境で通る経路を直接呼ぶ
        JsonExecutionStore.moveWithoutAtomicity(tmp, target, durability);
        // 中身は移動先へ移っている
        assertEquals("{}", Files.readString(target));
        assertTrue(Files.notExists(tmp));
        // 移動「先」に対する同期が行われている。コピー→削除だった場合、同期済みの
        // 一時ファイルは消えて移動先は未同期のページになるため、ここを飛ばすと
        // 「エントリは確定・中身は未確定」という防ごうとしている状態を自分で作ってしまう。
        // 段階が RECORD_CONTENT と別なのは、警告予算を一時ファイル側と分けるため
        assertEquals(List.of(Durability.Step.PUBLISHED_RECORD_CONTENT), completedSteps());
    }

    @Test
    void everyFileStepFailsTheSaveAndEveryDirectoryStepOnlyWarns() {
        // 記録のバイト列を対象にする段階は、失敗したら保存を失敗させる。
        // ここが緩むと、fsync が「書けていない」と言った後でも成功と報告してしまう
        for (Durability.Step step : Durability.Step.values()) {
            // 対象がファイルかどうかで、期待する扱いが決まる
            boolean expected = step.target() == Durability.Step.Target.FILE;
            // 段階ごとに、規則どおりの扱いになっていることを確かめる
            assertEquals(expected, step.failureFailsTheSave(), step.name());
        }
        // 規則が空回りしないよう、両方の側に段階が実在することも確かめる
        // （全段階がディレクトリ対象になれば、上のループは何も検証しなくなる）
        assertTrue(Arrays.stream(Durability.Step.values())
                .anyMatch(Durability.Step::failureFailsTheSave));
        assertTrue(Arrays.stream(Durability.Step.values())
                .anyMatch(step -> !step.failureFailsTheSave()));
    }

    @Test
    void directoryFailureWordingSeparatesAPlatformGapFromARealError() {
        // 開けなかった場合は「この環境では開けない」という説明になる
        assertTrue(Durability.describeFailure(Durability.Step.RECORD_RENAME,
                        new Durability.OpenFailure(new IOException("boom")))
                .contains("does not allow this"));
        // 開けたうえで同期に失敗した場合は、環境差ではなく本物のエラーだと言い切る。
        // ここを取り違えると、警告は段階ごとに 1 回きりなので、記録が確定していない
        // という唯一の合図を「この環境ではよくあること」として読み飛ばさせてしまう
        assertTrue(Durability.describeFailure(Durability.Step.RECORD_RENAME, new IOException("boom"))
                .contains("real error"));
        // 通常ファイルには環境差の言い訳が無い（開けないこと自体が異常）
        assertTrue(Durability.describeFailure(Durability.Step.PUBLISHED_RECORD_CONTENT,
                new IOException("boom")).contains("could not sync the file"));
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
            // root は権限検査を素通りするため、狙いどおり失敗する環境でだけ検証する
            assumeFalse(directorySyncSupported(dir), "この環境ではディレクトリの同期を失敗させられない");
            JsonExecutionStore store = new JsonExecutionStore(dir);
            // 同期が失敗しても保存そのものは例外にならない。ここが崩れると、記録が
            // ディスクに載っている実行に対して RunCommand が「保存に失敗しました」と
            // 報告し、終了コードまで変わってしまう
            assertDoesNotThrow(() -> store.save(sampleRun("run1")));
            // 記録は実際に読み戻せる（＝保存は成立している）
            assertTrue(store.findById("run1").isPresent());
            // 握り潰したのではなく、警告としてきちんと痕跡が残っている
            assertTrue(warnings().stream().anyMatch(w -> w.contains("RECORD_RENAME")),
                    warnings().toString());
        } finally {
            // 後片付け（@TempDir が消せるよう権限を戻す）
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void bothRecordContentStepsPropagateTheirFailure(@TempDir Path dir) {
        // 一時ファイルの同期。ここを握り潰すと、fsync が ENOSPC を返して
        // ダーティページが捨てられた後でも改名まで進み、空の記録を公開してしまう
        assertThrows(IOException.class,
                () -> durability.sync(dir.resolve("gone.tmp"), Durability.Step.RECORD_CONTENT));
        // 非アトミック移動の移動先の同期も同じ。こちらはコピー→削除で移動先が
        // 新しく確保されるため、一時ファイル側の同期はこの経路の役に立っていない
        assertThrows(IOException.class,
                () -> durability.sync(dir.resolve("gone.json"),
                        Durability.Step.PUBLISHED_RECORD_CONTENT));
    }

    @Test
    void syncFailureWarnsInsteadOfThrowing(@TempDir Path dir) {
        // 存在しないファイルとディレクトリを指して、同期が必ず失敗する状況を作る
        Path missingFile = dir.resolve("gone.json");
        Path missingDir = dir.resolve("gone-dir");
        // ディレクトリ対象の 2 段階は、失敗しても例外を投げずに戻る
        // （環境によっては実行できないため。保存の成功を失敗へ変えない契約）
        assertDoesNotThrow(() -> durability.sync(missingDir, Durability.Step.BASE_DIRECTORY));
        assertDoesNotThrow(() -> durability.sync(missingDir, Durability.Step.RECORD_RENAME));
        // 失敗は握り潰さず警告として残る
        assertEquals(2, warnings().size());
        // 警告には「省略すると何が起こりうるか」が含まれ、読んだ人が影響を判断できる
        assertTrue(warnings().get(0).contains("taking every record inside it"), warnings().get(0));
        assertTrue(warnings().get(1).contains("vanish or roll back"), warnings().get(1));
        // 使っていない変数を残さないよう、ファイル側は別の検査で扱う
        assertTrue(Files.notExists(missingFile));
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
