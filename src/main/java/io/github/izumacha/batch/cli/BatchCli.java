package io.github.izumacha.batch.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
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
        // 引数を解析してコマンドを実行し、終了コードを返す
        return cmd.execute(args);
    }

    // PicoCLI のデフォルト終了コードをこのツール独自のコードに上書きするメソッド
    private static void applyExitCodes(CommandLine cmd) {
        // 無効な入力（引数の解析エラー）の終了コードを EXIT_CONFIG に設定する
        cmd.getCommandSpec().exitCodeOnInvalidInput(EXIT_CONFIG);
        // 実行中の例外（予期しないエラー）の終了コードを EXIT_CONFIG に設定する
        cmd.getCommandSpec().exitCodeOnExecutionException(EXIT_CONFIG);
        // サブコマンドにも再帰的に同じ設定を適用する
        for (CommandLine sub : cmd.getSubcommands().values()) {
            applyExitCodes(sub);
        }
    }
}
