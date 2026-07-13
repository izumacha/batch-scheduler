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
 * tolerate unparseable files by skipping them.
 */
public final class JsonExecutionStore implements ExecutionStore {

    // このクラスのログ出力用 Logger（ファイル読み込み失敗時の警告に使う）
    private static final Logger LOGGER = Logger.getLogger(JsonExecutionStore.class.getName());

    // JSON ファイルの拡張子（runId.json という形式で保存する）
    private static final String SUFFIX = ".json";
    /** Fixed temp-file prefix; must be >= 3 chars for {@link Files#createTempFile}. */
    // 一時ファイルのプレフィックス（createTempFile は 3 文字以上必要）
    private static final String TMP_PREFIX = "run-";

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
        try {
            // ベースディレクトリが存在しない場合は再帰的に作成する（同時実行でも安全）
            Files.createDirectories(baseDir);
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
        // ベースディレクトリが存在しない場合は空リストを返す
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        // 読み込んだ実行結果を蓄積するリストを作成する
        List<ExecutionResult> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(baseDir)) {
            // ベースディレクトリ内のファイルをフィルタリングして処理する
            files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) // シンボリックリンクを除外する
                    .filter(p -> p.getFileName().toString().endsWith(SUFFIX))        // .json 拡張子のファイルのみ対象にする
                    // 読み込み・パース・壊れたファイルの読み飛ばしは共通ヘルパー tryRead に委譲する
                    .forEach(p -> tryRead(p).ifPresent(results::add));
        } catch (IOException e) {
            // ディレクトリ一覧の取得に失敗した場合はチェックなし例外に包んで投げる
            throw new UncheckedIOException(
                    "failed to list execution results under " + baseDir, e);
        }
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
        // ベースディレクトリが存在しない場合は空リストを返す
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        // 中身をパースせず、ファイル名だけを集めて絞り込む候補パスのリスト
        List<Path> candidates;
        try (Stream<Path> files = Files.list(baseDir)) {
            candidates = files
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) // シンボリックリンクを除外する
                    .filter(p -> p.getFileName().toString().endsWith(SUFFIX))        // .json 拡張子のファイルのみ対象にする
                    // runId が "yyyyMMdd-HHmmss-XXXXXX" 形式で辞書順=時系列順になることを利用し、
                    // ファイル名の降順（新しい実行が先頭）だけで並べ替える。中身を読まずに絞り込める
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    // 降順に並んだ先頭から limit 件だけを候補として残す
                    .limit(limit)
                    .toList();
        } catch (IOException e) {
            // ディレクトリ一覧の取得に失敗した場合はチェックなし例外に包んで投げる
            throw new UncheckedIOException(
                    "failed to list execution results under " + baseDir, e);
        }
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
     * Reads and parses one execution-result JSON file, tolerating a missing
     * or unparseable file by logging a warning and returning empty instead of
     * throwing. Shared by {@link #findById}, {@link #findAll}, and
     * {@link #findRecent} so the "skip broken files" contract documented on
     * this class lives in exactly one place.
     */
    private Optional<ExecutionResult> tryRead(Path file) {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            // ファイルを読み込んで ExecutionResult に変換し、Optional でラップして返す
            return Optional.of(mapper.readValue(in, ExecutionResult.class));
        } catch (IOException e) {
            // パースに失敗したファイルはスキップして空 Optional を返す（fail-safe）。
            // クラスの Javadoc が「壊れたファイルは読み飛ばす」と約束しており、途中書き込みや
            // 手動改変で壊れた JSON が 1 件残っていても呼び出し側をクラッシュさせないため。
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
