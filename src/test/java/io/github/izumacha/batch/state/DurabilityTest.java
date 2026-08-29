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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // このテストが取り付けたハンドラ（後片付けで取り外すために保持する）
    private Handler handler;
    // 取り付けたハンドラが記録したログの一覧
    private List<LogRecord> records;
    // 差し替える前のログレベル（後片付けで元に戻すために保持する）
    private Level originalLevel;
    // 差し替える前の親ロガーへの伝播設定（同上）
    private boolean originalUseParentHandlers;

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
        // 「1 回だけ」の警告予算は JVM 全体で共有されるため、テストごとに未使用へ戻す
        Durability.resetWarningBudgetsForTest();
    }

    @AfterEach
    void detachLogCapture() {
        // 取り付けたハンドラを外す
        DURABILITY_LOGGER.removeHandler(handler);
        // ログレベルと伝播設定を元に戻し、他のテストへ影響を残さない
        DURABILITY_LOGGER.setLevel(originalLevel);
        DURABILITY_LOGGER.setUseParentHandlers(originalUseParentHandlers);
        // 予算も未使用へ戻して、後続テストが警告を観測できる状態にする
        Durability.resetWarningBudgetsForTest();
    }

    /** 完了した段階を、記録された順番どおりに取り出す。 */
    private List<Durability.Step> completedSteps() {
        // FINE で記録された「completed」のログだけを対象に、どの段階かを取り出す
        List<Durability.Step> steps = new ArrayList<>();
        for (LogRecord record : records) {
            // 完了ログ以外（警告など）は数えない
            if (!record.getMessage().contains("completed")) {
                continue;
            }
            // メッセージに名前が含まれている段階を特定して順番どおりに積む
            for (Durability.Step step : Durability.Step.values()) {
                if (record.getMessage().contains(step.name())) {
                    steps.add(step);
                }
            }
        }
        // 記録された順番のまま返す
        return steps;
    }

    /** WARNING レベルで記録されたログの本文だけを取り出す。 */
    private List<String> warnings() {
        // 警告レベルのログを本文の文字列として集める
        List<String> messages = new ArrayList<>();
        for (LogRecord record : records) {
            if (record.getLevel() == Level.WARNING) {
                messages.add(record.getMessage());
            }
        }
        // 集めた警告本文を返す
        return messages;
    }

    /** テスト用の最小限の実行結果を組み立てる。 */
    private static ExecutionResult sampleRun(String runId) {
        // 開始時刻を固定して結果を組み立てる（内容はこのテストの関心事ではない）
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        JobResult job = new JobResult("a", JobStatus.SUCCEEDED, 0, 1,
                start, start.plusSeconds(1), "exit 0");
        return new ExecutionResult(runId, "etl", JobStatus.SUCCEEDED,
                start, start.plusSeconds(1), List.of(job));
    }

    @Test
    void saveSyncsContentsThenRenameInThatOrder(@TempDir Path dir) {
        // 保存先ディレクトリは既に存在するので、記録されるのは中身と改名の 2 段階だけ
        JsonExecutionStore store = new JsonExecutionStore(dir);
        // 実行結果を 1 件保存する
        store.save(sampleRun("run1"));
        // 中身を確定させてから改名を確定させる、という順序どおりであることを確認する
        // （改名を先に確定させると、中身が空のまま記録が「存在する」状態になりうる）
        assertEquals(List.of(Durability.Step.RECORD_CONTENT, Durability.Step.RECORD_RENAME),
                completedSteps());
    }

    @Test
    void saveSyncsEachDirectoryLevelItCreatesBeforeTheRecord(@TempDir Path dir) {
        // まだ存在しない 2 階層下を保存先に指定する（作成が必要な状況を作る）
        Path nested = dir.resolve("a").resolve("b");
        JsonExecutionStore store = new JsonExecutionStore(nested);
        // 実行結果を保存する（保存先ディレクトリはこの中で作成される）
        store.save(sampleRun("run1"));
        // 作成した 2 階層ぶんの確定が先に来て、そのあとに記録の中身と改名が続く
        assertEquals(List.of(
                Durability.Step.BASE_DIRECTORY,
                Durability.Step.BASE_DIRECTORY,
                Durability.Step.RECORD_CONTENT,
                Durability.Step.RECORD_RENAME), completedSteps());
        // 保存そのものも成功していることを確認する（確定処理が保存を壊していない）
        assertTrue(store.findById("run1").isPresent());
    }

    @Test
    void createDirectoriesDurablyReportsCreatedLevelsShallowestFirst(@TempDir Path dir) throws IOException {
        // 3 階層ぶん存在しないパスを用意する
        Path deep = dir.resolve("x").resolve("y").resolve("z");
        // ディレクトリを作成し、作成した階層の一覧を受け取る
        List<Path> created = Durability.createDirectoriesDurably(deep);
        // 浅い方から深い方への順で返ることを確認する（親を先に確定させる順序）
        assertEquals(List.of(dir.resolve("x"), dir.resolve("x").resolve("y"), deep), created);
        // 実際にディレクトリができていることを確認する
        assertTrue(Files.isDirectory(deep));
    }

    @Test
    void createDirectoriesDurablyReportsNothingWhenDirectoryAlreadyExists(@TempDir Path dir) throws IOException {
        // 既に存在するディレクトリを渡す
        List<Path> created = Durability.createDirectoriesDurably(dir);
        // 何も作成していないので空が返る
        assertTrue(created.isEmpty());
        // 作成していない以上、確定させるべき階層も無い
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
    void syncFailureWarnsInsteadOfThrowing(@TempDir Path dir) {
        // 存在しないファイルとディレクトリを指して、同期が必ず失敗する状況を作る
        Path missingFile = dir.resolve("gone.json");
        Path missingDir = dir.resolve("gone-dir");
        // どちらも例外を投げずに戻ることを確認する（書き込み成功を失敗へ変えない契約）
        assertDoesNotThrow(() -> Durability.syncFile(missingFile, Durability.Step.RECORD_CONTENT));
        assertDoesNotThrow(() -> Durability.syncDirectory(missingDir, Durability.Step.RECORD_RENAME));
        // 失敗は握り潰さず警告として残る
        assertEquals(2, warnings().size());
        // 警告には「省略すると何が起こりうるか」が含まれ、読んだ人が影響を判断できる
        assertTrue(warnings().get(0).contains("empty or garbled"), warnings().get(0));
        assertTrue(warnings().get(1).contains("vanish or roll back"), warnings().get(1));
    }

    @Test
    void warningBudgetIsPerStepAndSpentOnlyOnce(@TempDir Path dir) {
        // 同じ段階で 2 回失敗させる
        Path missing = dir.resolve("gone.json");
        Durability.syncFile(missing, Durability.Step.RECORD_CONTENT);
        Durability.syncFile(missing, Durability.Step.RECORD_CONTENT);
        // 予算は 1 回きりなので、警告は 1 件しか出ない
        assertEquals(1, warnings().size());
        // 別の段階の予算は使われていないため、そちらは今からでも警告できる
        Durability.syncDirectory(dir.resolve("gone-dir"), Durability.Step.RECORD_RENAME);
        // 段階ごとに独立していることを、2 件目が出ることで確認する
        // （予算を共有すると、先に失敗した段階が唯一の枠を使い切り、
        //   実際にデータが危うい段階の警告が二度と出なくなる）
        assertEquals(2, warnings().size());
    }

    @Test
    void syncSucceedsOnRealFileAndDirectory(@TempDir Path dir) throws IOException {
        // 実在するファイルを用意する
        Path file = dir.resolve("real.json");
        Files.writeString(file, "{}");
        // ファイルとディレクトリの両方を同期する
        Durability.syncFile(file, Durability.Step.RECORD_CONTENT);
        Durability.syncDirectory(dir, Durability.Step.RECORD_RENAME);
        // 正常系では警告が出ない（＝この環境では両方とも実際に同期できている）
        assertEquals(List.of(), warnings());
        // 完了ログが 2 件そろっていることを確認する
        assertEquals(List.of(Durability.Step.RECORD_CONTENT, Durability.Step.RECORD_RENAME),
                completedSteps());
    }
}
