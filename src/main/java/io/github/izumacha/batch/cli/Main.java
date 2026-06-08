package io.github.izumacha.batch.cli;

/**
 * Entry point for the {@code batch} command-line tool.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(BatchCli.run(args));
    }
}
