package io.github.izumacha.batch.cli;

import io.github.izumacha.batch.model.ExecutionResult;
import io.github.izumacha.batch.state.JsonExecutionStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Lists previously recorded batch runs, most recent first.
 */
@Command(
        name = "list",
        description = "List recorded batch runs, most recent first.",
        mixinStandardHelpOptions = true
)
public final class ListCommand implements Callable<Integer> {

    @Option(names = {"--state-dir"}, defaultValue = ".batch-state",
            description = "directory for run state (default: ${DEFAULT-VALUE})")
    Path stateDir;

    @Override
    public Integer call() {
        List<ExecutionResult> runs = new JsonExecutionStore(stateDir).findAll();

        if (runs.isEmpty()) {
            System.out.println("no runs found");
            return BatchCli.EXIT_OK;
        }

        String header = String.format("%-36s  %-20s  %-9s  %-19s  %10s",
                "RUN ID", "BATCH", "STATUS", "STARTED", "DURATION");
        System.out.println(header);
        System.out.println("-".repeat(header.length()));
        for (ExecutionResult run : runs) {
            System.out.printf("%-36s  %-20s  %-9s  %-19s  %10s%n",
                    run.runId(),
                    CliFormat.shortMessage(run.batchName(), 20),
                    run.status(),
                    CliFormat.instant(run.startedAt()),
                    CliFormat.duration(run.duration()));
        }
        return BatchCli.EXIT_OK;
    }
}
