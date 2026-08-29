package io.github.izumacha.batch.state;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Flushes writes all the way to the storage device so a completed
 * {@link ExecutionStore#save} survives a power loss or kernel panic.
 *
 * <p>Writing a temp file and renaming it over the target (as
 * {@link JsonExecutionStore#save} does) makes the swap <em>atomic</em> --
 * a concurrent reader never sees a half-written file -- but atomicity is not
 * durability. Both the file's contents and the rename itself sit in the page
 * cache until the kernel decides to write them back, and a crash before that
 * write-back loses whichever part has not yet reached the disk. The two parts
 * are flushed independently, so a crash can lose either one alone:
 *
 * <ul>
 *   <li>Rename durable, contents not: {@code <runId>.json} exists but reads
 *       back empty or garbled. {@code JsonExecutionStore.tryRead} then skips
 *       it, so the run silently disappears from {@code list} and
 *       {@code run --rerun-failed <runId>} reports it as not found.</li>
 *   <li>Contents durable, rename not: the directory entry rolls back, so the
 *       record either vanishes or reverts to whatever previously held that
 *       name.</li>
 * </ul>
 *
 * <p>Losing a record matters beyond the missing history row: {@code run
 * --rerun-failed <runId>} reads it back to decide which jobs already
 * succeeded. Without the record the operator has to re-run the whole batch,
 * including jobs that had already succeeded -- which for a non-idempotent
 * job (an ETL load, a notification) is exactly the outcome
 * {@code --rerun-failed} exists to avoid.
 *
 * <p><b>Best-effort, never fatal.</b> Every method here logs and returns
 * instead of throwing. A sync failure arrives <em>after</em> the write itself
 * succeeded, so propagating it would turn a run whose record is on disk into
 * a reported "failed to save execution result", telling the operator the
 * opposite of what happened. Durability is a hardening measure layered on top
 * of a write that already worked; losing the hardening must not lose the
 * write.
 *
 * <p><b>Observability.</b> A successful {@code fsync} leaves no trace of its
 * own, so each completed step is logged at {@code FINE} -- the only way to
 * answer "how far did this save actually get committed?" after the fact, and
 * the seam the tests use to prove {@link JsonExecutionStore#save} really
 * performs every step. Failures are logged at {@code WARNING} instead, at
 * most once per step.
 *
 * <p><b>Platform limits.</b> Directory syncing needs to open a directory as a
 * channel, which POSIX allows and Windows does not; there the sync is skipped
 * and the rename's durability is left to the filesystem. macOS
 * {@code fsync(2)} likewise only pushes to the drive's own cache rather than
 * issuing {@code F_FULLFSYNC}, so a power loss can still lose data the drive
 * had acknowledged. Both gaps are inherent to the platform, not to this
 * class.
 */
final class Durability {

    // このクラス専用のロガー（同期に失敗したときの警告に使う）
    private static final Logger LOGGER = Logger.getLogger(Durability.class.getName());

    /**
     * A point in {@link JsonExecutionStore#save} that needs flushing, carrying
     * the consequence of skipping it.
     *
     * <p>The consequence text lives on the constant rather than in a separate
     * lookup table so a new step cannot be added without one: a table would
     * let the entry be forgotten, and the omission would only surface as a
     * blank or wrong warning at the moment something actually went wrong.
     *
     * <p>Each step owns a <em>separate</em> "warn once" budget. A single
     * shared budget would let the step that runs earliest and matters least
     * ({@link #BASE_DIRECTORY}, which runs on the very first save, before any
     * record exists to lose) spend the only slot, after which the step whose
     * failure actually costs data ({@link #RECORD_RENAME}) could never warn
     * again for the life of the process.
     */
    enum Step {
        // 保存先ディレクトリの作成を確定させる用途（省略すると初回保存でディレクトリごと消えうる）
        BASE_DIRECTORY("the state directory itself may not survive a power loss, "
                + "taking every record inside it"),
        // 一時ファイルの中身を確定させる用途（省略すると改名だけ残り中身が空・破損になりうる）
        RECORD_CONTENT("the record may come back empty or garbled after a power loss, "
                + "and will then be skipped as unreadable"),
        // 非アトミック移動の移動先を確定させ直す用途。RECORD_CONTENT と分けているのは
        // 予算を共有すると、これから捨てられる一時ファイル側の失敗が唯一の枠を使い切り、
        // 「公開済みの記録が未確定」という本当に危険な失敗を黙らせてしまうため
        // （段階ごとに予算を分ける理由そのものが、この 2 つの間にも当てはまる）
        PUBLISHED_RECORD_CONTENT("the record was published by a non-atomic move and its bytes "
                + "may never have reached the disk, so it can come back empty or garbled "
                + "after a power loss even though the directory entry survived"),
        // 改名（ディレクトリエントリ）を確定させる用途（省略すると保存そのものが巻き戻りうる）
        RECORD_RENAME("the record may vanish or roll back to its previous contents "
                + "after a power loss");

        // この段階を飛ばしたときに起こりうることの説明（警告文に埋め込む）
        private final String consequence;
        // この段階の警告をすでに 1 度出したかどうか（段階ごとに独立した予算）
        private final AtomicBoolean warned = new AtomicBoolean(false);

        Step(String consequence) {
            // 説明文をフィールドへ保存する
            this.consequence = consequence;
        }

        /**
         * Claims this step's one-shot warning budget, returning {@code true}
         * only for the first caller.
         */
        private boolean claimWarningBudget() {
            // まだ警告していなければ true を返して予算を消費する（2 回目以降は false）
            return warned.compareAndSet(false, true);
        }
    }

    private Durability() {
        // ユーティリティクラスなのでインスタンス化させない
    }

    /**
     * Flushes an already-written regular file's contents to disk.
     *
     * <p>Opening the file again rather than syncing the handle Jackson wrote
     * through keeps the serialization call untouched: Jackson closes the
     * stream it is handed, and a closed descriptor can no longer be synced.
     * Reopening costs one {@code open}/{@code close} pair and syncs exactly
     * the same file.
     */
    static void syncFile(Path file, Step step) {
        // 書き込みモードで開き直してから force(true) で中身とメタデータをディスクへ押し出す
        sync(file, StandardOpenOption.WRITE, step, false);
    }

    /**
     * Flushes a directory's entries -- creations, renames, deletions -- to
     * disk, so a rename into it cannot roll back.
     *
     * <p>Opening a directory as a channel is a POSIX capability that Windows
     * does not offer, so a failure here is treated as "this platform cannot
     * do it" rather than as an error: the open is attempted and any
     * {@link IOException} is reported at most once per step. This is the same
     * approach Apache Lucene takes in its {@code IOUtils.fsync}.
     */
    static void syncDirectory(Path dir, Step step) {
        // ディレクトリは書き込みモードで開けないため読み取りモードで開いて force(true) を呼ぶ
        sync(dir, StandardOpenOption.READ, step, true);
    }

    /**
     * Creates {@code dir} and any missing ancestors, then makes each newly
     * created level itself durable.
     *
     * <p>{@link Files#createDirectories} leaves the new directory entries in
     * the page cache like any other write, so without this a power loss right
     * after the first save can take the whole state directory -- and the
     * record just written into it -- even though that record's own contents
     * and rename were synced.
     *
     * <p>A directory entry is made durable by syncing the directory that
     * <em>contains</em> it, so the loop syncs each new level's parent,
     * shallowest first. The deepest level ({@code dir} itself) is deliberately
     * not synced here: it holds no entries yet, and {@link #syncDirectory} is
     * called on it after the record rename, which is when it first has
     * something to lose.
     *
     * <p>Package-private and returning the levels it created so a test can
     * assert which parents were synced without racing an actual power loss.
     *
     * @return the directories this call created, shallowest first, in the same
     *     relative-or-absolute form as {@code dir}; empty if {@code dir}
     *     already existed
     * @throws IOException if the directories could not be created
     */
    static List<Path> createDirectoriesDurably(Path dir) throws IOException {
        // 作成前の時点でまだ存在しない階層を、深い方から浅い方へたどって集める。
        // 渡されたパスの形（相対・絶対）はここでは変えない。相対パスの解決は
        // directoryHolding() 1 箇所に寄せてあり、ここでも絶対化すると
        // 正規化が 2 箇所に分かれて、どちらが効いているのか追えなくなる
        List<Path> missing = new ArrayList<>();
        for (Path p = dir; p != null && !Files.exists(p); p = p.getParent()) {
            // この階層はまだ無いので「これから作られる階層」として記録する
            missing.add(p);
        }
        // 実際にディレクトリ階層を作成する（既に存在する場合は何もしない）
        Files.createDirectories(dir);
        // 収集順は深い→浅いなので、浅い→深いの順（親が先に確定する順）へ反転する
        Collections.reverse(missing);
        // 呼び出し元へ返す一覧は変更不可にして、内部リストと共有されないようにする
        List<Path> created = List.copyOf(missing);
        // 新しく作られた各階層について、その階層を含むディレクトリを同期し存在を確定させる
        for (Path level : created) {
            // ファイルシステムのルートには含まれる側のディレクトリが無いので飛ばす
            Path container = directoryHolding(level);
            if (container != null) {
                // 含む側を同期して、その中の level というエントリを確定させる
                syncDirectory(container, Step.BASE_DIRECTORY);
            }
        }
        // どの階層を作成したかを呼び出し元（とテスト）へ返す
        return created;
    }

    /**
     * Returns the directory whose entries include {@code level} -- the one
     * that has to be synced to make {@code level}'s own existence durable --
     * or {@code null} for a filesystem root, which no directory contains.
     *
     * <p>Resolves against the working directory first, because a bare
     * relative path such as the default {@code .batch-state} has a
     * {@code null} parent even though it plainly sits inside some directory.
     * Reading that {@code null} as "nothing to sync" would silently disable
     * this step for exactly the default configuration.
     *
     * <p>Pure path arithmetic with no filesystem access, and package-private,
     * so the relative-path case can be pinned by a test without depending on
     * the process working directory (which Java cannot change).
     */
    static Path directoryHolding(Path level) {
        // 相対パスでも「どのディレクトリの中にあるか」を言えるよう先に絶対パスへ直す
        return level.toAbsolutePath().getParent();
    }

    /**
     * Opens {@code path} with {@code mode} and forces it to disk, reporting a
     * failure at most once per {@code step}.
     *
     * @param directory whether {@code path} is a directory, which decides how
     *     the failure is worded: for a directory a failure most likely means
     *     the platform does not allow opening one as a channel, whereas for a
     *     regular file it means the sync itself did not happen
     */
    private static void sync(Path path, StandardOpenOption mode, Step step, boolean directory) {
        // 対象を開いて force(true) でデータとメタデータの両方をディスクへ書き戻す
        try (FileChannel channel = FileChannel.open(path, mode)) {
            // true を渡すことで中身だけでなくメタデータの書き戻しも要求する
            channel.force(true);
            // 成功した段階を FINE で記録する。fsync は成功しても何の痕跡も残らないため、
            // 「この保存はどこまで確定したのか」を現場で追う手段がこれ以外に無い。
            // Supplier 版を使うので FINE が無効なときは文字列組み立て自体が起きない
            LOGGER.fine(() -> "Durability step " + step.name() + " completed for '" + path + "'");
        } catch (IOException | RuntimeException e) {
            // 非検査例外まで受けるのは、このクラスが「決して落とさない」と約束しているため。
            // FileChannel.open は UnsupportedOperationException（プロバイダが未対応の
            // オプション）や SecurityException も投げうる仕様で、IOException だけを
            // 捕まえていると save() の catch (IOException) も素通りして、記録が
            // ディスクに載っている実行に対して「保存に失敗しました」と正反対の報告に
            // なる。捕捉する例外の種類を見積もるのではなく、契約どおり全部受ける
            // 同期の失敗は書き込み自体の失敗ではないため、例外にせず警告だけ残す（段階ごとに 1 回）
            if (step.claimWarningBudget()) {
                // ディレクトリはこの環境で開けないだけの可能性が高く、通常ファイルとは意味が違う
                String cause = directory
                        ? "could not open the directory to sync it (this platform may not allow it)"
                        : "could not sync the file";
                // 何が確定できなかったのか、省略すると何が起こりうるのかをまとめて記録する
                LOGGER.warning("Durability step " + step.name() + " skipped for '" + path + "': "
                        + cause + " (" + e + "); " + step.consequence
                        + ". This warning is reported once per step.");
            }
        }
    }

    /**
     * Clears every step's one-shot warning budget.
     *
     * <p>The budgets live on the enum constants and are therefore per-JVM,
     * matching {@code JobRunner.KILL_UNAVAILABLE_LOGGED}, the existing
     * warn-once in this codebase. The cost of that choice is this method:
     * Surefire reuses forks, so a test that provokes a warning would otherwise
     * silence that step for every later test in the same fork.
     *
     * <p><b>Any test that asserts on a durability warning must call this
     * first</b> (see {@code DurabilityTest}'s {@code @BeforeEach}). A test that
     * forgets will observe zero warnings and pass without checking anything,
     * which is the one way this global state can hide a regression rather than
     * merely annoy.
     */
    static void resetWarningBudgetsForTest() {
        // すべての段階の「1 回だけ」予算を未使用の状態へ戻す
        for (Step step : Step.values()) {
            step.warned.set(false);
        }
    }
}
