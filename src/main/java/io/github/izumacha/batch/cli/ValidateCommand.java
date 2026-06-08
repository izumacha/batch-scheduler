package io.github.izumacha.batch.cli;

import io.github.izumacha.batch.config.BatchConfigLoader;
import io.github.izumacha.batch.config.ConfigException;
import io.github.izumacha.batch.config.ValidationException;
import io.github.izumacha.batch.core.DependencyGraph;
import io.github.izumacha.batch.model.Batch;
import io.github.izumacha.batch.model.Job;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Validates a batch YAML file without executing it, reporting structural
 * problems and, on success, the resolved execution order.
 */
@Command(
        name = "validate",
        description = "Check a batch YAML file for structural problems without running it.",
        mixinStandardHelpOptions = true
)
public final class ValidateCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "CONFIG", description = "path to the batch YAML file")
    Path config;

    @Override
    public Integer call() {
        Batch batch;
        try {
            batch = new BatchConfigLoader().load(config);
        } catch (ConfigException e) {
            System.err.println("error: " + e.getMessage());
            return BatchCli.EXIT_CONFIG;
        }

        DependencyGraph graph;
        try {
            graph = DependencyGraph.build(batch);
        } catch (ValidationException e) {
            System.err.println("invalid:");
            for (String error : e.errors()) {
                System.err.println("  - " + error);
            }
            return BatchCli.EXIT_VALIDATION;
        }

        List<Job> order = graph.topologicalOrder();
        System.out.printf("OK: %s (%d jobs)%n", batch.name(), batch.jobs().size());
        System.out.println("Execution order:");
        int step = 1;
        for (Job job : order) {
            System.out.printf("  %d. %s%n", step++, job.id());
        }
        return BatchCli.EXIT_OK;
    }
}
