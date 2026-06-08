package io.github.izumacha.batch.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BatchCliTest {

    @Test
    void bareInvocationFailsWithConfigExitCode() {
        // No sub-command selected: must not look like a successful no-op.
        assertEquals(BatchCli.EXIT_CONFIG, BatchCli.run());
    }

    @Test
    void helpAndVersionSucceed() {
        assertEquals(BatchCli.EXIT_OK, BatchCli.run("--help"));
        assertEquals(BatchCli.EXIT_OK, BatchCli.run("--version"));
    }

    @Test
    void unknownSubcommandMapsToConfigExitCode() {
        assertEquals(BatchCli.EXIT_CONFIG, BatchCli.run("nope"));
    }

    @Test
    void missingRequiredArgumentMapsToConfigExitCode() {
        // `run` without the required CONFIG is invalid input; it must map to
        // EXIT_CONFIG (3), not collide with EXIT_VALIDATION (2).
        int code = BatchCli.run("run");
        assertEquals(BatchCli.EXIT_CONFIG, code);
        assertNotEquals(BatchCli.EXIT_VALIDATION, code);
    }
}
