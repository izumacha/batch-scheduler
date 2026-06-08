package io.github.izumacha.batch.cli;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Small, null-safe formatting helpers shared by the CLI commands.
 */
final class CliFormat {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private CliFormat() {
    }

    /** Formats an instant as a local timestamp, or {@code "-"} when null. */
    static String instant(Instant instant) {
        return instant == null ? "-" : TIMESTAMP.format(instant);
    }

    /** Formats a duration compactly (e.g. {@code "1m03.4s"}, {@code "850ms"}). */
    static String duration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return "0ms";
        }
        long millis = duration.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        }
        long totalSeconds = duration.getSeconds();
        long minutes = totalSeconds / 60;
        double seconds = (totalSeconds % 60) + (millis % 1000) / 1000.0;
        if (minutes > 0) {
            return String.format("%dm%04.1fs", minutes, seconds);
        }
        return String.format("%.1fs", seconds);
    }

    /** Truncates a possibly-null message to {@code max} characters for tables. */
    static String shortMessage(String message, int max) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String oneLine = message.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= max) {
            return oneLine;
        }
        return oneLine.substring(0, Math.max(0, max - 1)) + "…";
    }
}
