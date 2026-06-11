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
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed {@link ExecutionStore} that persists one pretty-printed JSON
 * document per run, named {@code <runId>.json}, under a base directory.
 *
 * <p>Instants are written as ISO-8601 strings (timestamps disabled) and reads
 * tolerate unparseable files by skipping them.
 */
public final class JsonExecutionStore implements ExecutionStore {

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
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            // ファイルを読み込んで ExecutionResult に変換し、Optional でラップして返す
            return Optional.of(mapper.readValue(in, ExecutionResult.class));
        } catch (IOException e) {
            // 読み込みに失敗した場合はチェックなし例外に包んで投げる
            throw new UncheckedIOException(
                    "failed to read execution result '" + runId + "' from " + file, e);
        }
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
                    .forEach(p -> {
                        try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
                            // ファイルを読み込んで ExecutionResult に変換してリストに追加する
                            results.add(mapper.readValue(in, ExecutionResult.class));
                        } catch (IOException ignored) {
                            // Skip files that fail to parse; they may be partial
                            // writes or unrelated documents.
                            // パースに失敗したファイルはスキップする（途中書き込みや無関係なファイルの可能性）
                        }
                    });
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
