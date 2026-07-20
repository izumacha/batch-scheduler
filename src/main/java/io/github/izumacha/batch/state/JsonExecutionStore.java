package io.github.izumacha.batch.state;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.izumacha.batch.model.ExecutionResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * File-backed {@link ExecutionStore} that persists one pretty-printed JSON
 * document per run, named {@code <runId>.json}, under a base directory.
 *
 * <p>Instants are written as ISO-8601 strings (timestamps disabled) and reads
 * tolerate missing, oversized, or unparseable files by skipping them.
 */
public final class JsonExecutionStore implements ExecutionStore {

    // このクラスのログ出力用 Logger（ファイル読み込み失敗時の警告に使う）
    private static final Logger LOGGER = Logger.getLogger(JsonExecutionStore.class.getName());

    // JSON ファイルの拡張子（runId.json という形式で保存する）
    private static final String SUFFIX = ".json";
    /** Fixed temp-file prefix; must be >= 3 chars for {@link Files#createTempFile}. */
    // 一時ファイルのプレフィックス（createTempFile は 3 文字以上必要）
    private static final String TMP_PREFIX = "run-";
    /**
     * Absolute safety ceiling for unbounded list retrieval ({@link #findAll()}
     * and {@link #findRecent} with {@code limit <= 0}), so a pathologically
     * large state directory cannot force an unbounded full-content parse even
     * when the caller explicitly asked for "no limit" (CLI {@code list
     * --limit 0}). batch-scheduler accumulates one file per run over the
     * tool's lifetime (§ CLAUDE.md: intended for repeated/CI invocation), so
     * a long-lived state directory can grow far larger than any interactive
     * "show me everything" request actually needs. This ceiling is far above
     * any realistic usage and only changes behavior in that pathological
     * case -- it is a circuit breaker, not a user-facing cap.
     */
    // 「上限なし」（findAll / findRecent(limit<=0)）の絶対的な安全上限。長期運用で
    // .batch-state に大量のファイルが蓄積した場合でも、内容の全文パースが無制限に
    // ならないようにする（現実的な利用規模を大きく超えた場合のみ効くサーキットブレーカー）
    static final int MAX_UNBOUNDED_RESULTS = 100_000;
    /**
     * Upper bound on a single stored execution-result JSON document, mirroring
     * {@link io.github.izumacha.batch.config.BatchConfigLoader#MAX_CONFIG_BYTES}'s
     * "bound before parsing" guard (docs/DESIGN.md: "Bounded config parsing").
     * That guard covers the number of files parsed per list request
     * ({@link #MAX_UNBOUNDED_RESULTS}) but, until now, nothing capped the size
     * of an individual file: {@link #tryRead} handed the file straight to
     * Jackson with no limit, so a single oversized {@code <runId>.json} --
     * whether from a misconfigured {@code JobRunner} (a very large
     * {@code maxCapturedOutputLines}), a corrupted write, or a file dropped
     * into the state directory by another process with write access -- could
     * force an unbounded in-memory parse on every {@code run}/{@code list}
     * invocation that touches it (docs/DESIGN.md "State-directory safety"
     * already treats the state directory as a tampering target: run-id
     * validation and no-symlink-following exist for the same reason). 16 MiB
     * is far above any legitimate record (JobRunner's per-job message is at
     * most one captured output line, capped at 8 KiB by
     * {@code OutputCollector.MAX_LINE_CHARS}, so even a batch with
     * thousands of jobs stays well under this ceiling) -- a circuit breaker,
     * not a practical cap.
     */
    // 保存済み実行結果 JSON 1 件あたりのサイズ上限。BatchConfigLoader が設定ファイルに
    // 対して行っている「パース前にサイズを拒否する」防御（docs/DESIGN.md: bounded config
    // parsing）と同じ考え方を、これまで無防備だった状態ファイルの読み込みにも適用する。
    // MAX_UNBOUNDED_RESULTS は「一覧で何件パースするか」を制限するが、1 件のファイル
    // 自体の大きさは制限しておらず、tryRead はサイズを確認せず Jackson にそのまま渡していた。
    // 巨大な maxCapturedOutputLines 設定・書き込み途中の破損・他プロセスによる改変などで
    // 肥大化した 1 ファイルだけでも、それに触れる run/list のたびに無制限なメモリパースを
    // 強いられ得る（状態ディレクトリを改変対象として扱う前提は docs/DESIGN.md の
    // 「State-directory safety」で runId 検証・シンボリックリンク非追従として既に明言されている）。
    // 16MiB は正規の記録を大きく上回る値（JobRunner が message に含めるのは出力の最終 1 行の
    // みで OutputCollector.MAX_LINE_CHARS により 8KiB に上限があるため、ジョブ数が数千規模の
    // バッチでも十分に収まる）であり、現実的な用途を妨げないサーキットブレーカーとして機能する。
    static final long MAX_RECORD_BYTES = 16L * 1024 * 1024; // 16 MiB

    // JSON ファイルを保存するベースディレクトリのパスを保持するフィールド
    private final Path baseDir;
    // JSON のシリアライズ・デシリアライズに使う Jackson の ObjectMapper インスタンス
    private final ObjectMapper mapper;

    public JsonExecutionStore(Path baseDir) {
        // baseDir が null の場合は例外を投げる（保存先ディレクトリは必須）
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir must not be null");
        }
        // ベースディレクトリのパスをフィールドに保存する
        this.baseDir = baseDir;
        // ObjectMapper を生成し、Java 時刻型（Instant など）を扱うモジュールを登録する
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // Instant を数値ではなく ISO-8601 文字列として出力する
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // JSON を読みやすいインデント付きで出力する
                .enable(SerializationFeature.INDENT_OUTPUT)
                // Tolerate fields written by newer versions (forward compatibility).
                // 未知のフィールドがあっても例外を投げず無視する（上位互換性のため）
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 注: ここでベースディレクトリは作成しない。コンストラクタで作成すると
        // 読み取り専用の list コマンドまで副作用でディレクトリを作ってしまい、
        // findAll / findRecent の「ディレクトリ未存在なら空を返す」分岐にも
        // 到達できなくなるため、作成は save() / ensureBaseDirectory() に限定する。
    }

    /**
     * {@code baseDir} 自体がシンボリックリンクかどうかを判定する（CWE-59 対策）。
     * {@code ensureBaseDirectory}・{@code findAll}・{@code findRecent}・{@code findById} の
     * 4 箇所が同一の判定ロジックを必要とするため、判定処理そのものをここに集約する
     * （§6 DRY: 2〜3 箇所目で重複したら共通化する）。判定結果を受けてどう振る舞うか
     * （例外を投げる／空を返す）は呼び出し元ごとに異なるため、その後続処理までは
     * 共通化せず各メソッドに残す。{@code Files.isSymbolicLink} はリンクを辿らずパスそのものを
     * 判定するため、シンボリックリンクの先を誤って正当な対象として扱うことはない。
     */
    private boolean isBaseDirSymlink() {
        // baseDir がシンボリックリンクかどうかをそのまま返す
        return Files.isSymbolicLink(baseDir);
    }

    /**
     * ベースディレクトリを作成・検証する（存在すれば何もしない）。
     * 書き込みを伴うコマンドが実行前に保存先の使用可否を早期確認（fail fast）
     * したい場合に明示的に呼ぶ。読み取り専用の利用では呼ばないこと。
     *
     * @throws UncheckedIOException 作成に失敗した場合（例: 既存ファイルと衝突・権限不足）、
     *     または {@code baseDir} 自体がシンボリックリンクだった場合
     */
    public void ensureBaseDirectory() {
        // baseDir 自体がシンボリックリンクの場合は拒否する。docs/DESIGN.md の
        // 「state ディレクトリの安全性（シンボリックリンク非追従）」は個々の <runId>.json
        // ファイルには NOFOLLOW_LINKS で既に及んでいるが、ベースディレクトリ自体には
        // この防御が無く、事前にリンクを仕込まれると Files.createDirectories がリンク先を
        // そのまま辿ってしまい（CWE-59）、以降のすべての実行結果が想定外のディレクトリへ
        // 書き込まれてしまう。判定ロジックは isBaseDirSymlink() に集約している（§6 DRY）
        if (isBaseDirSymlink()) {
            throw new UncheckedIOException(
                    new IOException("refusing to use a symlinked state directory: " + baseDir));
        }
        try {
            // ベースディレクトリが存在しない場合は再帰的に作成する
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            // ディレクトリ作成に失敗した場合はチェックなし例外に包んで投げる
            throw new UncheckedIOException(
                    "failed to create execution store directory: " + baseDir, e);
        }
    }

    @Override
    public void save(ExecutionResult result) {
        // result が null の場合は例外を投げる
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        // runId が null または空白の場合は例外を投げる（ファイル名に使うため必須）
        if (result.runId() == null || result.runId().isBlank()) {
            throw new IllegalArgumentException("result.runId must not be null or blank");
        }
        // ベースディレクトリが存在しない場合は再帰的に作成する（同時実行でも安全）。
        // ここで isBaseDirSymlink() による事前チェックが走り、事前に仕込まれたシンボリック
        // リンクは fail fast で拒否される
        ensureBaseDirectory();
        // ensureBaseDirectory() の判定から実際に書き込みが完了するまでの間にも、baseDir が
        // シンボリックリンクへ動的に差し替えられる余地が残っていた（TOCTOU）。
        // Files.createTempFile と Files.move はどちらも呼び出し時点の baseDir をパスとして
        // 再解決するため、その途中どこか 1 箇所だけをもう一度 isBaseDirSymlink() で
        // 再チェックしても、その次の 1 手が実際に差し替えを踏む可能性は残ってしまい
        // （例えば move 直前だけ確認しても move 自体との間にまだ隙間が残る）、
        // 何回チェックを増やしても隙間を完全にはゼロにできない。そこで方式を変える:
        // 書き込みシーケンスの前後で baseDir の実体パス（シンボリックリンクを解決した
        // 実際のディレクトリ）を記録しておき、書き込みが完了した直後に「実際に書き込んだ
        // 先」が開始時に確認した実体ディレクトリと一致するかを検証する。シーケンス中の
        // どの時点で差し替えが起きても、この 1 回の事後検証で必ず検知でき、一致しなければ
        // 誤って書き込まれたファイルを削除したうえで拒否する（fail-closed）。
        // ensureBaseDirectory() が baseDir を作成済みなので toRealPath() は例外なく解決できる
        Path expectedRealBase;
        try {
            expectedRealBase = baseDir.toRealPath();
        } catch (IOException e) {
            // 実体パスの解決に失敗した場合はチェックなし例外に包んで投げる
            throw new UncheckedIOException(
                    "failed to resolve execution store directory: " + baseDir, e);
        }
        try {
            // runId から書き込み先のファイルパスを計算する
            Path target = fileFor(result.runId());
            // Write to a temp file in the same directory, then move atomically
            // so readers never observe a half-written file.
            // Prefix is independent of runId: createTempFile requires >= 3 chars,
            // but a runId may be as short as one character.
            // 同じディレクトリに一時ファイルを作成する（アトミック移動のため同一ファイルシステム上に置く）
            Path tmp = Files.createTempFile(baseDir, TMP_PREFIX, ".tmp");
            try {
                // 一時ファイルに JSON として実行結果を書き込む
                mapper.writeValue(tmp.toFile(), result);
                try {
                    // 一時ファイルを最終ファイルへアトミックに移動する（読者が半端なファイルを読まないようにする）
                    Files.move(tmp, target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    // Some filesystems don't support atomic moves; fall back.
                    // アトミック移動に対応していないファイルシステムの場合は通常の移動にフォールバックする
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                // 何があっても一時ファイルを削除してゴミファイルが残らないようにする
                Files.deleteIfExists(tmp);
            }
            // 実際に書き込んだ場所が、書き込み開始時に確認した実体ディレクトリと
            // 一致するかを検証する（一致しなければ誤って書き込まれたファイルを削除し拒否する）
            verifyWroteUnderExpectedBase(target, expectedRealBase, baseDir);
        } catch (IOException e) {
            // IO 例外をチェックなし例外に包んで投げる
            throw new UncheckedIOException(
                    "failed to save execution result '" + result.runId() + "' under " + baseDir, e);
        }
    }

    @Override
    public Optional<ExecutionResult> findById(String runId) {
        // runId が null または空白の場合は空の Optional を返す
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        // baseDir 自体がシンボリックリンクの場合、findAll/findRecent と同じ CWE-59 対策として
        // 存在しない場合と同様に扱う。下の Files.isRegularFile(..., NOFOLLOW_LINKS) は解決済み
        // パスの「末尾コンポーネント」がリンクでないことしか保証せず、baseDir 自体がリンクだと
        // 親ディレクトリの中間コンポーネントとして素通りに辿られてしまう（NOFOLLOW_LINKS は
        // 末尾コンポーネントにしか効かない）ため、攻撃者が --state-dir を事前にリンクへ差し替えて
        // いた場合、意図しない場所の <runId>.json をそのまま読めてしまう。findAll/findRecent は
        // この対策を既に持つが、findById だけ抜け落ちていた
        if (isBaseDirSymlink()) {
            return Optional.empty();
        }
        // runId からファイルパスを計算する
        Path file = fileFor(runId);
        // Do not follow symlinks: only read a regular file that lives directly in
        // the state directory, never a link an attacker may have swapped in.
        // シンボリックリンクを辿らずに通常ファイルかどうか確認する（パストラバーサル対策）
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            // ファイルが存在しない場合は空の Optional を返す
            return Optional.empty();
        }
        // 読み込み・パース・壊れたファイルの読み飛ばしは findAll/findRecent と共通のヘルパーに委譲する
        return tryRead(file);
    }

    @Override
    public List<ExecutionResult> findAll() {
        // ベースディレクトリが存在しない場合、またはシンボリックリンクの場合（ensureBaseDirectory
        // と同じ CWE-59 対策。書き込み経路だけでなく読み取り経路も攻撃者が差し替えた
        // リンク先を辿らないようにする）は空リストを返す
        if (isBaseDirSymlink() || !Files.isDirectory(baseDir)) {
            return List.of();
        }
        // 読み込んだ実行結果を蓄積するリストを作成する
        List<ExecutionResult> results = new ArrayList<>();
        // パース対象の候補パス（中身はまだ読まない。ファイル名一覧の取得は軽量なので先に全件集める）
        List<Path> candidates;
        try (Stream<Path> files = Files.list(baseDir)) {
            // ベースディレクトリ内のファイルをフィルタリングして候補リストを作る
            candidates = files
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) // シンボリックリンクを除外する
                    .filter(p -> p.getFileName().toString().endsWith(SUFFIX))        // .json 拡張子のファイルのみ対象にする
                    .toList();
        } catch (IOException e) {
            // ディレクトリ一覧の取得に失敗した場合はチェックなし例外に包んで投げる
            throw new UncheckedIOException(
                    "failed to list execution results under " + baseDir, e);
        }
        // 候補が安全上限を超えている場合は、ファイル名（=時系列）の新しい順に絞り込んでから
        // パースする（全件の内容パースによる資源枯渇を避ける。MAX_UNBOUNDED_RESULTS 参照）
        if (candidates.size() > MAX_UNBOUNDED_RESULTS) {
            LOGGER.warning("State directory " + baseDir + " has " + candidates.size()
                    + " execution results, exceeding the unbounded-list safety ceiling ("
                    + MAX_UNBOUNDED_RESULTS + "); returning only the " + MAX_UNBOUNDED_RESULTS
                    + " most recent by filename. Use 'list --limit N' for a bounded view.");
            candidates = keepMostRecentByFilename(candidates, MAX_UNBOUNDED_RESULTS);
        }
        // 絞り込んだ候補だけを実際にパースする（読み込み・パース・壊れたファイルの読み飛ばしは
        // 共通ヘルパー tryRead に委譲する）
        candidates.forEach(p -> tryRead(p).ifPresent(results::add));
        // 結果を開始日時の降順（最新順）に並べ替える
        results.sort(ExecutionResults.BY_STARTED_AT_DESC);
        // 並べ替え済みのリストを返す
        return results;
    }

    /**
     * Overrides the {@link ExecutionStore} default, which builds the bounded
     * result by calling {@link #findAll()} first and truncating afterward --
     * that still parses every stored run just to return a handful, defeating
     * the point of a limit (§8: list retrieval itself must be bounded, not
     * just its rendered output). Instead, list only the file names, sort by
     * name descending, and parse just the {@code limit} candidates.
     *
     * <p>This relies on {@code runId} being generated as
     * {@code yyyyMMdd-HHmmss-<hex>} ({@code BatchExecutor.generateRunId}), so
     * filenames sort lexicographically in chronological order. The selected
     * candidates are still re-sorted by {@link ExecutionResults#BY_STARTED_AT_DESC}
     * after parsing so the returned order matches the documented "most
     * recent first" contract exactly, even if a file was manually edited.
     *
     * <p><b>Known trade-off:</b> because the candidate window is chosen from
     * filenames alone, a corrupted/unparseable file inside that window is
     * simply skipped rather than backfilled from just outside the window
     * (unlike the {@link #findAll()}-then-truncate default, which always
     * returns {@code limit} results as long as that many valid runs exist
     * anywhere). A single manually-corrupted state file can therefore make
     * this method return fewer than {@code limit} results even when older
     * valid runs exist. This is accepted for the MVP: corrupted state files
     * are not an expected operational state (§ state directory safety), and
     * widening the window to backfill would reintroduce unbounded parsing.
     */
    @Override
    public List<ExecutionResult> findRecent(int limit) {
        // limit <= 0 は「上限なし」を意味するため、この場合は素直に全件取得の経路を使う
        if (limit <= 0) {
            return findAll();
        }
        // limitがMAX_UNBOUNDED_RESULTSを超える場合、findAllと同じ安全上限を適用する。
        // ここでクランプしないと、呼び出し元が意図的か誤りかに関わらず巨大なlimit
        // （例: CLIの`list --limit 200000`）を渡すだけでfindAll側の安全上限を回避でき、
        // 状態ディレクトリに大量のファイルが蓄積した環境で無制限パースが再発してしまう
        if (limit > MAX_UNBOUNDED_RESULTS) {
            LOGGER.warning("Requested limit " + limit + " exceeds the unbounded-list safety "
                    + "ceiling (" + MAX_UNBOUNDED_RESULTS + "); clamping to " + MAX_UNBOUNDED_RESULTS
                    + ".");
            limit = MAX_UNBOUNDED_RESULTS;
        }
        // ベースディレクトリが存在しない場合、またはシンボリックリンクの場合（findAll と同じ
        // CWE-59 対策）は空リストを返す
        if (isBaseDirSymlink() || !Files.isDirectory(baseDir)) {
            return List.of();
        }
        // 中身をパースせず、ファイル名だけを集めて絞り込む候補パスのリスト
        List<Path> candidates;
        try (Stream<Path> files = Files.list(baseDir)) {
            candidates = files
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) // シンボリックリンクを除外する
                    .filter(p -> p.getFileName().toString().endsWith(SUFFIX))        // .json 拡張子のファイルのみ対象にする
                    .toList();
        } catch (IOException e) {
            // ディレクトリ一覧の取得に失敗した場合はチェックなし例外に包んで投げる
            throw new UncheckedIOException(
                    "failed to list execution results under " + baseDir, e);
        }
        // runId が "yyyyMMdd-HHmmss-XXXXXX" 形式で辞書順=時系列順になることを利用し、
        // ファイル名の降順（新しい実行が先頭）だけで limit 件に絞り込む。中身を読まずに絞り込める
        // （findAll の安全上限絞り込みと同じヘルパーを再利用。§6 DRY）
        candidates = keepMostRecentByFilename(candidates, limit);
        // 絞り込んだ候補ファイルだけを実際にパースする（全件パースを避けて資源枯渇を防ぐ）。
        // 読み込み・パース・壊れたファイルの読み飛ばしは findAll と共通のヘルパー tryRead に委譲する
        List<ExecutionResult> results = new ArrayList<>();
        for (Path p : candidates) {
            tryRead(p).ifPresent(results::add);
        }
        // ファイル名の降順とstartedAtの降順は通常一致するが、契約どおりの順序を厳密に保証するため
        // 絞り込み済みの少数件（limit 件以下）だけを最後に開始日時の降順で並べ替える
        results.sort(ExecutionResults.BY_STARTED_AT_DESC);
        return results;
    }

    /**
     * Verifies that {@code target} -- the file {@link #save} just finished
     * writing -- actually landed under {@code expectedRealBase}, the base
     * directory's resolved real path captured before the write sequence
     * began, and rejects (deleting the misdirected file first) if not.
     *
     * <p>{@link Files#createTempFile} and {@link Files#move} both re-resolve
     * {@code baseDir} by path rather than through an already-open directory
     * handle, so a symlink swap at any point during the write sequence -- not
     * just one swapped in before it started -- would otherwise go undetected.
     * Comparing real paths after the fact, instead of re-checking {@code
     * isBaseDirSymlink()} before each individual filesystem call in the
     * sequence, covers the whole sequence in one pass: no matter which call
     * the swap happens around, the file's actual final location will not
     * match {@code expectedRealBase}.
     *
     * <p>Package-private, like {@link #keepMostRecentByFilename}, so this
     * check can be unit-tested directly against two independently-constructed
     * real directories, without needing to race an actual filesystem swap
     * into the middle of a single {@link #save} call.
     */
    static void verifyWroteUnderExpectedBase(Path target, Path expectedRealBase, Path baseDir) throws IOException {
        // 実際に書き込んだ場所（target の実体パスの親）が、書き込み開始時に確認した
        // 実体ディレクトリと一致するかを検証する
        if (!target.toRealPath().getParent().equals(expectedRealBase)) {
            // 一致しない場合、シーケンスの途中で baseDir がシンボリックリンクへ差し替えられ、
            // 意図しない場所へ書き込まれたことを意味するため、誤って書き込まれたファイルを
            // 削除したうえで拒否する（fail-closed）
            Files.deleteIfExists(target);
            throw new UncheckedIOException(new IOException(
                    "refusing to use a symlinked state directory: " + baseDir));
        }
    }

    /**
     * Keeps only the {@code ceiling} entries of {@code candidates} with the
     * lexicographically largest filename, relying on the {@code runId}
     * filename format ({@code yyyyMMdd-HHmmss-<hex>}) sorting in chronological
     * order -- so "largest filename" means "most recent". Pure path-string
     * comparison with no filesystem I/O, so it is unit-testable without
     * creating any files. Shared by {@link #findAll} (safety-ceiling
     * truncation) and {@link #findRecent} (limit truncation).
     *
     * <p>When {@code candidates.size() <= ceiling}, the same list reference is
     * returned unmodified (no copy). This is safe only because every current
     * caller passes an unmodifiable list ({@code Stream.toList()}); a future
     * caller passing a mutable list must not rely on the returned list being
     * independent of the input.
     */
    static List<Path> keepMostRecentByFilename(List<Path> candidates, int ceiling) {
        // 既にちょうど収まっている場合はソートせずそのまま返す（無駄な比較を避ける）。
        // 呼び出し元は現状すべて Stream.toList()（変更不可）を渡しているため、参照をそのまま
        // 返しても安全。可変リストを渡す呼び出し元を将来追加する場合はこの前提が崩れる点に注意
        if (candidates.size() <= ceiling) {
            return candidates;
        }
        // ファイル名の降順（新しい実行が先頭）に並べ替えてから先頭 ceiling 件だけを残す
        return candidates.stream()
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .limit(ceiling)
                .toList();
    }

    /**
     * Reads and parses one execution-result JSON file, tolerating a missing,
     * oversized, or unparseable file by logging a warning and returning empty
     * instead of throwing. Shared by {@link #findById}, {@link #findAll}, and
     * {@link #findRecent} so the "skip broken files" contract documented on
     * this class lives in exactly one place.
     */
    private Optional<ExecutionResult> tryRead(Path file) {
        try {
            // Bound the parse the same way BatchConfigLoader bounds config
            // parsing, but do it via a bounded read rather than a
            // stat-then-open size check: a separate Files.size() (or
            // readAttributes) call followed later by Files.newInputStream()
            // has a TOCTOU window between the two filesystem calls in which
            // another process could grow the file past MAX_RECORD_BYTES, and
            // Jackson would then parse the enlarged content in full because
            // nothing bounds the stream itself. Reading at most
            // MAX_RECORD_BYTES + 1 bytes up front makes the actual bytes
            // handed to Jackson the thing that is bounded, so no
            // post-check growth can smuggle an oversized document through.
            // Every caller of tryRead has already confirmed the path is a
            // regular file via NOFOLLOW_LINKS before reaching this method
            // (findById directly, findAll/findRecent via their
            // Files.isRegularFile(..., NOFOLLOW_LINKS) candidate filter).
            // Jackson にファイルを渡す前にサイズを確認する（BatchConfigLoader と同じ
            // 「パース前にサイズを拒否する」防御）。ただし「サイズを確認してから開く」
            // 2 段階の方式だと、確認と読み込みの間（TOCTOU の隙間）に別プロセスが
            // ファイルを肥大化させても、Jackson は肥大化後の中身をそのまま全部
            // パースしてしまう（ストリーム自体には何の上限もかかっていないため）。
            // そこで実際に読む量そのものを MAX_RECORD_BYTES + 1 バイトまでに制限し、
            // 「Jackson に渡すバイト列」自身を上限内に収める設計にすることで、
            // 確認後の肥大化では上限をすり抜けられないようにする。呼び出し元は
            // tryRead に渡す前に NOFOLLOW_LINKS で通常ファイルであることを確認済み
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                // 上限+1バイトまで読み込む（+1は「ちょうど上限」と「上限超過」を区別するため）
                byte[] bytes = in.readNBytes((int) MAX_RECORD_BYTES + 1);
                // 読み込めたバイト数が上限を超えていれば、壊れたファイルと同様に読み飛ばす
                if (bytes.length > MAX_RECORD_BYTES) {
                    LOGGER.warning("Skipping oversized execution result file '" + file + "' (>"
                            + MAX_RECORD_BYTES + " bytes, limit " + MAX_RECORD_BYTES + ")");
                    return Optional.empty();
                }
                // 上限内に収まったバイト列を ExecutionResult に変換し、Optional でラップして返す
                return Optional.of(mapper.readValue(bytes, ExecutionResult.class));
            }
        } catch (IOException e) {
            // パースに失敗した（またはサイズ確認中に消えた）ファイルはスキップして空 Optional を
            // 返す（fail-safe）。クラスの Javadoc が「壊れたファイルは読み飛ばす」と約束しており、
            // 途中書き込みや手動改変で壊れた JSON が 1 件残っていても呼び出し側をクラッシュさせないため。
            LOGGER.warning("Skipping unreadable execution result file '" + file + "': " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolves the file for a run id, guarding against path traversal: a runId
     * is untrusted input (the store is public and accepts externally-built
     * {@link ExecutionResult}s), so a value like {@code "../escape"} must never
     * read or write outside {@code baseDir}.
     */
    private Path fileFor(String runId) {
        // runId にパス区切り文字や ".." が含まれていないか確認する（パストラバーサル攻撃を防ぐ）
        if (runId.contains("/") || runId.contains("\\")
                || runId.contains("..") || runId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "invalid runId '" + runId + "': must not contain path separators or '..'");
        }
        // ベースディレクトリの絶対パスを正規化する（. や .. を解決して一意のパスにする）
        Path base = baseDir.toAbsolutePath().normalize();
        // runId に .json を付けてベースディレクトリ配下のファイルパスに解決する
        Path resolved = base.resolve(runId + SUFFIX).normalize();
        // 解決されたパスの親がベースディレクトリと異なる場合はディレクトリ外への脱出を意味するので例外を投げる
        if (!base.equals(resolved.getParent())) {
            throw new IllegalArgumentException(
                    "invalid runId '" + runId + "': resolves outside the state directory");
        }
        // 安全と確認できたファイルパスを返す
        return resolved;
    }
}
