package io.github.izumacha.batch.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

/**
 * トップレベルの {@code batch} コマンド。サブコマンド（{@code run}・{@code validate}・
 * {@code list}）のコンテナとして機能し、単体で起動するとヘルプを表示する。
 */
@Command(
        name = "batch",
        mixinStandardHelpOptions = true,
        version = "batch-scheduler 0.1.0",
        description = "A small, robust batch execution manager: define jobs in YAML, "
                + "run them honoring a dependency DAG, and track run state.",
        subcommands = {
                RunCommand.class,
                ValidateCommand.class,
                ListCommand.class
        }
)
public final class BatchCli implements Callable<Integer> {

    /** 終了コード: 操作が正常に完了した */
    public static final int EXIT_OK = 0;
    /** 終了コード: バッチは最後まで実行されたが FAILED 状態で終了した */
    public static final int EXIT_FAILED = 1;
    /** 終了コード: バッチの設定が構造的に無効だった */
    public static final int EXIT_VALIDATION = 2;
    /** 終了コード: 設定・IO エラーまたはその他の利用エラーが発生した */
    public static final int EXIT_CONFIG = 3;

    // PicoCLI がこのコマンドの仕様オブジェクトを注入するフィールド
    @Spec
    private CommandSpec spec;

    /**
     * サブコマンドなしで起動された場合にヘルプを標準エラーに表示して
     * {@link #EXIT_CONFIG} を返す。{@code --help}/{@code --version} は
     * {@code call()} に到達する前に処理されるためコード 0 を返す。
     */
    @Override
    public Integer call() {
        // 標準エラーにヘルプメッセージを出力する
        spec.commandLine().usage(System.err);
        // サブコマンド未指定は設定エラーとして EXIT_CONFIG を返す
        return EXIT_CONFIG;
    }

    /** プログラムから CLI を起動するための便利メソッド */
    public static int run(String... args) {
        // BatchCli のインスタンスを CommandLine でラップしてパーサーを構築する
        CommandLine cmd = new CommandLine(new BatchCli());
        // すべてのコマンドとサブコマンドに対して終了コードのマッピングを設定する
        // （PicoCLI のデフォルトコードをドキュメント通りのコードに変換するため）
        applyExitCodes(cmd);
        // 各サブコマンドが個別に catch し損ねた予期しない例外を picocli の既定ハンドラ
        // （生のスタックトレースをそのまま標準エラーに出す）へ渡さないための最終防波堤を設定する。
        // RunCommand は BatchExecutionException を自前で catch して 1 行メッセージへ変換しているが、
        // ValidateCommand 等の他コマンドには同種のガードが無く、DependencyGraph の
        // 「起こり得ないはず」の内部不変条件違反（IllegalStateException）等が万一発生すると、
        // §9 fail-closed の「スタックトレースを外部に出さない」規約からこの経路だけ外れてしまう。
        // ルートの CommandLine 1 箇所に設定すれば、サブコマンドの実行時に投げられた未捕捉の例外は
        // すべてここを経由するため、個々のコマンド実装が catch し忘れても安全側に倒せる。
        cmd.setExecutionExceptionHandler(new SanitizingExecutionExceptionHandler());
        // 引数を解析してコマンドを実行し、終了コードを返す
        return cmd.execute(args);
    }

    // 未捕捉の実行時例外を、生のスタックトレースを出さない 1 行のエラーメッセージへ変換する
    // 最終防波堤ハンドラ（§9 fail-closed。他の catch 節と同じ「error: ...」書式に揃える）。
    // パッケージプライベート（テストから直接インスタンス化して検証できるようにするため）
    static final class SanitizingExecutionExceptionHandler implements IExecutionExceptionHandler {
        @Override
        public int handleExecutionException(Exception ex, CommandLine commandLine, ParseResult parseResult) {
            // 他の失敗経路（RunCommand の各 catch 節）と同じ「error: <メッセージ>」の 1 行だけを
            // 標準エラーに出す。例外オブジェクト自体（スタックトレース）は決して出力しない。
            // このアプリはログ出力先を切り替える logging.properties を持たず、
            // java.util.logging の既定 ConsoleHandler が Level.SEVERE 以上を標準エラーへ
            // そのまま流すため、Logger.log(Level, msg, throwable) のように Throwable 引数付きで
            // 記録すると、この println とは別にフルスタックトレースまで標準エラーへ二重出力されて
            // しまう（実際に検証用テストでこの回帰を確認した）。他の catch 節が e.getMessage() だけを
            // 使い、生の例外を一切 Logger に渡していないのと同じ理由で、ここでもメッセージ文字列
            // だけを扱う。
            System.err.println("error: " + ex.getMessage());
            // 個々のコマンドが自前で catch していた場合と同じ EXIT_CONFIG を返す
            return EXIT_CONFIG;
        }
    }

    // PicoCLI のデフォルト終了コードをこのツール独自のコードに上書きするメソッド
    private static void applyExitCodes(CommandLine cmd) {
        // 無効な入力（引数の解析エラー）の終了コードを EXIT_CONFIG に設定する
        cmd.getCommandSpec().exitCodeOnInvalidInput(EXIT_CONFIG);
        // 実行中の例外（予期しないエラー）の終了コードを EXIT_CONFIG に設定する。
        // run() で設定する SanitizingExecutionExceptionHandler が正常に動作する限りは
        // その戻り値（常に EXIT_CONFIG）がそのまま使われるためこの設定は素通りされるが、
        // 万一ハンドラ自身が例外を投げた場合の picocli 側フォールバックとして機能し続けるため残す
        cmd.getCommandSpec().exitCodeOnExecutionException(EXIT_CONFIG);
        // サブコマンドにも再帰的に同じ設定を適用する
        for (CommandLine sub : cmd.getSubcommands().values()) {
            applyExitCodes(sub);
        }
    }
}
