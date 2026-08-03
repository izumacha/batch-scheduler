package io.github.izumacha.batch.config;

import io.github.izumacha.batch.model.Batch;
import io.github.izumacha.batch.model.Job;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchConfigLoaderTest {

    private final BatchConfigLoader loader = new BatchConfigLoader();

    // 未対応 YAML 機能（アンカー・エイリアス・マージキー）の拒否メッセージ冒頭。
    // 各テストが同じリテラルを書き写さないよう 1 箇所に定数化する（§6 DRY）
    private static final String UNSUPPORTED_FEATURE_PREFIX = "unsupported YAML feature:";

    private static final String VALID_YAML = """
            name: etl
            jobs:
              - id: extract
                command: ["sh", "-c", "echo extract"]
              - id: transform
                command: ["sh", "-c", "echo transform"]
                dependsOn: [extract]
                retries: 2
                timeoutSeconds: 30
              - id: load
                command: ["sh", "-c", "echo load"]
                dependsOn: [transform]
            """;

    @Test
    void parsesValidYaml() {
        Batch batch = loader.loadFromString(VALID_YAML);

        assertEquals("etl", batch.name());
        assertEquals(3, batch.jobs().size());

        Job extract = batch.job("extract").orElseThrow();
        // name defaults to id when not provided
        assertEquals("extract", extract.name());
        assertEquals(0, extract.retries());
        assertEquals(0L, extract.timeoutSeconds());
        assertTrue(extract.dependsOn().isEmpty());
        assertEquals(3, extract.command().size());

        Job transform = batch.job("transform").orElseThrow();
        assertEquals(2, transform.retries());
        assertEquals(30L, transform.timeoutSeconds());
        assertEquals(java.util.List.of("extract"), transform.dependsOn());

        Job load = batch.job("load").orElseThrow();
        assertEquals(java.util.List.of("transform"), load.dependsOn());
    }

    @Test
    void parsesJsonContent() {
        String json = """
                {"name":"j","jobs":[{"id":"a","command":["sh","-c","echo a"]}]}
                """;
        Batch batch = loader.loadFromString(json);
        assertEquals("j", batch.name());
        assertEquals(1, batch.jobs().size());
        assertEquals("a", batch.jobs().get(0).id());
    }

    @Test
    void blankIdSurfacesConfigException() {
        String yaml = """
                name: bad
                jobs:
                  - id: ""
                    command: ["sh", "-c", "echo x"]
                """;
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        assertTrue(ex.getMessage().contains("job id is required"),
                "message should mention the validation issue, was: " + ex.getMessage());
    }

    @Test
    void negativeRetriesSurfacesConfigException() {
        String yaml = """
                name: bad
                jobs:
                  - id: a
                    command: ["sh", "-c", "echo x"]
                    retries: -1
                """;
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        assertTrue(ex.getMessage().contains("retries must be between 0 and"),
                "message should mention the validation issue, was: " + ex.getMessage());
    }

    @Test
    void overLimitRetriesSurfacesConfigException() {
        // retries が上限 MAX_RETRIES を超える値だと maxAttempts() が桁あふれし
        // ジョブが 1 度も実行されなくなるため、設定読み込み時点で拒否されることを確認する
        String yaml = """
                name: bad
                jobs:
                  - id: a
                    command: ["sh", "-c", "echo x"]
                    retries: 2147483647
                """;
        // 上限超過の retries は ConfigException として表面化するはず
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        // エラーメッセージが範囲チェックの内容を含むことを検証する
        assertTrue(ex.getMessage().contains("retries must be between 0 and"),
                "message should mention the validation issue, was: " + ex.getMessage());
    }

    @Test
    void fractionalTimeoutSecondsSurfacesConfigException() {
        // 既定の Jackson は小数を整数へ切り捨て変換するため、timeoutSeconds: 0.9 が
        // 0（= タイムアウト無し）に化けてしまう。coercion を無効化した結果、
        // 小数のタイムアウト指定が ConfigException として明示的に拒否されることを確認する
        String yaml = """
                name: bad
                jobs:
                  - id: a
                    command: ["sh", "-c", "echo x"]
                    timeoutSeconds: 0.9
                """;
        // 小数の timeoutSeconds は ConfigException として表面化するはず
        assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
    }

    @Test
    void fractionalRetriesSurfacesConfigException() {
        // retries: 2.9 が 2 に静かに切り捨てられると利用者の意図（3 回近いリトライ）と
        // 異なる実行になるため、小数のリトライ指定が拒否されることを確認する
        String yaml = """
                name: bad
                jobs:
                  - id: a
                    command: ["sh", "-c", "echo x"]
                    retries: 2.9
                """;
        // 小数の retries は ConfigException として表面化するはず
        assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
    }

    @Test
    void nullTimeoutSecondsSurfacesConfigException() {
        // 値を書き忘れた `timeoutSeconds:`（YAML では null）が既定の Jackson では
        // 0（= タイムアウト無し）へ静かに化け、無制限実行という危険側に倒れてしまう。
        // null→0 変換を無効化した結果、明示的な null が ConfigException として
        // 拒否されることを確認する
        String yaml = """
                name: bad
                jobs:
                  - id: a
                    command: ["sh", "-c", "echo x"]
                    timeoutSeconds:
                """;
        // 値の無い timeoutSeconds は ConfigException として表面化するはず
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        // エラーメッセージが「null を数値フィールドに入れられない」ことと
        // 対象フィールド名の両方を含み、利用者が原因箇所を特定できることを検証する
        assertTrue(ex.getMessage().contains("null") && ex.getMessage().contains("timeoutSeconds"),
                "message should mention the null value and the field, was: " + ex.getMessage());
    }

    @Test
    void nullRetriesSurfacesConfigException() {
        // `retries:` に値を書き忘れると既定では 0（= リトライ無し）へ静かに化け、
        // 利用者の意図と異なる実行になるため、明示的な null が拒否されることを確認する
        String yaml = """
                name: bad
                jobs:
                  - id: a
                    command: ["sh", "-c", "echo x"]
                    retries:
                """;
        // 値の無い retries は ConfigException として表面化するはず
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        // エラーメッセージから対象フィールドが分かることを検証する
        assertTrue(ex.getMessage().contains("null") && ex.getMessage().contains("retries"),
                "message should mention the null value and the field, was: " + ex.getMessage());
    }

    @Test
    void duplicateDependsOnKeyWithinJobSurfacesConfigException() {
        // A job block declaring dependsOn twice (typical of a bad merge or
        // copy-paste) must be rejected instead of silently keeping only the
        // last value: last-wins would erase the declared ordering constraint,
        // `validate` would report OK, and `run` would execute the job before
        // its prerequisite.
        String yaml = """
                name: etl
                jobs:
                  - id: a
                    command: ["sh", "-c", "echo a"]
                  - id: b
                    command: ["sh", "-c", "echo b"]
                    dependsOn: [a]
                    dependsOn: []
                """;
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        assertTrue(ex.getMessage().contains("Duplicate"),
                "duplicate keys must be reported explicitly, was: " + ex.getMessage());
    }

    @Test
    void duplicateCommandKeyWithinJobSurfacesConfigException() {
        // A duplicated command key must not silently replace the first
        // command with the second one (the job would run something the
        // author believed they had overridden away).
        String yaml = """
                name: etl
                jobs:
                  - id: a
                    command: ["sh", "-c", "echo right"]
                    command: ["sh", "-c", "echo wrong"]
                """;
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        assertTrue(ex.getMessage().contains("Duplicate"),
                "duplicate keys must be reported explicitly, was: " + ex.getMessage());
    }

    @Test
    void multiDocumentYamlSurfacesConfigException() {
        // jackson-dataformat-yaml は既定で `---` 区切りの 2 つ目以降のドキュメントを
        // 黙って読み飛ばすため、「正しい 1 つ目＋2 つ目」のファイルでも `validate` が
        // OK（終了コード 0）を返し、`run` は先頭ドキュメントのジョブだけを実行して
        // しまう（サイレントなジョブ喪失）。FAIL_ON_TRAILING_TOKENS の有効化により、
        // 複数ドキュメントの YAML が ConfigException（終了コード 3）として明示的に
        // 拒否されることを確認する
        String yaml = """
                name: first
                jobs:
                  - id: a
                    command: ["sh", "-c", "echo a"]
                ---
                name: second
                jobs:
                  - id: b
                    command: ["sh", "-c", "echo b"]
                """;
        // 複数ドキュメントの YAML は ConfigException として表面化するはず
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        // 他のローダー拒否テストと同様に、設定エラー経路（"invalid batch config"）で
        // 報告されることをメッセージ文言で検証する
        assertTrue(ex.getMessage().contains("invalid batch config"),
                "multi-document YAML must be rejected explicitly, was: " + ex.getMessage());
    }

    @Test
    void invalidBinaryScalarDoesNotEscapeAsRawRuntimeException() {
        // Regression guard for the boundsGuard pre-pass: with load() instead of
        // compose(), SnakeYAML's value-construction stage would throw a raw
        // IllegalArgumentException (invalid !!binary Base64) that escapes the
        // YAMLException catch and loses the source-labelled ConfigException
        // wrapping. Whatever the real parse decides about this document, no
        // raw runtime exception may leak out of the loader.
        String yaml = """
                name: !!binary "not*valid*base64!!"
                jobs:
                  - id: a
                    command: ["sh", "-c", "echo a"]
                """;
        try {
            // Loading may succeed or fail depending on how the real parser
            // treats the binary scalar; both outcomes satisfy this contract.
            loader.loadFromString(yaml);
        } catch (ConfigException expected) {
            // If it fails, it must be the loader's own wrapped exception,
            // carrying the source label for diagnosis.
            assertTrue(expected.getMessage().contains("<string>"), expected.getMessage());
        }
        // Any other runtime exception (e.g. IllegalArgumentException from
        // Base64 decoding) propagates out of the try above and fails the test.
    }

    @Test
    void malformedYamlSurfacesConfigException() {
        String yaml = "name: etl\njobs: [oops: : :";
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        // Regression guard: enforceYamlSafetyLimits' pre-parse pass (added to
        // catch alias-bomb/deep-nesting documents, see yamlAliasBombIsRejected)
        // must not swallow this plain syntax error and report it as a
        // misleading "safety limits" violation instead of the real problem.
        assertFalse(ex.getMessage().contains("safety limits"),
                "a plain syntax error must not be reported as a safety-limit violation, was: "
                        + ex.getMessage());
        assertTrue(ex.getMessage().contains("invalid batch config"),
                "message should explain the parse failure, was: " + ex.getMessage());
    }

    @Test
    void emptyContentSurfacesConfigException() {
        assertThrows(ConfigException.class, () -> loader.loadFromString("   "));
    }

    @Test
    void loadsFromFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("batch.yaml");
        Files.writeString(file, VALID_YAML);

        Batch batch = loader.load(file);
        assertNotNull(batch);
        assertEquals(3, batch.jobs().size());
    }

    @Test
    void missingFileThrowsConfigExceptionWithPath(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.yaml");
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.load(missing));
        assertTrue(ex.getMessage().contains(missing.toString()),
                "message should contain the path, was: " + ex.getMessage());
        // A missing (mistyped) path must not be reported as "not a regular file" --
        // that message is reserved for a path that exists but is the wrong type
        // (see nonRegularFilePathIsRejected). Conflating the two would make a
        // simple typo look like a special-file rejection.
        assertFalse(ex.getMessage().contains("not a regular file"),
                "missing-file message should be distinct from the wrong-type message, was: "
                        + ex.getMessage());
    }

    @Test
    void invalidFileThrowsConfigExceptionWithPath(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad.yaml");
        Files.writeString(file, """
                name: bad
                jobs:
                  - id: ""
                    command: ["sh"]
                """);
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.load(file));
        assertTrue(ex.getMessage().contains(file.toString()),
                "message should contain the path, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("job id is required"),
                "message should mention the validation issue, was: " + ex.getMessage());
    }

    @Test
    void aliasInValuePositionIsRejectedNotSilentlyCorrupted() {
        // jackson-dataformat-yaml は SnakeYAML の Composer（エイリアス解決段階）を
        // 通さないため、値の位置の `*base` が黙って文字列 "base" に化け、ジョブが
        // アンカーの参照先とは別の（間違った）コマンドを実行してしまっていた。
        // エイリアスを含むドキュメントが ConfigException（終了コード 3）として
        // 明示的に拒否されることを確認する
        String yaml = """
                name: etl
                defaults: &base ["sh", "-c", "echo real"]
                jobs:
                  - id: a
                    command: *base
                """;
        // エイリアス入りの YAML は ConfigException として表面化するはず
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        // エラーメッセージが「未対応」であることとソース名を含み、原因を特定できることを検証する
        assertTrue(ex.getMessage().contains(UNSUPPORTED_FEATURE_PREFIX) && ex.getMessage().contains("<string>"),
                "alias must be rejected with the unsupported-feature message, was: " + ex.getMessage());
    }

    @Test
    void anchorIsRejectedEvenWithoutAlias() {
        // アンカー（&name）はエイリアスの参照先であり、mapper はアンカーも解決しない。
        // エイリアス側だけ拒否しても片手落ちになるため、アンカー単独のドキュメントも
        // 拒否されることを確認する
        String yaml = """
                name: etl
                jobs:
                  - id: a
                    command: &cmd ["sh", "-c", "echo a"]
                """;
        // アンカー入りの YAML は ConfigException として表面化するはず
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        // エラーメッセージが「未対応」とアンカー名を含むことを検証する
        assertTrue(ex.getMessage().contains(UNSUPPORTED_FEATURE_PREFIX) && ex.getMessage().contains("cmd"),
                "anchor must be rejected with the unsupported-feature message, was: " + ex.getMessage());
    }

    @Test
    void undefinedAliasIsRejectedNotDegradedToLiteralString() {
        // アンカーが定義されていないエイリアスは compose() では ComposerException になる
        // （safety-limit ガードは意図的に無視する）が、mapper はそれでも `*x` を
        // 文字列 "x" に化けさせてしまう。イベントレベルの走査によって、未定義の
        // エイリアスも漏れなく拒否されることを確認する
        String yaml = """
                name: etl
                jobs:
                  - id: a
                    command: ["sh", "-c", *missing]
                """;
        // 未定義エイリアス入りの YAML も ConfigException として表面化するはず
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        // エラーメッセージが「未対応」を含むことを検証する
        assertTrue(ex.getMessage().contains(UNSUPPORTED_FEATURE_PREFIX),
                "a dangling alias must be rejected, was: " + ex.getMessage());
    }

    @Test
    void mergeKeyIsRejectedNotSilentlyDropped() {
        // `<<: *defaults` のマージキーは mapper では未知フィールド "<<" として扱われ、
        // FAIL_ON_UNKNOWN_PROPERTIES=false / @JsonIgnoreProperties が黙って読み捨てる
        // （retries/timeoutSeconds が静かに失われても `validate` は OK を返す）。
        // マージキーを含むドキュメントが拒否されることを確認する
        String yaml = """
                name: etl
                defaults: &defaults
                  retries: 2
                  timeoutSeconds: 30
                jobs:
                  - id: a
                    command: ["sh", "-c", "echo a"]
                    <<: *defaults
                """;
        // マージキー入りの YAML は ConfigException として表面化するはず
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        // エラーメッセージが「未対応」を含むことを検証する（アンカーが先に検出されても
        // マージキーが先に検出されても、どちらも同じ未対応機能エラーになる）
        assertTrue(ex.getMessage().contains(UNSUPPORTED_FEATURE_PREFIX),
                "a merge key must be rejected, was: " + ex.getMessage());
    }

    @Test
    void bareMergeKeyWithoutAnchorIsRejected() {
        // アンカー・エイリアスを一切使わない裸のマージキー（`<<:` にインラインの
        // マップを渡す形）も、mapper では同様に黙って読み捨てられるため拒否される
        // ことを確認する（アンカー検出に依存しないマージキー単独の検出経路の検証）
        String yaml = """
                name: etl
                jobs:
                  - id: a
                    command: ["sh", "-c", "echo a"]
                    <<: {retries: 2}
                """;
        // 裸のマージキー入りの YAML も ConfigException として表面化するはず
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        // エラーメッセージが「未対応」とマージキーの表記を含むことを検証する
        assertTrue(ex.getMessage().contains(UNSUPPORTED_FEATURE_PREFIX) && ex.getMessage().contains("<<"),
                "a bare merge key must be rejected, was: " + ex.getMessage());
    }

    @Test
    void unsupportedFeatureMessageIsAsciiOnlySoItSurvivesAnyLocale() {
        // 回帰防止: このメッセージは以前 日本語だったため、LANG 未設定の C/POSIX ロケール
        // （Docker の JDK ベースイメージや CI コンテナの既定）では System.err の符号化が
        // US-ASCII になり、"error: YAML ????? &a ??????: ..." のように機能名も対処方法も
        // 読めない状態で表示されていた。CLI 自身の文言を ASCII に保つことでロケールに
        // 依存せず読めることを保証する（BatchConfigLoader#unsupportedYamlFeatureMessage 参照）
        String yaml = """
                name: etl
                jobs:
                  - id: a
                    command: &cmd ["sh", "-c", "echo a"]
                """;
        // アンカー入りの YAML を読み込ませて拒否メッセージを取り出す
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(yaml));
        // メッセージ本文を取り出す
        String message = ex.getMessage();
        // US-ASCII で符号化できる文字だけで構成されていることを検証する
        // （1 文字でも非 ASCII が混じると US-ASCII 出力時に "?" へ潰れて情報が失われる）
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(message),
                "message must stay ASCII-only so it is readable under a non-UTF-8 locale, was: "
                        + message);
    }

    @Test
    void quotedMergeKeyLookalikeStringIsStillAllowed() {
        // クォート付きの "<<" はマージキーではなく通常の文字列。コマンド引数として
        // 正当に使われうる（例: ヒアドキュメント風の引数）ため、拒否されずに
        // そのまま値として読み込まれることを確認する（偽陽性の回帰防止）
        String yaml = """
                name: etl
                jobs:
                  - id: a
                    command: ["sh", "-c", "cat", "<<"]
                """;
        // クォート済みの "<<" を含む YAML は正常に読み込めるはず
        Batch batch = loader.loadFromString(yaml);
        // コマンド引数として "<<" がそのまま保持されていることを検証する
        assertEquals("<<", batch.jobs().get(0).command().get(3));
    }

    @Test
    void plainDocumentWithoutAnchorsStillParses() {
        // アンカー・エイリアス・マージキーを使わない従来どおりのドキュメントが、
        // 新しい未対応機能ガードの巻き添えで拒否されないことを確認する（回帰防止）
        Batch batch = loader.loadFromString(VALID_YAML);
        // 3 つのジョブがそのまま読み込まれることを検証する
        assertEquals(3, batch.jobs().size());
    }

    @Test
    void yamlAliasBombIsRejected() {
        // Classic "billion laughs": many aliases to one anchor inside a single
        // collection. `jobs: []` stays structurally valid so the thrown
        // exception can only come from the alias-count guard itself, not an
        // incidental type mismatch (the previous version of this test put the
        // bomb directly on `jobs`, which happened to fail Jackson's structural
        // mapping of `jobs` regardless of whether the alias limit was ever
        // consulted -- it passed for the wrong reason and would not have
        // caught a regression where the limit stopped being enforced).
        StringBuilder bomb = new StringBuilder("name: bomb\njobs: []\nbase: &base [\"x\"]\nmany: [");
        // 60 aliases exceeds BatchConfigLoader's alias-count limit (50).
        for (int i = 0; i < 60; i++) {
            bomb.append("*base,");
        }
        bomb.append("]\n");
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(bomb.toString()));
        assertTrue(ex.getMessage().contains("safety limits"),
                "message should explain the alias-count rejection, was: " + ex.getMessage());
    }

    @Test
    void yamlDeepNestingBombIsRejected() {
        // Unbounded nesting depth is a separate resource-exhaustion vector from
        // the alias count above (deep recursive descent rather than exponential
        // blow-up from a handful of anchors). The document stays tiny in bytes
        // -- only the structural depth is excessive -- so this must be rejected
        // by the nesting-depth guard specifically, not the size guard.
        StringBuilder deep = new StringBuilder("name: bomb\njobs: []\nnested:\n");
        for (int i = 0; i < 500; i++) {
            deep.append("  ".repeat(i + 1)).append("a:\n");
        }
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(deep.toString()));
        assertTrue(ex.getMessage().contains("safety limits"),
                "message should explain the nesting-depth rejection, was: " + ex.getMessage());
    }

    @Test
    void oversizedConfigFileIsRejected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("huge.yaml");
        // Just over the 4 MiB limit; should be rejected before being read whole.
        byte[] filler = new byte[BatchConfigLoader.MAX_CONFIG_BYTES + 1];
        java.util.Arrays.fill(filler, (byte) '#'); // comment bytes, still oversized
        Files.write(file, filler);
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.load(file));
        assertTrue(ex.getMessage().contains("too large"),
                "message should explain the size limit, was: " + ex.getMessage());
    }

    @Test
    void oversizedStringIsRejected() {
        // loadFromString has no file to check the size of before reading (the
        // caller already holds the whole string in memory), but it must still
        // reject oversized content with the same explicit ConfigException as
        // load(Path), instead of relying only on SnakeYAML's internal
        // codePointLimit (which throws an unwrapped runtime exception).
        String filler = "#".repeat(BatchConfigLoader.MAX_CONFIG_BYTES + 1);
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.loadFromString(filler));
        assertTrue(ex.getMessage().contains("too large"),
                "message should explain the size limit, was: " + ex.getMessage());
    }

    @Test
    void malformedUtf8FileIsRejected(@TempDir Path dir) throws IOException {
        // load(Path) now reads bytes directly and decodes them itself (bounded
        // read, see the "why" comment in BatchConfigLoader.load) instead of
        // delegating to Files.readString(Path). Files.readString decodes
        // strictly and throws on a malformed byte sequence rather than
        // silently substituting the U+FFFD replacement character; this test
        // pins that the hand-rolled decode preserves the same strictness
        // (StandardCharsets.UTF_8.newDecoder() defaults to CodingErrorAction
        // .REPORT, unlike the lenient `new String(bytes, UTF_8)` constructor).
        Path file = dir.resolve("bad-encoding.yaml");
        // 0x80 is a lone UTF-8 continuation byte with no leading byte before
        // it: not valid UTF-8 on its own.
        Files.write(file, new byte[] {'n', 'a', 'm', 'e', ':', ' ', (byte) 0x80});
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.load(file));
        assertTrue(ex.getMessage().contains("failed to read batch config file"),
                "message should explain the read/decode failure, was: " + ex.getMessage());
    }

    @Test
    void nonRegularFilePathIsRejected(@TempDir Path dir) {
        // A directory is not a regular file; Files.size()/Files.readString() would
        // either throw an unrelated IOException or behave in a confusing way. The
        // regular-file guard must reject it up front with a clear message, before
        // ever calling Files.size() (which is meaningless for special files).
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("not a regular file"),
                "message should explain the rejection reason, was: " + ex.getMessage());
    }

    @Test
    void characterDeviceIsRejectedEvenThoughItsSizeIsZero() {
        // Regression test for the TOCTOU this guard closes: Files.size("/dev/null")
        // reports 0, which would have silently passed the old size-only check even
        // though reading it is unbounded/blocking for some device files (e.g.
        // /dev/zero). The regular-file guard must reject it before Files.size() is
        // ever consulted. Linux-only; CI runs on ubuntu-latest (see .github/workflows).
        Path devNull = Path.of("/dev/null");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.exists(devNull), "requires /dev/null (Linux/macOS)");
        ConfigException ex = assertThrows(ConfigException.class, () -> loader.load(devNull));
        assertTrue(ex.getMessage().contains("not a regular file"),
                "message should explain the rejection reason, was: " + ex.getMessage());
    }
}
