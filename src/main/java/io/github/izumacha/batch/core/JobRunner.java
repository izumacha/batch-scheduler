package io.github.izumacha.batch.core;

import io.github.izumacha.batch.model.Job;
import io.github.izumacha.batch.model.JobResult;
import io.github.izumacha.batch.model.JobStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executes a single {@link Job} as an external process, with retries,
 * per-attempt timeouts, and bounded output capture. A {@code JobRunner} never
 * throws because a job failed: every outcome (success, non-zero exit, timeout,
 * failure to start) is reported as a {@link JobResult}.
 */
public final class JobRunner {

    private static final int DEFAULT_MAX_CAPTURED_OUTPUT_LINES = 50;
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(1);
    /** How long to wait for the output reader to drain after a process ends. */
    private static final Duration READER_JOIN_TIMEOUT = Duration.ofSeconds(5);

    private final int maxCapturedOutputLines;
    private final Duration retryBackoff;
    private final boolean echoOutput;

    public JobRunner() {
        this(DEFAULT_MAX_CAPTURED_OUTPUT_LINES, DEFAULT_RETRY_BACKOFF, false);
    }

    public JobRunner(int maxCapturedOutputLines, Duration retryBackoff, boolean echoOutput) {
        if (maxCapturedOutputLines < 0) {
            throw new IllegalArgumentException("maxCapturedOutputLines must be >= 0");
        }
        this.maxCapturedOutputLines = maxCapturedOutputLines;
        this.retryBackoff = retryBackoff == null ? Duration.ZERO : retryBackoff;
        this.echoOutput = echoOutput;
    }

    /**
     * Runs the job, retrying up to {@link Job#maxAttempts()} times until it
     * exits with code 0. Returns a terminal {@link JobResult}; never throws on
     * job failure.
     */
    public JobResult run(Job job) {
        Instant startedAt = Instant.now();
        int attempts = 0;
        int lastExitCode = JobResult.NO_EXIT_CODE;
        String lastMessage = "did not run";
        boolean succeeded = false;
        boolean lastTimedOut = false;

        int maxAttempts = job.maxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attempts = attempt;
            Attempt result = runOnce(job);
            lastExitCode = result.exitCode;
            lastMessage = result.message;
            lastTimedOut = result.timedOut;

            if (result.exitCode == 0 && !result.timedOut && !result.failedToStart) {
                succeeded = true;
                break;
            }

            // More attempts remaining: back off (unless zero) before retrying.
            if (attempt < maxAttempts && !retryBackoff.isZero() && !retryBackoff.isNegative()) {
                try {
                    Thread.sleep(retryBackoff.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        Instant finishedAt = Instant.now();
        JobStatus status = succeeded ? JobStatus.SUCCEEDED : JobStatus.FAILED;
        String message = summarize(succeeded, lastExitCode, attempts, lastTimedOut, lastMessage, job);

        return new JobResult(
                job.id(),
                status,
                lastExitCode,
                attempts,
                startedAt,
                finishedAt,
                message
        );
    }

    private Attempt runOnce(Job job) {
        ProcessBuilder pb = new ProcessBuilder(job.command());
        pb.redirectErrorStream(true);
        if (job.workingDir() != null) {
            pb.directory(new java.io.File(job.workingDir()));
        }
        Process process;
        try {
            // Applying the environment can throw IllegalArgumentException for
            // keys/values ProcessBuilder rejects (e.g. a key containing '=').
            // Treat that as a failure-to-start so it is recorded as a FAILED
            // job result rather than crashing the whole batch.
            Map<String, String> env = job.env();
            if (!env.isEmpty()) {
                pb.environment().putAll(env);
            }
            process = pb.start();
        } catch (IllegalArgumentException e) {
            return Attempt.failedToStart("failed to start: invalid environment (" + e.getMessage() + ")");
        } catch (IOException e) {
            return Attempt.failedToStart("failed to start: " + e.getMessage());
        }

        // Drain combined output on a separate thread so a full pipe buffer
        // never blocks the process.
        OutputCollector collector = new OutputCollector(process, maxCapturedOutputLines, echoOutput);
        Thread reader = new Thread(collector, "jobrunner-output-" + job.id());
        reader.setDaemon(true);
        reader.start();

        try {
            if (job.hasTimeout()) {
                boolean finished = process.waitFor(job.timeoutSeconds(), TimeUnit.SECONDS);
                if (!finished) {
                    killTree(process);
                    // Reap the process, but never block indefinitely: an orphaned
                    // grandchild (e.g. the `sleep` under `sh -c`) can keep the
                    // output pipe open and otherwise hang us for the job's full
                    // runtime. We do not wait on the reader thread here for the
                    // same reason; it is a daemon and will exit when the pipe
                    // finally closes. Capture whatever output is available now.
                    process.waitFor(READER_JOIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    joinQuietly(reader, Duration.ofMillis(200));
                    return Attempt.timedOut(
                            "timed out after " + job.timeoutSeconds() + "s",
                            collector.tail());
                }
            } else {
                process.waitFor();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            joinQuietly(reader, READER_JOIN_TIMEOUT);
            return Attempt.failedToStart("interrupted while waiting for process");
        }

        joinQuietly(reader, READER_JOIN_TIMEOUT);
        int exitCode = process.exitValue();
        return Attempt.completed(exitCode, collector.tail());
    }

    /** Forcibly terminates the process together with any descendants it spawned. */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void joinQuietly(Thread t, Duration timeout) {
        try {
            t.join(timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String summarize(boolean succeeded,
                                    int exitCode,
                                    int attempts,
                                    boolean timedOut,
                                    String lastMessage,
                                    Job job) {
        StringBuilder sb = new StringBuilder();
        if (succeeded) {
            sb.append("exit 0");
            if (attempts > 1) {
                sb.append(" after ").append(attempts).append(" attempts");
            }
        } else if (timedOut) {
            sb.append("timed out after ").append(job.timeoutSeconds()).append("s");
            if (attempts > 1) {
                sb.append(" (").append(attempts).append(" attempts)");
            }
        } else if (exitCode == JobResult.NO_EXIT_CODE) {
            // Failed to start or never produced an exit code.
            sb.append(lastMessage);
            if (attempts > 1) {
                sb.append(" (").append(attempts).append(" attempts)");
            }
        } else {
            sb.append("exit ").append(exitCode);
            if (attempts > 1) {
                sb.append(" after ").append(attempts).append(" attempts");
            }
        }

        // Append a short output tail when it adds context.
        String tail = lastMessage != null && lastMessage.startsWith("OUTPUT:")
                ? lastMessage.substring("OUTPUT:".length())
                : null;
        if (!succeeded && tail != null && !tail.isBlank()) {
            sb.append(": ").append(firstLine(tail));
        }
        return sb.toString();
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s.trim() : s.substring(0, nl).trim();
    }

    /** Outcome of a single process attempt. */
    private record Attempt(int exitCode, boolean timedOut, boolean failedToStart, String message) {
        static Attempt completed(int exitCode, String tail) {
            String msg = tail == null || tail.isBlank() ? null : "OUTPUT:" + tail;
            return new Attempt(exitCode, false, false, msg);
        }

        static Attempt timedOut(String message, String tail) {
            String msg = tail == null || tail.isBlank() ? message : "OUTPUT:" + tail;
            return new Attempt(JobResult.NO_EXIT_CODE, true, false, msg);
        }

        static Attempt failedToStart(String message) {
            return new Attempt(JobResult.NO_EXIT_CODE, false, true, message);
        }
    }

    /** Reads the combined stream, optionally echoing, keeping only the last N lines. */
    private static final class OutputCollector implements Runnable {
        private final Process process;
        private final int maxLines;
        private final boolean echo;
        private final Deque<String> lines = new ArrayDeque<>();

        OutputCollector(Process process, int maxLines, boolean echo) {
            this.process = process;
            this.maxLines = maxLines;
            this.echo = echo;
        }

        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (echo) {
                        System.out.println(line);
                    }
                    if (maxLines > 0) {
                        synchronized (lines) {
                            lines.addLast(line);
                            while (lines.size() > maxLines) {
                                lines.removeFirst();
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // Stream closed (e.g. process destroyed); nothing more to read.
            }
        }

        String tail() {
            synchronized (lines) {
                return String.join("\n", new ArrayList<>(lines));
            }
        }
    }
}
