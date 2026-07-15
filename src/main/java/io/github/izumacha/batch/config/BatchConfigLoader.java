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
import java.nio.charset.StandardCharsets;
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
        // Reject anything that EXISTS but is not a regular file (FIFOs,
        // character devices, directories, ...) before touching its size or
        // contents. Files.size() is not meaningful for special files (e.g. it
        // reports 0 for /dev/zero, silently bypassing the MAX_CONFIG_BYTES
        // guard below), and a FIFO/character device can block or stream
        // unboundedly on read, defeating the "bounded parsing" invariant even
        // though the size check itself passed. Batch config files have no
        // legitimate reason to be anything other than a regular file.
        //
        // The Files.exists(path) guard matters for error-message clarity, not
        // just correctness: Files.isRegularFile(path) alone returns false for
        // BOTH "doesn't exist" and "wrong type", which would collapse a
        // mistyped/missing path into the same "not a regular file" message
        // instead of the clearer NoSuchFileException-derived message the
        // existing IOException handling below already produces. Only reject
        // here when we can positively confirm the path exists and is the
        // wrong type; a missing (or existence-undeterminable, e.g. permission
        // denied on a parent directory) path falls through to Files.size()
        // below, which raises its own descriptive IOException.
        // 存在するのに通常ファイル以外（FIFO・キャラクタデバイス・ディレクトリ等）の場合だけ、
        // サイズ確認や読み込みを行う前に拒否する。Files.size() は特殊ファイルに対して意味を持たず
        // （例えば /dev/zero では 0 を返し、直後の MAX_CONFIG_BYTES チェックをすり抜けてしまう）、
        // FIFO/キャラクタデバイスは読み込み時に無制限にブロック・ストリームしうるため、
        // サイズチェックが通過していても「有界なパース」という不変条件が崩れる。
        // バッチ設定ファイルが通常ファイル以外である正当な理由はない。
        //
        // Files.exists(path) を併用するのはメッセージの分かりやすさのため: isRegularFile() 単独では
        // 「存在しない」と「型が違う」のどちらも false を返し区別できず、パスの typo・存在しない
        // ファイルまで「通常ファイルではない」という誤解を招くメッセージになってしまう。ここでは
        // 「存在するのに型が違う」ことを確認できた場合だけ拒否し、存在しない（または存在確認自体が
        // 権限エラー等で判定不能な）パスは下の Files.size() に委ねて、そちらの分かりやすい
        // IOException 由来メッセージを使わせる。
        if (Files.exists(path) && !Files.isRegularFile(path)) {
            throw new ConfigException("batch config path is not a regular file: " + path);
        }
        // ファイル内容を格納する変数を宣言する
        String content;
        try {
            // Reject oversized files before reading them whole into memory.
            // ファイルをメモリに読む前にサイズを確認して、大きすぎる場合は拒否する
            long size = Files.size(path);
            // ファイルサイズが上限を超えている場合はエラーを投げる(ラベルは呼び出し元のパスそのもの)
            rejectIfOversized(size, path.toString());
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
     * @throws ConfigException if the content is too large or malformed, or
     *                         describes an invalid batch
     */
    public Batch loadFromString(String content) {
        // load(Path) はファイルを丸ごとメモリへ読む前に Files.size() でサイズを
        // 拒否するが、loadFromString は呼び出し側が既に文字列をメモリ上に持っている
        // ため「読む前」の防御はできない。それでも、この後の(再帰下降の)パース処理に
        // 進む前に明示的にサイズを拒否することで、load(Path) と同じ「4 MiB 上限は
        // 必ず先に効く」という契約を保つ。
        //
        // SnakeYAML 側の codePointLimit(コンストラクタで MAX_CONFIG_BYTES と同値に
        // 設定済み)は「コードポイント数」を数えるため、マルチバイト文字(例: 日本語)を
        // 大量に含む文字列だと UTF-8 バイト数は 4 MiB を超えていてもコードポイント数は
        // 上限未満のままで codePointLimit をすり抜けてしまう(この関数がここで
        // 明示チェックする本質的な理由。単にエラーメッセージを分かりやすくするだけの
        // 冗長チェックではない)。
        if (content != null) {
            // まず String.length()(UTF-16 コード単位数)で安価に事前判定する。
            // どの文字も UTF-8 エンコード後のバイト数は UTF-16 コード単位数以上になる
            // (ASCII は 1 対 1、BMP の非ASCIIは 1 単位に2〜3バイト、サロゲートペアは
            // 2 単位に4バイト)ため、length() が既に上限超過なら UTF-8 バイト数も
            // 必ず超過している。極端に巨大な文字列を毎回フルエンコードしてから
            // 判定する無駄(ピークメモリ・CPU の倍加)を避けられる。
            if (content.length() > MAX_CONFIG_BYTES) {
                // 事前判定だけで上限超過と確定できる場合は、正確なバイト数を
                // 数えるためだけにエンコードせず、length() をそのまま報告する
                rejectIfOversized((long) content.length(), "<string>");
            }
            // ファイル版と同じ「4 MiB」の意味に揃えるため、UTF-8 バイト数で正確に比較する
            long sizeBytes = content.getBytes(StandardCharsets.UTF_8).length;
            rejectIfOversized(sizeBytes, "<string>");
        }
        // 文字列を直接パースする（テストや組み込み用）。ソース名は "<string>" とする
        return parse(content, "<string>");
    }

    // load(Path) と loadFromString の両方で使う、サイズ上限超過を拒否する共通処理。
    // label は例外メッセージに表示する識別子(ファイルパス、または文字列入力なら "<string>")。
    private static void rejectIfOversized(long sizeBytes, String label) {
        // サイズが上限を超えていなければ何もしない(正常系)
        if (sizeBytes <= MAX_CONFIG_BYTES) {
            return;
        }
        // 上限を超えている場合は、識別子と実サイズ・上限値を含む ConfigException を投げる
        throw new ConfigException("batch config is too large: " + label
                + " (" + sizeBytes + " bytes, limit " + MAX_CONFIG_BYTES + ")");
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
