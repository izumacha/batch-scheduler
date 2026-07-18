package io.github.izumacha.batch.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.izumacha.batch.model.Batch;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

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
    /**
     * A second, independent SnakeYAML loader used only to enforce the alias-count
     * and nesting-depth bounds below in {@link #enforceYamlSafetyLimits}, because
     * {@link #mapper} alone does not enforce them despite sharing the same
     * {@link LoaderOptions} (see that field's construction below and
     * {@link #enforceYamlSafetyLimits} for why).
     */
    // 「billion-laughs」エイリアス爆弾・過剰ネストを検出する専用の SnakeYAML ローダー
    // （mapper とは別インスタンス。理由は下の enforceYamlSafetyLimits を参照）
    private final Yaml boundsGuard;

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
                .configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, false)
                // 同一マップ内の重複キーを「後勝ち」で黙って採用しない。既定では
                // ジョブ内に `dependsOn:` が 2 回書かれると（コピペや衝突マージの典型）
                // 先の宣言が静かに消え、依存関係や command が意図せず差し替わったまま
                // `validate` も OK を返してしまう。重複キーはパースエラー
                // （ConfigException / 終了コード 3）として明示的に拒否する（§9 入力は信用しない）。
                // なお SnakeYAML 側の setAllowDuplicateKeys(false) は Composer/Constructor
                // 経由でしか効かず、jackson-dataformat-yaml のパース経路では機能しないため、
                // Jackson のストリーム層で検出できる本フラグを使う
                .configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);

        // jackson-dataformat-yaml の YAMLParser は SnakeYAML の Scanner/Parser の
        // イベント列を直接 Jackson のトークンへ橋渡ししており、maxAliasesForCollections /
        // nestingDepthLimit を実際にチェックする SnakeYAML の Composer を一切経由しない
        // （SnakeYAML 2.3 のバイトコードで確認済み: 両オプションを参照するのは
        // Composer クラスだけで、jackson-dataformat-yaml のクラスからは一切参照されない）。
        // そのため上の mapper に同じ loaderOptions を渡しても、この 2 つの上限は
        // 事実上ザル（何も拒否しない）になっており、DESIGN.md が謳う「billion-laughs
        // エイリアス爆弾対策」「ネスト深度対策」は実際には効いていなかった
        // （codePointLimit だけは Scanner 側で効くため機能する）。
        // 対策として、実際に Composer を通す素の SnakeYAML ローダーをもう一つ用意し、
        // parse() の中で本パースの前に「読み捨てるだけの検証パス」として compose() させることで
        // 上限超過を検出する（compose() は値構築を行わないため enforceYamlSafetyLimits を参照）。
        // SafeConstructor を渡しているのは多層防御: compose() 自体は constructor を使わないが、
        // 将来 load() 系へ書き換えられた場合でも危険な !!java.* タグ経由の
        // 任意 Java オブジェクト構築（deserialization gadget）が起きないようにしておく
        // （§9 危険な実行・安全でない解析を避ける: YAML は safe_load 相当を使う）。
        this.boundsGuard = new Yaml(new SafeConstructor(loaderOptions));
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
        // mapper に読ませる前に、alias 爆弾・過剰ネストを検出する専用パスを通す
        // （mapper 自身はこれらを検出できないため。boundsGuard フィールドの説明を参照）
        enforceYamlSafetyLimits(content, source);
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

    /**
     * Composes {@code content} with a plain SnakeYAML {@link Yaml} (backed by
     * the same {@link LoaderOptions} as {@link #mapper}, so the same limits
     * apply) purely to enforce the alias-count and nesting-depth bounds; the
     * resulting node tree is discarded, {@link #mapper} still performs the real
     * structural parse into {@link Batch} right after this call returns. Using
     * {@code compose()} rather than {@code load()} deliberately stops at the
     * Composer: constructing Java values (SnakeYAML's constructor stage) can
     * throw non-{@link YAMLException} runtime exceptions (e.g. an
     * {@link IllegalArgumentException} from invalid {@code !!binary} Base64)
     * that would escape the catch below without the source-labelled
     * {@link ConfigException} wrapping; the real parse's own error reporting
     * covers such documents instead.
     *
     * <p>This pre-pass exists because {@code mapper} alone does not enforce
     * those two bounds: jackson-dataformat-yaml's {@code YAMLParser} drives
     * SnakeYAML's {@code Scanner}/{@code Parser} event stream directly and
     * never builds nodes through SnakeYAML's {@code Composer}, which is the
     * only class that reads
     * {@link LoaderOptions#getMaxAliasesForCollections()} and
     * {@link LoaderOptions#getNestingDepthLimit()} (confirmed against the
     * SnakeYAML 2.3 bytecode: neither getter is referenced from any
     * jackson-dataformat-yaml class). Passing the same {@code loaderOptions} to
     * {@code mapper}'s {@code YAMLFactory} therefore silently does nothing for
     * these two guards. (In practice the Jackson pipeline also never expands
     * aliases into collections, so it is this pre-pass -- not
     * {@code mapper.readValue} -- that would materialize an aliased document;
     * the point of the guard is to enforce the documented limits in the one
     * place that does compose the tree, and to keep them enforced if the
     * parsing pipeline ever changes.) {@link LoaderOptions#getCodePointLimit()}
     * is unaffected by this gap: it is enforced earlier, in SnakeYAML's
     * {@code StreamReader}, which the Jackson parser does use.
     *
     * @throws ConfigException if the document exceeds the alias-count or
     *                         nesting-depth limit
     */
    private void enforceYamlSafetyLimits(String content, String source) {
        try {
            // 戻り値は使わない（爆弾なら Composer が例外を投げて即座に打ち切られることだけが目的）。
            // load() ではなく compose() を使うのは、上限を検査する Composer だけを通し、
            // SafeConstructor による Java 値の構築を一切行わないため。load() だと構築段階が
            // 投げる非 YAMLException 系の実行時例外（例: 不正な !!binary の Base64 が投げる
            // IllegalArgumentException）が下の catch を素通りし、ソース名を含む
            // ConfigException に包まれないまま CLI の最終防波堤まで漏れてしまう
            boundsGuard.compose(new java.io.StringReader(content));
        } catch (YAMLException e) {
            // このガードが検出すべきなのは alias 爆弾・過剰ネストの 2 種類だけであり、
            // それ以外（例えば単なる文法エラー）まで拾ってしまうと、mapper.readValue が
            // 本来出すはずの分かりやすいエラーメッセージ（例: "failed to parse batch
            // config: ..."）を「safety limits」という見当違いのメッセージで覆い隠して
            // しまう。SnakeYAML の Scanner/Parser が投げる文法エラー系の例外は
            // ScannerException/ParserException 等の YAMLException のサブクラスであり、
            // ここでは isSafetyLimitViolation で「本当にこの 2 種類の上限超過か」を
            // メッセージ文言で見分ける（Composer が直接 `new YAMLException(...)` する
            // 数少ない箇所のうちの 2 つ）。一致しなければ何もせず処理を続け、直後の
            // mapper.readValue に本来のエラー報告を任せる（そちらも同じ文法エラーに
            // 遭遇して ConfigException を投げるため、ここで握り潰しても未報告にはならない）
            if (isSafetyLimitViolation(e)) {
                // billion-laughs 相当のエイリアス爆弾・過剰なネストを検出した場合は
                // ConfigException に包んで投げる（終了コード 3、スタックトレースは出さない）
                throw new ConfigException(
                        "batch config exceeds YAML safety limits: " + source + " (" + rootMessage(e) + ")", e);
            }
        }
    }

    /**
     * Whether {@code e} is specifically the alias-count or nesting-depth guard
     * tripping (as opposed to any other {@link YAMLException} subtype, e.g. a
     * plain syntax error). SnakeYAML's {@code Composer} throws the exact base
     * {@link YAMLException} class directly (not a subclass) at exactly two call
     * sites for these two checks, with these two literal message prefixes
     * (confirmed against the SnakeYAML 2.3 bytecode); everything else --
     * {@code ScannerException}, {@code ParserException}, {@code
     * ComposerException}, etc. -- is a distinct subclass carrying an unrelated
     * problem. Matching by message text is admittedly coupled to SnakeYAML's
     * literal strings; if a future SnakeYAML upgrade changes them, the worst
     * case is this guard silently stops firing for real bombs again (falling
     * back to today's already-broken behavior), not a false rejection of valid
     * documents. {@code BatchConfigLoaderTest#yamlAliasBombIsRejected} and
     * {@code #yamlDeepNestingBombIsRejected} exist to catch that regression
     * (e.g. on a SnakeYAML version bump) in CI.
     */
    private static boolean isSafetyLimitViolation(YAMLException e) {
        // メッセージ本文を取り出す（null の可能性もあるため先に確認する）
        String message = e.getMessage();
        // メッセージが null なら判定不能なので false（安全側＝この例外は無視して mapper に委ねる）
        if (message == null) {
            return false;
        }
        // alias 上限超過、またはネスト深度上限超過のどちらかのメッセージ文言に一致するかを確認する
        return message.contains("exceeds the specified max") || message.contains("Nesting Depth exceeded max");
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
