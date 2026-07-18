package io.github.izumacha.batch.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchCliTest {

    // テスト前の標準エラーを退避しておく変数（テストごとに差し替え・復元するため）
    private PrintStream originalErr;
    // 標準エラーの出力先を差し替えて捕捉するためのバッファ
    private ByteArrayOutputStream capturedErr;

    @BeforeEach
    void captureStderr() {
        // 差し替え前の標準エラーを退避する
        originalErr = System.err;
        // 捕捉用バッファを新規に用意する
        capturedErr = new ByteArrayOutputStream();
        // 標準エラーの出力先をバッファへ差し替える
        System.setErr(new PrintStream(capturedErr));
    }

    @AfterEach
    void restoreStderr() {
        // 差し替えた標準エラーを元に戻す（他のテストへ影響を残さないため）
        System.setErr(originalErr);
    }

    @Test
    void bareInvocationFailsWithConfigExitCode() {
        // No sub-command selected: must not look like a successful no-op.
        assertEquals(BatchCli.EXIT_CONFIG, BatchCli.run());
    }

    @Test
    void helpAndVersionSucceed() {
        assertEquals(BatchCli.EXIT_OK, BatchCli.run("--help"));
        assertEquals(BatchCli.EXIT_OK, BatchCli.run("--version"));
    }

    @Test
    void unknownSubcommandMapsToConfigExitCode() {
        assertEquals(BatchCli.EXIT_CONFIG, BatchCli.run("nope"));
    }

    @Test
    void missingRequiredArgumentMapsToConfigExitCode() {
        // `run` without the required CONFIG is invalid input; it must map to
        // EXIT_CONFIG (3), not collide with EXIT_VALIDATION (2).
        int code = BatchCli.run("run");
        assertEquals(BatchCli.EXIT_CONFIG, code);
        assertNotEquals(BatchCli.EXIT_VALIDATION, code);
    }

    // BatchCli.run() の最終防波堤ハンドラ（SanitizingExecutionExceptionHandler）を直接検証する。
    // ValidateCommand 等が catch し損ねる「起こり得ないはず」の内部不変条件違反
    // （DependencyGraph.topologicalOrder の IllegalStateException 等）は正常系入力からは
    // 再現できないため、実際に production で使われるハンドラ本体を直接インスタンス化して
    // 呼び出すことで、picocli の既定ハンドラ（生のスタックトレースをそのまま出す）に
    // 落ちずにこのハンドラで確実にサニタイズされることを検証する。
    @Test
    void executionExceptionHandlerSanitizesUnexpectedExceptionWithoutStackTrace() throws Exception {
        // 検証対象のハンドラを生成する（BatchCli.run() が実際に設定するものと同一クラス）
        BatchCli.SanitizingExecutionExceptionHandler handler =
                new BatchCli.SanitizingExecutionExceptionHandler();
        // 「起こり得ないはず」の内部不変条件違反を模した予期しない例外を用意する
        RuntimeException unexpected = new IllegalStateException("boom: should never happen");

        // ハンドラを直接呼び出す（commandLine/parseResult はこの実装では使われないため null で良い）
        int exitCode = handler.handleExecutionException(unexpected, null, null);

        // 他の失敗経路と同じ EXIT_CONFIG（3）が返ることを検証する
        assertEquals(BatchCli.EXIT_CONFIG, exitCode);
        // 標準エラーの出力内容を取得する
        String stderr = capturedErr.toString();
        // 例外メッセージを含む簡潔な 1 行（"error: ..."）が出力されていることを検証する
        assertTrue(stderr.contains("error: boom: should never happen"),
                "expected sanitized error message, got: " + stderr);
        // スタックトレース（各フレームの典型的な行頭 "\tat "）が標準エラーに一切
        // 出ていないことを検証する（§9 fail-closed の核心）
        assertFalse(stderr.contains("\tat "),
                "stack trace must not leak to stderr, got: " + stderr);
    }

    // メッセージを持たない例外（getMessage() が null）でも「error: null」ではなく
    // 例外クラス名が表示され、最低限の診断情報が残ることを検証する
    @Test
    void executionExceptionHandlerFallsBackToClassNameWhenMessageIsNull() throws Exception {
        // 検証対象のハンドラを生成する（BatchCli.run() が実際に設定するものと同一クラス）
        BatchCli.SanitizingExecutionExceptionHandler handler =
                new BatchCli.SanitizingExecutionExceptionHandler();
        // メッセージ無し（getMessage() が null）の例外を用意する
        RuntimeException noMessage = new IllegalStateException();

        // ハンドラを直接呼び出す（commandLine/parseResult はこの実装では使われないため null で良い）
        int exitCode = handler.handleExecutionException(noMessage, null, null);

        // 他の失敗経路と同じ EXIT_CONFIG（3）が返ることを検証する
        assertEquals(BatchCli.EXIT_CONFIG, exitCode);
        // 標準エラーの出力内容を取得する
        String stderr = capturedErr.toString();
        // 「error: null」ではなく例外クラス名が表示されることを検証する
        assertTrue(stderr.contains("error: IllegalStateException"),
                "expected class-name fallback, got: " + stderr);
        assertFalse(stderr.contains("error: null"),
                "must not print 'error: null', got: " + stderr);
    }
}
