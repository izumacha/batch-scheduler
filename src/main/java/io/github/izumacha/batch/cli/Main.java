package io.github.izumacha.batch.cli;

import picocli.CommandLine;

/**
 * Entry point for the {@code batch} command-line tool.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int code = new CommandLine(new BatchCli()).execute(args);
        System.exit(code);
    }
}
