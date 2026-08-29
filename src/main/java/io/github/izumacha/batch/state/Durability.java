package io.github.izumacha.batch.state;

import io.github.izumacha.batch.text.SafeText;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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
 * <p><b>What a failure does depends on what failed.</b> Flushing a directory
 * is the step that legitimately cannot run at all -- Windows does not allow
 * opening a directory as a channel -- and its failure costs the directory
 * entry rather than the record's bytes, so it degrades to a warning: failing
 * a save because the platform cannot {@code fsync} would report a run whose
 * record is on disk as "failed to save execution result", telling the
 * operator the opposite of what happened.
 *
 * <p>Flushing a regular file is different. A failed {@code fsync} there means
 * the record's own bytes did not reach the disk -- with delayed allocation
 * the kernel can report {@code ENOSPC}/{@code EIO} at exactly that point and
 * drop the dirty pages -- so continuing would publish an empty or truncated
 * record and report success, manufacturing the exact failure this class
 * exists to prevent. Those steps propagate. The rule rides on the step (see
 * {@link Step#failureFailsTheSave()}) rather than on the call site, so there
 * is no second method a caller could reach for by mistake.
 *
 * <p><b>Observability.</b> A successful {@code fsync} leaves no trace of its
 * own, so each completed step is logged at {@code FINE} -- the only way to
 * answer "how far did this save actually get committed?" after the fact, and
 * the seam the tests use to prove {@link JsonExecutionStore#save} really
 * performs every step. A failure that is <em>degraded</em> is logged at
 * {@code WARNING} instead, at most once per step per {@link JsonExecutionStore}
 * (the budget is held by this instance, and each command builds its own store).
 * A failure that <em>propagates</em> is not logged at all: it becomes the
 * exception the caller reports, so logging it here would state the same
 * problem twice and spend the step's single warning slot on it. Reading the
 * logs alone, therefore, a {@link Step#RECORD_CONTENT} record that is neither
 * {@code FINE} nor {@code WARNING} is one whose flush failed and failed the
 * save -- the absence is the signal, not evidence that the flush was fine.
 * That inference needs two preconditions. First, the save actually reached the
 * flush: {@code save} serializes the record with Jackson <em>before</em> calling
 * {@code sync}, so an {@code ENOSPC} or a serialization failure there throws
 * without this class ever running, and the same silence then means "never
 * written" rather than "written but not flushed" -- opposite conclusions about
 * where to look. Second, the store performed a single save.
 * The warning budget is per store, so if a caller reuses one
 * {@link JsonExecutionStore} across saves, the second and later degraded
 * flushes emit neither record -- the warning was already spent -- and their
 * silence looks identical to a failed-and-propagated flush even though those
 * saves succeeded. The CLI builds one store per command, so the precondition
 * holds there; an embedder reusing a store should read the logs accordingly.
 * That inference holds for {@code RECORD_CONTENT} only, because it is the one
 * step that runs on every save. {@link Step#PUBLISHED_RECORD_CONTENT} runs
 * only when the publish had to fall back to a non-atomic move -- rare, but
 * genuinely reachable ({@code ATOMIC_MOVE} degrades on any {@code rename(2)}
 * failure, not just {@code EXDEV}) -- so its absence is the <em>normal</em>
 * case and says nothing at all.
 *
 * <p>One step has a third outcome that this class cannot report, because the
 * decision is not made here: {@link Step#RECORD_RENAME} is <em>withheld</em>
 * by {@link JsonExecutionStore#commitPublishedRecord} when the record's
 * contents were never confirmed durable, so committing the entry would leave
 * it pointing at unflushed bytes. That produces neither a {@code FINE} nor a
 * {@code WARNING} on this logger; the note explaining it is logged at
 * {@code FINE} by {@code JsonExecutionStore}. The two loggers share the
 * {@code io.github.izumacha.batch.state} prefix, so enable {@code FINE} on the
 * package rather than on this class to see the whole picture -- otherwise a
 * withheld rename is indistinguishable from a failed one, and the two call for
 * opposite responses (re-run the batch versus investigate the disk).
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
     * <p>Each step owns a <em>separate</em> "warn once" budget (held by the
     * {@link Durability} instance, not here). A single shared budget would let
     * the step that runs earliest and matters least ({@link #BASE_DIRECTORY},
     * which runs on the very first save, before any record exists to lose)
     * spend the only slot, after which the step whose failure actually costs
     * data ({@link #RECORD_RENAME}) could never warn again.
     */
    enum Step {
        // 保存先ディレクトリの作成を確定させる用途（省略すると初回保存でディレクトリごと消えうる）
        BASE_DIRECTORY(Target.DIRECTORY,
                // 同期する相手は「作った階層」ではなく「それを含むディレクトリ」なので、
                // 警告に出るパスもそちら。危ないのはその中に作った階層の方だと分かる文面にする
                "the state directory level created inside this one may not survive a power "
                + "loss, taking every record in it"),
        // 一時ファイルの中身を確定させる用途（省略すると改名だけ残り中身が空・破損になりうる）
        RECORD_CONTENT(Target.FILE,
                "the record may come back empty or garbled after a power loss, "
                + "and will then be skipped as unreadable"),
        // 非アトミック移動の移動先を確定させ直す用途。RECORD_CONTENT と分ける理由は 2 つ。
        // (1) 完了ログが別物になるので、どちらの同期が走ったのかをテストと運用で区別できる
        //     （この経路では一時ファイル側の同期が役に立っていないことが、ログ上も分かる）。
        // (2) 開けなかったときの警告予算が独立する。共有すると、これから捨てられる
        //     一時ファイル側が唯一の枠を使い切り、公開済みの記録の側を黙らせてしまう
        PUBLISHED_RECORD_CONTENT(Target.FILE,
                "the record was published by a non-atomic move and "
                + "its bytes may never have reached the disk, so it can come back empty or garbled "
                + "after a power loss even though the directory entry survived"),
        // 改名（ディレクトリエントリ）を確定させる用途（省略すると保存そのものが巻き戻りうる）
        RECORD_RENAME(Target.DIRECTORY,
                "the record may vanish or roll back to its previous "
                + "contents after a power loss");

        /** What a step flushes, which decides how it is opened and where it can be skipped. */
        enum Target {
            // 通常ファイルを対象にする段階（どのプラットフォームでも同期できる）
            FILE,
            // ディレクトリを対象にする段階（開けるのは POSIX だけ）
            DIRECTORY
        }

        // この段階が何を対象にするか（開き方・環境差の判定・失敗時の扱いを決める）
        private final Target target;
        // この段階を飛ばしたときに起こりうることの説明（警告文に埋め込む）
        private final String consequence;

        Step(Target target, String consequence) {
            // 対象の種別をフィールドへ保存する
            this.target = target;
            // 説明文をフィールドへ保存する
            this.consequence = consequence;
        }

        /**
         * Returns whether a failure of this step should fail the save.
         *
         * <p>Derived from {@link #target} rather than declared separately,
         * because the two are the same fact stated twice. A regular file's
         * {@code fsync} failing means the record's own bytes did not reach the
         * disk -- a real write error, on every platform, whether or not a
         * directory entry already points at them. A directory sync is the one
         * that legitimately cannot run at all (Windows does not allow opening
         * a directory as a channel), and its failure costs the entry rather
         * than the bytes, so it degrades to a warning.
         *
         * <p>Deriving it also removes the axis this originally got wrong.
         * Splitting on "before or after publication" left the non-atomic
         * fallback's re-sync as best-effort even though that path re-flushes
         * the record's own bytes into a freshly allocated destination -- the
         * identical failure, reported as success.
         */
        boolean failureFailsTheSave() {
            // 通常ファイルの同期失敗は「記録のバイト列が書けていない」を意味する
            return target == Target.FILE;
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

    }

    /**
     * The steps that have already spent their one warning, one entry per step.
     *
     * <p>Instance state rather than static, so a {@link JsonExecutionStore}
     * gets its own budgets and nothing has to reach in and reset them. The
     * earlier shape put an {@code AtomicBoolean} on each enum constant, which
     * made the budgets per-JVM and forced a test-only reset method into this
     * class -- along with the trap that a test which forgot to call it would
     * observe zero warnings and pass without checking anything.
     */
    // 警告を出し終えた段階を覚えておく集合（このストア専用。空の状態から始まる）
    private final Set<Step> warned = EnumSet.noneOf(Step.class);

    /**
     * Claims {@code step}'s one-shot warning budget, returning {@code true}
     * only for the first caller.
     */
    private boolean claimWarningBudget(Step step) {
        // 追加できたときだけ true（既に入っていれば消費済みなので false）
        // 同じストアを複数スレッドから使われても二重に警告しないよう、集合への出し入れを直列化する
        synchronized (warned) {
            // 追加できたら初回（＝予算を確保できた）、既に入っていれば消費済み
            return warned.add(step);
        }
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
     * <p>What propagates is only a failure of the <em>flush</em>. A failure to
     * <em>open</em> -- including an unchecked one, which {@link #flush} wraps as
     * {@link OpenFailure} so it lands on the same side -- means the sync could
     * not be attempted rather than that a write was lost, so it warns even for
     * a step whose flush failure would fail the save. The mirror image also
     * holds: an unchecked failure raised by {@code force}/{@code close} did
     * reach the flush, so it follows the flush rule and fails a record-content
     * step. The distinction is which half failed, never checked versus
     * unchecked -- routing on the exception's type instead would let a provider
     * that throws unchecked from {@code force} publish bytes that never reached
     * the disk and still report success. The record's bytes were written
     * and the stream closed before this ran; being unable to reopen the file
     * (an antivirus or indexer holding it with a restrictive share mode on
     * Windows, a provider that rejects the option, a security manager) says
     * nothing about whether those bytes are good. Failing the save there would
     * report "failed to persist run state" for a batch whose record is on
     * disk -- the same inversion as swallowing a real write error, in the
     * other direction. Catching {@link RuntimeException} rather than naming
     * the types is deliberate: this class promises not to be the thing that
     * fails a completed write, and guessing the list is how such promises
     * break.
     *
     * @throws IOException if the <em>flush</em> failed and the step's failure
     *     should fail the save (see {@link Step#failureFailsTheSave()})
     * @return {@code true} if the flush completed, {@code false} if it was
     *     degraded to a warning. Callers use this to keep the two halves of a
     *     save consistent: committing the rename for contents that were never
     *     flushed would produce a durable directory entry pointing at
     *     non-durable bytes -- the first failure mode named in this class's
     *     documentation, and worse than losing the record outright, because
     *     the file then exists but reads back garbled and is skipped by
     *     {@code tryRead}.
     */
    boolean sync(Path path, Step step) throws IOException {
        // 開いて force(true) するところまでを試す
        try {
            flush(path, step);
        } catch (IOException | RuntimeException e) {
            // 検査例外と非検査例外を 1 つの catch にまとめているのは、方針（保存を
            // 失敗させるか）を通る道が 1 本しかないことを構造で保証するため。分けると、
            // 非検査例外の側だけ shouldFailTheSave を通さない書き方が自然に成立して
            // しまい、「バイト列が届いていないのに保存成功と報告する」という、
            // failureFailsTheSave がまさに防ぐために存在する逆転が復活する。
            // 非検査例外は既定以外のファイルシステムプロバイダが force / close で
            // 宣言に反して投げる場合を想定した保険（開く段階の失敗は種別によらず
            // flush が OpenFailure に包むので、ここへは来ない）。
            //
            // 「書き戻しまで到達したか」を 1 度だけ判定し、方針の決定と文面の決定の
            // 両方に同じ答えを渡す。2 か所で別々に判定すると、片方だけ直したときに
            // 「本物のエラーなのに環境の制約として説明する」ずれが生まれる
            boolean reachedTheFlush = !(e instanceof OpenFailure);
            // 保存そのものを失敗させるべき失敗かどうかを判定する（下の表を参照）
            if (shouldFailTheSave(step, reachedTheFlush)) {
                // 検査例外はそのまま、非検査例外は IOException に包んで伝える
                // （shouldFailTheSave が true を返すのは reachedTheFlush が true の
                // ときだけなので、ここで e が OpenFailure であることはない＝
                // 内部マーカー型が呼び出し元へ出ることもない）
                throw e instanceof IOException io ? io : new IOException(describeFailure(step, true), e);
            }
            // そうでなければ、確定できなかったことを警告として残すだけにする
            warnOnce(path, step, e, describeFailure(step, reachedTheFlush));
            // 失敗したので完了は記録せず、確定しなかったことを呼び出し元へ伝える
            return false;
        }
        // ここまで来たら open も force も close も通っている。完了ログを try の外へ
        // 出しているのは、ログの失敗を「同期の失敗」として分類しないため。中に置くと、
        // ログハンドラが投げただけで describeFailure が選ばれ、「バイト列がディスクに
        // 届いていないかもしれない」という誤った警告が出たうえ、その段階の
        // 「1 回だけ」の警告枠まで消費してしまう。
        // ただし外へ出しても、投げるハンドラが刺さっていれば例外はここから抜け、
        // 確定済みの保存が失敗として報告される点は変わらない（java.util.logging は
        // publish() の例外を遮らない）。握り潰す catch を置けば防げるが、
        // それは §6 の「エラーを握り潰さない」に反するうえ、投げる Handler は
        // 組み込み側の設定ミスであって、この層が隠すべきものではないと判断した。
        // close() の後なのは、NFS などで書き込みエラーが遅れて close() で報告されるため。
        // fsync は成功しても痕跡を残さないので、このログが「どこまで確定したか」を
        // 後から追える唯一の手がかりになる。Supplier 版なので FINE が無効なときは
        // 文字列の組み立て自体が起きない
        LOGGER.fine(() -> "Durability step " + step.name() + " completed for '" + SafeText.oneLine(path.toString()) + "'");
        // ここまで来た＝確定した
        return true;
    }

    /**
     * Decides whether a failure should fail the save, from the step and which
     * half of {@link #flush} failed.
     *
     * <p>Two independent conditions, both of which must hold:
     *
     * <ul>
     *   <li>The step flushes the record's own bytes ({@link Step.Target#FILE}). A
     *       directory flush costs the entry rather than the bytes, and is the
     *       one that legitimately cannot run on some platforms.</li>
     *   <li>{@code reachedTheFlush} -- the failure came from the flush, not
     *       from the open. Being unable
     *       to reopen a file whose bytes were already written and closed says
     *       nothing about whether those bytes are good -- an antivirus holding
     *       the file on Windows, say -- so failing the save there would report
     *       "failed to persist run state" for a record that is on disk.</li>
     * </ul>
     *
     * <p>Takes the boolean rather than the exception so the caller decides
     * "did we reach the flush?" once and hands the same answer to this method
     * and to {@link #describeFailure}; deciding it twice lets the policy and
     * the wording that explains it drift apart.
     *
     * <p>Package-private and pure, because the second condition cannot be
     * reached from a test any other way: no test environment can make
     * {@code fsync} itself report an error on demand, so without this seam
     * the difference between the two halves would go unverified.
     */
    static boolean shouldFailTheSave(Step step, boolean reachedTheFlush) {
        // 記録のバイト列を対象にする段階で、かつ書き戻しまで到達していた失敗のときだけ
        return step.failureFailsTheSave() && reachedTheFlush;
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
    void createDirectoriesDurably(Path dir) throws IOException {
        // 作成前の時点でまだ存在しない階層を、深い方から浅い方へたどって集める。
        // 渡されたパスの形（相対・絶対）はここでは変えない。相対パスの解決は
        // directoryHolding() 1 箇所に寄せてあり、ここでも絶対化すると
        // 正規化が 2 箇所に分かれて、どちらが効いているのか追えなくなる
        List<Path> missing = new ArrayList<>();
        for (Path p = dir; p != null && !Files.exists(p); p = p.getParent()) {
            // この階層はまだ無いので「これから作られる階層」として記録する
            missing.add(p);
        }
        // 収集順は深い→浅いなので、浅い→深いの順（親が先に確定する順）へ反転する
        Collections.reverse(missing);
        try {
            // 実際にディレクトリ階層を作成する（既に存在する場合は何もしない）
            Files.createDirectories(dir);
        } catch (IOException createFailed) {
            // 途中まで作れていた分は確定させたうえで、作成の失敗をそのまま伝える。
            // finally に置くと、同期が投げるようになった将来に作成の失敗が黙って
            // 差し替わる（save 側と同じ理由で、安全性をこの場で読み取れる形にする）
            try {
                syncCreatedLevels(missing);
            } catch (IOException syncFailed) {
                // 同期側の失敗は主因ではないので添えるに留める
                createFailed.addSuppressed(syncFailed);
            }
            throw createFailed;
        }
        // 作成に成功したので、作られた各階層の存在を確定させる
        syncCreatedLevels(missing);
    }

    /**
     * Syncs the directory that contains each level in {@code created},
     * shallowest first, stopping at the first level that does not exist.
     *
     * <p>{@link Files#createDirectories} creates shallowest-first, so a level
     * that is missing means everything below it is missing too -- which is how
     * a partially-successful creation is handled without syncing directories
     * that were never made.
     */
    private void syncCreatedLevels(List<Path> created) throws IOException {
        // 確定できなかった階層の数を数える（警告予算との噛み合わせを説明するため）
        int unconfirmed = 0;
        // 最初に確定できなかった「含む側」のパス（集約メッセージ自体を行動可能にするため）
        Path firstUnconfirmed = null;
        // 浅い方から順に、その階層を含むディレクトリを同期して存在を確定させる
        for (Path level : created) {
            // createDirectories は浅い方から作るので、無い階層に当たったらそこから先も無い
            if (!Files.exists(level)) {
                break;
            }
            // ファイルシステムのルートには含まれる側のディレクトリが無いので飛ばす
            Path container = directoryHolding(level);
            if (container != null) {
                // 含む側を同期して、その中の level というエントリを確定させる
                if (!sync(container, Step.BASE_DIRECTORY)) {
                    // 確定できなかった階層を数えておく
                    unconfirmed++;
                    // 最初の 1 件は集約メッセージで名指しできるよう控えておく。
                    // 控えるのは container ではなく level。危ないのは「作った階層」の方で、
                    // container はそれを含む既存のディレクトリ（/tmp/x/y/z を作るなら
                    // /tmp から始まる）なので、そちらを名指しすると、このツールが作っても
                    // いない・危険でもないディレクトリへ運用者を向かわせてしまう
                    if (firstUnconfirmed == null) {
                        firstUnconfirmed = level;
                    }
                }
            }
        }
        // 予算は段階につき 1 回なので、複数の階層が同じ理由で失敗しても警告は 1 本しか
        // 出ず、しかもその 1 本は 1 つのパスしか名指ししない。読んだ運用者が
        // 「危ないのはその階層だけ」と受け取らないよう、件数だけは必ず残す。
        // 予算そのものを階層ごとにしないのは、深い --state-dir を一度作るだけで
        // 何本も同じ警告が出て、段階ごとに 1 回という設計の意図が崩れるため
        // 注: この集約メッセージはテストで踏めない。2 階層以上を同時に失敗させるには
        // ディレクトリの fsync ができないマウントが要り、POSIX の通常環境では作れない
        // （root では権限を落としても素通りする）。文面の正しさはレビューで担保している
        if (unconfirmed > 1) {
            // 実効値をラムダの外へ写す（ラムダは実質 final な変数しか捕まえられない）
            int total = unconfirmed;
            Path first = firstUnconfirmed;
            // 自分でパスを名指しする。「上の警告」に頼ると、予算が別の呼び出しで
            // 既に使われていたときに存在しない行を指すことになり、件数だけ告げて
            // 手がかりを何も渡さないメッセージになってしまう
            LOGGER.warning(() -> total + " directory levels created for the state directory "
                    + "could not be confirmed durable, starting with '"
                    + SafeText.oneLine(String.valueOf(first))
                    + "'; each may not survive a power loss, taking every record in it");
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

        OpenFailure(Throwable cause) {
            // 元の例外を原因として保持する（文面の組み立てで参照する）。
            // Throwable を受けるのは、開く段階の失敗が検査例外とは限らないため
            // （プロバイダが未対応のオプションを拒む UnsupportedOperationException など）。
            // ここで種別を揃えておかないと、呼び出し元は「開けなかった」のか
            // 「開けたが書き戻しに失敗した」のかを見分けられず、本物のエラーを
            // 環境の制約として説明してしまう
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
        // 対象の種別に応じた開き方を選ぶ。ディレクトリは書き込みモードで開けないので
        // 読み取りで開き、通常ファイルは書き込みで開く
        boolean directory = step.target() == Step.Target.DIRECTORY;
        // 開くときのオプション。通常ファイルにはシンボリックリンク非追従を足す。
        // docs/DESIGN.md の「State-directory safety」は state ディレクトリを改変対象と
        // して扱っており、このクラス以外の open はすべて NOFOLLOW_LINKS 付きになっている。
        // ここだけ追従すると、同居プロセスが <runId>.json をリンクへ差し替えた窓で
        // state ディレクトリの外のファイルを書き込みモードで開いて fsync しうる。
        // ディレクトリ側に付けないのは、同期対象が directoryHolding() の返す「含む側」で、
        // /var/run→/run のような正当なリンクを経路に含みうるため
        OpenOption[] options = directory
                ? new OpenOption[] {StandardOpenOption.READ}
                : new OpenOption[] {StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS};
        // まず開く。ここでの失敗は「この環境では同期を試せない」を意味しうる
        FileChannel opened;
        try {
            // 開く前に種別を確かめる。open(2) は FIFO を相手にすると読み手／書き手が
            // 現れるまで無期限にブロックし、Java からタイムアウトも O_NONBLOCK も
            // 渡せない。state ディレクトリは改変対象として扱う想定（DESIGN.md の
            // 「State-directory safety」）なので、同居プロセスが記録の名前で
            // mkfifo すると、バッチが走り終わった後の save() がここで固まり、
            // 「保存できたのか分からないまま CLI が応答しない」という、この仕組みが
            // 消そうとしている曖昧さそのものを作る。NOFOLLOW_LINKS は FIFO を
            // シンボリックリンクではないので弾けない。
            // 判定と open の間で差し替えられる隙間は残るが（Java から原子的に
            // 「通常ファイルなら開く」と要求する手段が無い）、置きっぱなしの FIFO は
            // これで確実に警告へ落ちる。失敗の扱いは他の開けない理由と同じ
            // 「開くとブロックしうる種別か」だけを見る。isOther() が真になるのは
            // 通常ファイル・ディレクトリ・シンボリックリンクのいずれでもないもの、
            // つまり FIFO・ソケット・デバイスで、まさに open(2) が固まる相手。
            // 「通常ファイルか」で判定してしまうと、単に存在しない・stat できない
            // だけの場合まで自前の文面に化けてしまう。警告は段階につき 1 回きりで
            // 運用者が受け取る唯一の通知なので、行方不明のファイルに対して
            // 「パイプを探せ」と言うのは、その 1 回を無駄にすることになる。
            // 読めなければ判定はあきらめ、open が本当の理由を出すのに任せる
            BasicFileAttributes attributes = null;
            try {
                // リンクの扱いは、その段階の open と必ず同じにする。ディレクトリ側は
                // 追従して開く（/var/run→/run のような正当なリンクを経路に含みうる）ので
                // 追従して stat する。NOFOLLOW で見ると、リンク自体は isOther() が偽に
                // なるため、FIFO を指すリンクを素通しして open で固まってしまう。
                // 通常ファイル側は NOFOLLOW で開くので NOFOLLOW で見る
                // （リンクなら open が ELOOP で落ちる＝本当の理由が報告される）
                LinkOption[] linkOptions = directory
                        ? new LinkOption[0]
                        : new LinkOption[] {LinkOption.NOFOLLOW_LINKS};
                attributes = Files.readAttributes(path, BasicFileAttributes.class, linkOptions);
            } catch (IOException | RuntimeException statFailed) {
                // 種別が分からないだけ。attributes は代入が完了していないので null のまま。
                // ここで拒否せず、下の open に本当の失敗（NoSuchFile / AccessDenied 等）を
                // 語らせる — 自前の文面で上書きすると、段階につき 1 回きりの警告が
                // 実際の原因ではなく「パイプを探せ」になってしまう
                // 捕まえた例外そのものも残す。握り潰すと、この判定が黙って無効化された
                // 理由（NOFOLLOW_LINKS 非対応のプロバイダ・親ディレクトリの EACCES 等）を
                // 後から追えず、FIFO でぶら下がったときに「なぜ守れなかったのか」が
                // どこにも無い状態になる（§6 エラーを握り潰さない）
                LOGGER.fine(() -> "could not determine the file type of '"
                        + SafeText.oneLine(path.toString())
                        + "' (" + SafeText.bounded(SafeText.oneLine(String.valueOf(statFailed)),
                                JsonExecutionStore.MAX_LOGGED_DETAIL_CHARS) + "); letting the open report the reason");
            }
            if (attributes != null && attributes.isOther()) {
                throw new IOException("refusing to sync '" + path
                        + "': it is a pipe, socket, or device, and opening one here "
                        + "would block until a peer appeared");
            }
            opened = FileChannel.open(path, options);
        } catch (IOException | RuntimeException e) {
            // 検査例外か否かによらず、開けなかったことが呼び出し元に分かるよう包んで投げ直す
            throw new OpenFailure(e);
        }
        // 開けたので、閉じる責任を持ちつつ書き戻しを要求する
        try (FileChannel channel = opened) {
            // true を渡すことで中身だけでなくメタデータの書き戻しも要求する
            channel.force(true);
        }
    }

    /**
     * Words a failure according to what was being synced and which half of
     * {@link #flush} failed.
     *
     * <p>Getting this wrong is not cosmetic. The warning is emitted once per
     * step per store, so it is effectively the only notice the operator will
     * get. Only a directory can plausibly fail to open because of the
     * platform, so wording a genuine flush error that way -- or wording a
     * regular file's failure that way at all -- tells the operator to ignore
     * the one signal saying their records are not reaching the disk.
     *
     * <p>The open-failure wording deliberately stops short of calling itself
     * benign. Windows and a POSIX {@code AccessDenied}/{@code NoSuchFile} both
     * surface as an {@link IOException} from the same call, so the type cannot
     * tell them apart; claiming "this platform may not allow it" would dismiss
     * a real permissions problem. It names both possibilities and points at
     * the cause, which {@code warnOnce} appends.
     *
     * <p>Takes "did we reach the flush?" rather than the exception, so the
     * unchecked path (which has no {@link IOException} to inspect) routes
     * through here too. Every wording this class emits comes from this method;
     * a call site that invented its own would be free to describe a regular
     * file's failure as a platform quirk, which is the mistake this exists to
     * prevent.
     *
     * <p>Package-private so both wordings can be pinned by a test: the
     * "opened but the flush failed" branch needs a disk that reports an error
     * from {@code fsync}, which no test environment can arrange on demand.
     */
    static String describeFailure(Step step, boolean reachedTheFlush) {
        // 通常ファイルは開けないこと自体が異常なので、環境差の言い訳をしない
        if (step.target() == Step.Target.FILE) {
            return "could not sync the file";
        }
        // 書き戻しまで到達していないなら、この環境がディレクトリを開けない可能性がある
        if (!reachedTheFlush) {
            return "could not open the directory to sync it "
                    + "(never possible on platforms such as Windows; anywhere else this is a "
                    + "real problem -- permissions, or the directory was removed -- so read "
                    + "the cause)";
        }
        // 開けたうえで失敗したのなら、環境差ではなく本物の同期エラーである
        return "the directory opened but the sync itself failed, "
                + "so this is a real error rather than a platform limitation";
    }

    /**
     * Reports a skipped step at most once per step per store, explaining what
     * could not be committed and what that risks.
     */
    private void warnOnce(Path path, Step step, Exception cause, String reason) {
        // 予算を使えたときだけ記録する（同じ段階で何度も鳴らさない）
        if (!claimWarningBudget(step)) {
            return;
        }
        // OpenFailure は「開く段階で失敗した」ことを内部で伝えるためだけの目印で、
        // その toString() は包んだ原因を繰り返すだけ（IOException(Throwable) は
        // 原因の toString() を detailMessage に採用する）。段階ごとに 1 回きりの
        // 警告に内部クラス名を混ぜても運用者の役に立たないので、中身へ置き換える
        Throwable shown = cause instanceof OpenFailure ? cause.getCause() : cause;
        // 何が確定できなかったのか、省略すると何が起こりうるのかをまとめて記録する
        LOGGER.warning("Durability step " + step.name() + " skipped for '" + SafeText.oneLine(path.toString()) + "': "
                + reason + " (" + SafeText.bounded(SafeText.oneLine(String.valueOf(shown)),
                        JsonExecutionStore.MAX_LOGGED_DETAIL_CHARS) + "); " + step.consequence
                + ". This warning is reported once per step, per store.");
    }
}
