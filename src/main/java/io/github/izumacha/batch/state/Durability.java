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
 * <p><b>Best-effort, never fatal -- with one deliberate exception.</b> These
 * methods log and return instead of throwing. A sync failure normally arrives
 * <em>after</em> the write itself succeeded, so propagating it would turn a
 * run whose record is on disk into a reported "failed to save execution
 * result", telling the operator the opposite of what happened. Durability is
 * a hardening measure layered on top of a write that already worked; losing
 * the hardening must not lose the write.
 *
 * <p>{@link Step.OnFailure#PROPAGATE} is the exception, carried by the one
 * step that runs before publication. There that justification is simply
 * false, and swallowing the error would manufacture the exact failure this
 * class exists to prevent. The policy rides on the step rather than on the
 * call site so there is no second method a caller could reach for by
 * mistake.
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
        BASE_DIRECTORY(Target.DIRECTORY, OnFailure.WARN,
                "the state directory itself may not survive a power loss, "
                + "taking every record inside it"),
        // 一時ファイルの中身を確定させる用途（省略すると改名だけ残り中身が空・破損になりうる）。
        // この段階だけ失敗を伝播させる理由は OnFailure.PROPAGATE の説明を参照
        RECORD_CONTENT(Target.FILE, OnFailure.PROPAGATE,
                "the record may come back empty or garbled after a power loss, "
                + "and will then be skipped as unreadable"),
        // 非アトミック移動の移動先を確定させ直す用途。RECORD_CONTENT と分けているのは
        // 予算を共有すると、これから捨てられる一時ファイル側の失敗が唯一の枠を使い切り、
        // 「公開済みの記録が未確定」という本当に危険な失敗を黙らせてしまうため
        // （段階ごとに予算を分ける理由そのものが、この 2 つの間にも当てはまる）
        PUBLISHED_RECORD_CONTENT(Target.FILE, OnFailure.WARN,
                "the record was published by a non-atomic move and "
                + "its bytes may never have reached the disk, so it can come back empty or garbled "
                + "after a power loss even though the directory entry survived"),
        // 改名（ディレクトリエントリ）を確定させる用途（省略すると保存そのものが巻き戻りうる）
        RECORD_RENAME(Target.DIRECTORY, OnFailure.WARN,
                "the record may vanish or roll back to its previous "
                + "contents after a power loss");

        /** What a step flushes, which decides how it is opened and where it can be skipped. */
        enum Target {
            // 通常ファイルを対象にする段階（どのプラットフォームでも同期できる）
            FILE,
            // ディレクトリを対象にする段階（開けるのは POSIX だけ）
            DIRECTORY
        }

        /** What a failed step does to the save in progress. */
        enum OnFailure {
            /**
             * Log once and continue -- the default, and correct wherever the
             * sync runs after the write is already visible, because reporting
             * a failure would contradict what is on disk.
             */
            WARN,
            /**
             * Propagate, failing the save.
             *
             * <p>Only correct before publication. There the record is still a
             * temp file the caller's {@code finally} deletes, and with delayed
             * allocation an {@code fsync} can report {@code ENOSPC}/{@code EIO}
             * and drop the dirty pages -- so continuing would rename empty or
             * truncated bytes into place and report success, manufacturing the
             * exact failure this class exists to prevent.
             */
            PROPAGATE
        }

        // この段階が何を対象にするか（開き方の決定と、環境差の判定に使う）
        private final Target target;
        // 失敗したときに保存を失敗させるか、警告に留めるか
        private final OnFailure onFailure;
        // この段階を飛ばしたときに起こりうることの説明（警告文に埋め込む）
        private final String consequence;
        // この段階の警告をすでに 1 度出したかどうか（段階ごとに独立した予算）
        private final AtomicBoolean warned = new AtomicBoolean(false);

        Step(Target target, OnFailure onFailure, String consequence) {
            // 対象の種別をフィールドへ保存する
            this.target = target;
            // 失敗時の扱いをフィールドへ保存する
            this.onFailure = onFailure;
            // 説明文をフィールドへ保存する
            this.consequence = consequence;
        }

        /**
         * Returns what a failure of this step does to the save.
         *
         * <p>On the constant for the same reason as {@link #consequence} and
         * {@link #target}, and here it matters most: with the policy attached
         * to the step there is exactly one way to run a step, so a caller
         * cannot pick the wrong one. The earlier shape -- a best-effort method
         * and a fail-closed method, chosen at each call site -- let the
         * pre-publication call be silently swapped for the best-effort variant
         * with nothing to catch it.
         */
        OnFailure onFailure() {
            // 失敗時の扱いをそのまま返す
            return onFailure;
        }

        /**
         * Returns what this step flushes.
         *
         * <p>On the constant rather than at the call sites for the same reason
         * as {@link #consequence}: a new step cannot be added without
         * classifying it. The tests need this fact too -- a directory step
         * produces no completion record on a platform that cannot open
         * directories -- and reading it from here keeps their expectations
         * exhaustive by construction instead of relying on a hand-maintained
         * list that a new constant could quietly fall out of.
         */
        Target target() {
            // 対象の種別をそのまま返す
            return target;
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
     * Runs one durability step against {@code path}: opens it the way the
     * step's target requires, flushes it, and reacts to a failure the way the
     * step's policy says.
     *
     * <p>This is the only way to run a step. Opening the file again rather
     * than syncing the handle Jackson wrote through keeps the serialization
     * call untouched: Jackson closes the stream it is handed, and a closed
     * descriptor can no longer be synced. Reopening costs one
     * {@code open}/{@code close} pair and syncs exactly the same file.
     *
     * <p>An <em>unchecked</em> failure never propagates, whatever the step's
     * policy. It means the sync could not be <em>attempted</em> (an option the
     * provider rejects, a security manager) rather than that a write was lost,
     * and failing a save because the platform cannot {@code fsync} would be
     * the same inversion as swallowing a real write error, in the other
     * direction. Catching {@link RuntimeException} rather than naming the
     * types is deliberate: this class promises not to be the thing that fails
     * a completed write, and guessing the list is how such promises break.
     *
     * @throws IOException if the sync failed and the step's policy is
     *     {@link Step.OnFailure#PROPAGATE}
     */
    static void sync(Path path, Step step) throws IOException {
        // 開いて force(true) するところまでを試す
        try {
            flush(path, step);
        } catch (IOException e) {
            // この段階の方針が「伝播」なら、呼び出し元へそのまま投げて保存を失敗させる
            if (step.onFailure() == Step.OnFailure.PROPAGATE) {
                throw e;
            }
            // そうでなければ、確定できなかったことを警告として残すだけにする
            warnOnce(path, step, e, describeFailure(step, e));
        } catch (RuntimeException e) {
            // 「同期を試せなかった」は方針によらず警告どまり（上記 Javadoc 参照）
            warnOnce(path, step, e, "could not attempt the sync on this platform");
        }
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
     * not synced here: it holds no entries yet, and {@link #sync} is called
     * on it with {@link Step#RECORD_RENAME} after the record rename, which is
     * when it first has
     * something to lose.
     *
     * <p>Which directories were synced, and in what order, is observable from
     * the {@code FINE} records {@link #sync} emits (each names its path), so
     * this returns nothing: a return value would exist only for tests to
     * assert on, and would assert what this method believes it created rather
     * than what was actually flushed.
     *
     * @throws IOException if the directories could not be created
     */
    static void createDirectoriesDurably(Path dir) throws IOException {
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
        // 新しく作られた各階層について、その階層を含むディレクトリを同期し存在を確定させる
        for (Path level : missing) {
            // ファイルシステムのルートには含まれる側のディレクトリが無いので飛ばす
            Path container = directoryHolding(level);
            if (container != null) {
                // 含む側を同期して、その中の level というエントリを確定させる
                sync(container, Step.BASE_DIRECTORY);
            }
        }
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
     * Marks a failure as having happened while opening the target, so callers
     * can tell "this platform cannot open a directory as a channel" apart from
     * "the flush itself reported an error".
     */
    static final class OpenFailure extends IOException {
        // 直列化 ID（例外クラスに付けるのが慣例。この例外を直列化する用途は無い）
        private static final long serialVersionUID = 1L;

        OpenFailure(IOException cause) {
            // 元の例外を原因として保持する（文面の組み立てで参照する）
            super(cause);
        }
    }

    /**
     * Opens {@code path} according to its step's target and forces it to disk.
     *
     * <p>The open and the {@code force} are attempted in separate blocks so
     * the caller can tell them apart. Which one failed is the whole difference
     * between a benign platform gap (a directory cannot be opened on Windows)
     * and a real loss of durability (the flush reported an error on a
     * directory that opened fine) -- and those two deserve opposite reactions
     * from whoever reads the warning.
     *
     * <p>Unchecked exceptions are deliberately not caught here. They mean the
     * sync could not be attempted at all, and each caller decides what that is
     * worth; letting them pass keeps this method's job to "open, flush, say
     * which part failed".
     *
     * @throws OpenFailure if {@code path} could not be opened
     * @throws IOException if the flush itself failed
     */
    private static void flush(Path path, Step step) throws IOException {
        // 対象の種別に応じた開き方を選ぶ（ディレクトリは書き込みモードで開けない）
        StandardOpenOption mode = step.target() == Step.Target.DIRECTORY
                ? StandardOpenOption.READ
                : StandardOpenOption.WRITE;
        // まず開く。ここでの失敗は「この環境では同期を試せない」を意味しうる
        FileChannel opened;
        try {
            opened = FileChannel.open(path, mode);
        } catch (IOException e) {
            // 開けなかったことが呼び出し元に分かるよう包んで投げ直す
            throw new OpenFailure(e);
        }
        // 開けたので、閉じる責任を持ちつつ書き戻しを要求する
        try (FileChannel channel = opened) {
            // true を渡すことで中身だけでなくメタデータの書き戻しも要求する
            channel.force(true);
            // 成功した段階を FINE で記録する。fsync は成功しても何の痕跡も残らないため、
            // 「この保存はどこまで確定したのか」を現場で追う手段がこれ以外に無い。
            // Supplier 版を使うので FINE が無効なときは文字列組み立て自体が起きない
            LOGGER.fine(() -> "Durability step " + step.name() + " completed for '" + path + "'");
        }
    }

    /**
     * Words a failure according to what was being synced and which half of
     * {@link #flush} failed.
     *
     * <p>Getting this wrong is not cosmetic. The warning is emitted once per
     * step for the life of the process, so it is the only notice the operator
     * will ever get. Only a directory can plausibly fail to open because of
     * the platform, so wording a genuine flush error that way -- or wording a
     * regular file's failure that way at all -- tells the operator to ignore
     * the one signal saying their records are not reaching the disk.
     *
     * <p>Package-private so both wordings can be pinned by a test: the
     * "opened but the flush failed" branch needs a disk that reports an error
     * from {@code fsync}, which no test environment can arrange on demand.
     */
    static String describeFailure(Step step, IOException e) {
        // 通常ファイルは開けないこと自体が異常なので、環境差の言い訳をしない
        if (step.target() == Step.Target.FILE) {
            return "could not sync the file";
        }
        // 開く段階で失敗したのなら、この環境がディレクトリを開けないという説明が妥当
        if (e instanceof OpenFailure) {
            return "could not open the directory to sync it "
                    + "(a platform such as Windows does not allow this)";
        }
        // 開けたうえで失敗したのなら、環境差ではなく本物の同期エラーである
        return "the directory opened but the sync itself failed, "
                + "so this is a real error rather than a platform limitation";
    }

    /**
     * Reports a skipped step at most once per step, explaining what could not
     * be committed and what that risks.
     */
    private static void warnOnce(Path path, Step step, Exception cause, String reason) {
        // 予算を使えたときだけ記録する（同じ段階で何度も鳴らさない）
        if (!step.claimWarningBudget()) {
            return;
        }
        // 何が確定できなかったのか、省略すると何が起こりうるのかをまとめて記録する
        LOGGER.warning("Durability step " + step.name() + " skipped for '" + path + "': "
                + reason + " (" + cause + "); " + step.consequence
                + ". This warning is reported once per step.");
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
            // この段階の「1 回だけ」の枠を未使用へ戻す
            step.warned.set(false);
        }
    }
}
