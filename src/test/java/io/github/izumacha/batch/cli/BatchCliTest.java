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
    void unknownSubcommandFails() {
        // picocli reports its own usage exit code; the contract we care about is
        // that it is not treated as success.
        assertNotEquals(BatchCli.EXIT_OK, BatchCli.run("nope"));
    }
}
