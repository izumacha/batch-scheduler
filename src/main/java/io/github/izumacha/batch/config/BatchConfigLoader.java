package io.github.izumacha.batch.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.izumacha.batch.model.Batch;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a {@link Batch} definition from a YAML (or JSON) document.
 *
 * <p>The loader handles parsing and per-field model validation only. The
 * canonical constructors of {@link io.github.izumacha.batch.model.Job} and
 * {@link Batch} enforce light, per-field invariants (e.g. non-blank id,
 * non-negative retries). Cross-job structural validation (duplicate ids,
 * missing dependencies, cycles) is performed elsewhere when the batch is
 * turned into a dependency graph.
 *
 * <p>Any failure to read or parse the document is surfaced as a
 * {@link ConfigException} whose message identifies the offending source.
 */
public final class BatchConfigLoader {

    /** Upper bound on a config document, guarding against memory-exhaustion DoS. */
    // YAML ファイルの最大サイズ（4MiB）。これを超えるファイルは読み込みを拒否してメモリ枯渇を防ぐ
    static final int MAX_CONFIG_BYTES = 4 * 1024 * 1024; // 4 MiB
    // YAML エイリアス（繰り返し参照）の最大数（billion-laughs 攻撃への対策）
    private static final int MAX_YAML_ALIASES = 50;
    // YAML のネスト深度の上限（深すぎる構造による解析遅延を防ぐ）
    private static final int MAX_YAML_NESTING_DEPTH = 100;

    // Jackson の ObjectMapper インスタンス（YAML/JSON 双方のパースに使う）
    private final ObjectMapper mapper;

    public BatchConfigLoader() {
        // Bound the parser so a hostile or accidental "YAML bomb" cannot exhaust
        // memory: cap the document size, the number of aliases (billion-laughs),
        // and nesting depth, and forbid recursive keys.
        // SnakeYAML の制限オプションを作成する（YAML bomb 対策）
        LoaderOptions loaderOptions = new LoaderOptions();
        // コードポイント数の上限を設定する（= ファイルサイズ上限と同じ値）
        loaderOptions.setCodePointLimit(MAX_CONFIG_BYTES);
        // エイリアス（繰り返し参照）の最大数を MAX_YAML_ALIASES に制限する（billion-laughs 攻撃への対策）
        loaderOptions.setMaxAliasesForCollections(MAX_YAML_ALIASES);
        // ネストの深さを MAX_YAML_NESTING_DEPTH に制限する（深すぎると解析が遅くなるので上限を設ける）
        loaderOptions.setNestingDepthLimit(MAX_YAML_NESTING_DEPTH);
        // 再帰的なキー（循環参照）を禁止する
        loaderOptions.setAllowRecursiveKeys(false);
        // 上記オプションを組み込んだ YAMLFactory を生成する
        YAMLFactory yamlFactory = YAMLFactory.builder().loaderOptions(loaderOptions).build();

        // YAML ファクトリを使う ObjectMapper を作り、Java 時刻型モジュールを登録する
        this.mapper = new ObjectMapper(yamlFactory)
                .registerModule(new JavaTimeModule())
                // 未知のフィールドがあっても例外にしない（上位互換性のため）
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // 小数を整数フィールドへ黙って切り捨て変換（coercion）しない。
                // 既定では `timeoutSeconds: 0.9` が 0（= タイムアウト無し）、
                // `retries: 2.9` が 2 に静かに丸められ、意図と異なる実行になるため、
                // 小数値は ConfigException（終了コード 3）として明示的に拒否する
                .configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, false);
    }

    /**
     * Reads and parses the YAML/JSON file at {@code path} into a {@link Batch}.
     *
     * @throws ConfigException if the file is missing, unreadable, malformed, or
     *                         describes an invalid batch
     */
    public Batch load(Path path) {
        // path が null の場合は例外を投げる（ファイルパスは必須）
        if (path == null) {
            throw new ConfigException("config path must not be null");
        }
        // Reject anything that is not a regular file (FIFOs, character devices,
        // directories, ...) before touching its size or contents. Files.size()
        // is not meaningful for special files (e.g. it reports 0 for /dev/zero,
        // silently bypassing the MAX_CONFIG_BYTES guard below), and a
        // FIFO/character device can block or stream unboundedly on read,
        // defeating the "bounded parsing" invariant even though the size check
        // itself passed. Batch config files have no legitimate reason to be
        // anything other than a regular file.
        // 通常ファイル以外（FIFO・キャラクタデバイス・ディレクトリ等）は、サイズ確認や読み込みを
        // 行う前に拒否する。Files.size() は特殊ファイルに対して意味を持たず
        // （例えば /dev/zero では 0 を返し、直後の MAX_CONFIG_BYTES チェックをすり抜けてしまう）、
        // FIFO/キャラクタデバイスは読み込み時に無制限にブロック・ストリームしうるため、
        // サイズチェックが通過していても「有界なパース」という不変条件が崩れる。
        // バッチ設定ファイルが通常ファイル以外である正当な理由はない。
        if (!Files.isRegularFile(path)) {
            throw new ConfigException("batch config path is not a regular file: " + path);
        }
        // ファイル内容を格納する変数を宣言する
        String content;
        try {
            // Reject oversized files before reading them whole into memory.
            // ファイルをメモリに読む前にサイズを確認して、大きすぎる場合は拒否する
            long size = Files.size(path);
            // ファイルサイズが上限を超えている場合はエラーを投げる
            if (size > MAX_CONFIG_BYTES) {
                throw new ConfigException("batch config file is too large: " + path
                        + " (" + size + " bytes, limit " + MAX_CONFIG_BYTES + ")");
            }
            // ファイル全体を文字列として読み込む
            content = Files.readString(path);
        } catch (IOException e) {
            // ファイルが見つからない・読めないなどの IO エラーは ConfigException に包んで投げる
            throw new ConfigException(
                    "failed to read batch config file: " + path + " (" + e.getMessage() + ")", e);
        }
        // 読み込んだ内容をパースして Batch オブジェクトに変換する
        return parse(content, path.toString());
    }

    /**
     * Parses YAML/JSON text directly into a {@link Batch}. Useful for tests.
     *
     * @throws ConfigException if the content is malformed or describes an
     *                         invalid batch
     */
    public Batch loadFromString(String content) {
        // 文字列を直接パースする（テストや組み込み用）。ソース名は "<string>" とする
        return parse(content, "<string>");
    }

    private Batch parse(String content, String source) {
        // 内容が null または空白だけの場合はエラーを投げる
        if (content == null || content.isBlank()) {
            throw new ConfigException("batch config is empty: " + source);
        }
        // パース結果を格納する変数を宣言する
        Batch batch;
        try {
            // YAML/JSON 文字列を Batch クラスにデシリアライズする
            batch = mapper.readValue(content, Batch.class);
        } catch (ValueInstantiationException e) {
            // Jackson wraps exceptions thrown from a record's canonical
            // constructor (e.g. IllegalArgumentException for a blank id or
            // negative retries) in a ValueInstantiationException. Surface the
            // underlying validation message together with the source.
            // レコードのコンストラクタで投げられた例外（例: id が空白）をラップした例外を処理する
            String detail = rootMessage(e);
            // 根本原因のメッセージを取り出して ConfigException に包み直す
            throw new ConfigException(
                    "invalid batch config: " + source + " (" + detail + ")", e);
        } catch (JsonMappingException e) {
            // JSON/YAML の構造上のマッピングエラー（例: 型の不一致）を処理する
            String detail = rootMessage(e);
            // 根本原因のメッセージを取り出して ConfigException に包み直す
            throw new ConfigException(
                    "invalid batch config: " + source + " (" + detail + ")", e);
        } catch (IOException e) {
            // その他の IO エラーを ConfigException に包んで投げる
            throw new ConfigException(
                    "failed to parse batch config: " + source + " (" + rootMessage(e) + ")", e);
        }
        // パース結果が null の場合（例: 空の YAML ドキュメント）はエラーを投げる
        if (batch == null) {
            throw new ConfigException("batch config is empty: " + source);
        }
        // 正常にパースできた Batch オブジェクトを返す
        return batch;
    }

    private static String rootMessage(Throwable t) {
        // 例外の原因チェーンを辿って最も根本的な原因を見つける
        Throwable cause = t;
        // 原因チェーンを末端まで辿る（無限ループにならないよう自己参照も除外する）
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        // 最終的な例外のメッセージを取り出す
        String message = cause.getMessage();
        // メッセージが null または空白の場合は例外の toString() を返す
        return (message == null || message.isBlank()) ? cause.toString() : message;
    }
}
