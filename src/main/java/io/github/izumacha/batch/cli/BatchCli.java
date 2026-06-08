package io.github.izumacha.batch.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

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
public final class BatchCli implements Callable<Integer> {

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
     * When no sub-command is given, print usage to stderr and fail with
     * {@link #EXIT_CONFIG} so scripts and CI see that no operation was selected.
     * Picocli still returns {@code 0} for a bare {@code --help}/{@code --version}
     * invocation because those options are handled before {@code call()} is
     * reached.
     */
    @Override
    public Integer call() {
        spec.commandLine().usage(System.err);
        return EXIT_CONFIG;
    }

    /** Convenience for launching the CLI programmatically. */
    public static int run(String... args) {
        CommandLine cmd = new CommandLine(new BatchCli());
        // Map picocli's defaults onto our documented codes so callers can
        // distinguish a usage/config error (3) from a validation error (2) or a
        // batch that ran but FAILED (1). Applied to every command, since parse
        // errors are reported by the (sub)command where they occur.
        applyExitCodes(cmd);
        return cmd.execute(args);
    }

    private static void applyExitCodes(CommandLine cmd) {
        cmd.getCommandSpec().exitCodeOnInvalidInput(EXIT_CONFIG);
        cmd.getCommandSpec().exitCodeOnExecutionException(EXIT_CONFIG);
        for (CommandLine sub : cmd.getSubcommands().values()) {
            applyExitCodes(sub);
        }
    }
}
