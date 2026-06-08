package io.github.izumacha.batch.cli;

import io.github.izumacha.batch.config.BatchConfigLoader;
import io.github.izumacha.batch.config.ConfigException;
import io.github.izumacha.batch.config.ValidationException;
import io.github.izumacha.batch.core.BatchExecutor;
import io.github.izumacha.batch.model.Batch;
import io.github.izumacha.batch.model.ExecutionResult;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;
import io.github.izumacha.batch.state.JsonExecutionStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Loads a batch from YAML, executes it and persists the run report.
 */
@Command(
        name = "run",
        description = "Execute a batch defined in a YAML file and record the run.",
        mixinStandardHelpOptions = true
)
public final class RunCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "CONFIG", description = "path to the batch YAML file")
    Path config;

    @Option(names = {"--state-dir"}, defaultValue = ".batch-state",
            description = "directory for run state (default: ${DEFAULT-VALUE})")
    Path stateDir;

    @Option(names = {"-q", "--quiet"}, description = "suppress the per-job summary table")
    boolean quiet;

    @Override
    public Integer call() {
        Batch batch;
        try {
            batch = new BatchConfigLoader().load(config);
        } catch (ConfigException e) {
            System.err.println("error: " + e.getMessage());
            return BatchCli.EXIT_CONFIG;
        }

        ExecutionResult result;
        try {
            result = new BatchExecutor().execute(batch);
        } catch (ValidationException e) {
            for (String error : e.errors()) {
                System.err.println("invalid: " + error);
            }
            return BatchCli.EXIT_VALIDATION;
        }

        try {
            new JsonExecutionStore(stateDir).save(result);
        } catch (RuntimeException e) {
            System.err.println("error: failed to persist run state: " + e.getMessage());
            return BatchCli.EXIT_CONFIG;
        }

        printSummary(result);

        return result.succeeded() ? BatchCli.EXIT_OK : BatchCli.EXIT_FAILED;
    }

    private void printSummary(ExecutionResult result) {
        System.out.printf("Batch:  %s%n", result.batchName());
        System.out.printf("Run ID: %s%n", result.runId());
        System.out.printf("Status: %s%n", result.status());
        System.out.printf("Jobs:   %d succeeded, %d failed, %d skipped (%d total) in %s%n",
                result.countByStatus(JobStatus.SUCCEEDED),
                result.countByStatus(JobStatus.FAILED),
                result.countByStatus(JobStatus.SKIPPED),
                result.jobResults().size(),
                CliFormat.duration(result.duration()));

        if (!quiet && !result.jobResults().isEmpty()) {
            System.out.println();
            String header = String.format("%-20s  %-9s  %5s  %10s  %s",
                    "JOB", "STATUS", "EXIT", "DURATION", "MESSAGE");
            System.out.println(header);
            System.out.println("-".repeat(header.length()));
            for (JobResult job : result.jobResults()) {
                String exit = job.exitCode() == JobResult.NO_EXIT_CODE
                        ? "-" : Integer.toString(job.exitCode());
                System.out.printf("%-20s  %-9s  %5s  %10s  %s%n",
                        job.jobId(),
                        job.status(),
                        exit,
                        CliFormat.duration(job.duration()),
                        CliFormat.shortMessage(job.message(), 60));
            }
        }

        System.out.printf("%nState saved to %s%n", stateDir.toAbsolutePath());
    }
}
