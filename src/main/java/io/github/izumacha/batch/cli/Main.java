package io.github.izumacha.batch.cli;

/**
 * {@code batch} コマンドラインツールのエントリーポイント（起動クラス）。
 * JVM がここの main メソッドを最初に呼び出す。
 */
public final class Main {

    // インスタンス生成を禁止するためのプライベートコンストラクタ（ユーティリティクラスなので不要）
    private Main() {
    }

    /**
     * プログラムの開始メソッド。コマンドライン引数を BatchCli に渡して実行し、
     * 終了コードでプロセスを終了させる。
     */
    public static void main(String[] args) {
        // BatchCli にコマンドライン引数を渡して実行し、その終了コードでプロセスを終了する
        System.exit(BatchCli.run(args));
    }
}
