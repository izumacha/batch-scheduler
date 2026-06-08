package io.github.izumacha.batch.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Top-level {@code batch} command. Acts purely as a container for the
 * sub-commands ({@code run}, {@code validate}, {@code list}); invoking it on its
 * own prints the usage help.
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
public final class BatchCli implements Runnable {

    /** Exit code: the requested operation completed successfully. */
    public static final int EXIT_OK = 0;
    /** Exit code: the batch ran to completion but ended in a FAILED state. */
    public static final int EXIT_FAILED = 1;
    /** Exit code: the batch configuration is structurally invalid. */
    public static final int EXIT_VALIDATION = 2;
    /** Exit code: a configuration/IO error or other usage error occurred. */
    public static final int EXIT_CONFIG = 3;

    @Spec
    private CommandSpec spec;

    /**
     * When no sub-command is given, print usage to stderr. Picocli still returns
     * {@code 0} for a bare {@code --help}/{@code --version} invocation because
     * those options are handled before {@code run()} is reached.
     */
    @Override
    public void run() {
        spec.commandLine().usage(System.err);
    }

    /** Convenience for launching the CLI programmatically. */
    public static int run(String... args) {
        return new CommandLine(new BatchCli()).execute(args);
    }
}
