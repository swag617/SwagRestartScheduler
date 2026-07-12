package com.swag617.restartsched.logging;

import com.swag617.restartsched.SwagRestartScheduler;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * Appends restart events to two log files:
 * <ul>
 *   <li>{@code {dataFolder}/logs/restart-log.yml} — YAML format (existing Phase 1)</li>
 *   <li>{@code {dataFolder}/logs/restart-log.csv} — CSV format (Phase 3)</li>
 * </ul>
 *
 * <h3>CSV columns</h3>
 * <pre>
 * timestamp,type,schedule,reason,initiator,players_online,
 *   grace_period_used,grace_duration_seconds,restart_duration_seconds
 * </pre>
 *
 * <p>{@code restart_duration_seconds} is unknown at log time and is written as an
 * empty field.  Call {@link #updateLastRestartDuration(int)} after the fact if
 * the duration becomes known (best-effort; rewrites the last line of the CSV).</p>
 *
 * <p>All disk I/O is performed synchronously — this is called just before the
 * server shuts down so async scheduling is not an option at that point.</p>
 */
public class RestartLogger {

    /** CSV header written on first file creation. */
    private static final String CSV_HEADER =
            "timestamp,type,schedule,reason,initiator,players_online,"
            + "grace_period_used,grace_duration_seconds,restart_duration_seconds";

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                             .withZone(ZoneId.of("UTC"));

    private final SwagRestartScheduler plugin;
    private final Logger logger;
    private final File logFile;    // YAML
    private final File csvFile;    // CSV

    public RestartLogger(SwagRestartScheduler plugin) {
        this.plugin  = plugin;
        this.logger  = plugin.getLogger();

        File logsDir = new File(plugin.getDataFolder(), "logs");
        if (!logsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            logsDir.mkdirs();
        }
        this.logFile = new File(logsDir, "restart-log.yml");
        this.csvFile = new File(logsDir, "restart-log.csv");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link File} handle for the CSV log.
     * Used by the {@code /restart logs export} command to report the path.
     */
    public File getCsvFile() {
        return csvFile;
    }

    /**
     * Appends a restart event entry to both log files.
     *
     * @param initiator    player name, "SCHEDULE", "CONSOLE", or "PERFORMANCE"
     * @param reason       human-readable reason
     * @param source       schedule name or "manual"
     * @param type         "SCHEDULED", "MANUAL", or "PERFORMANCE"
     */
    public void logRestart(String initiator, String reason, String source, String type) {
        Instant now         = Instant.now();
        String  timestamp   = ISO_FMT.format(now);
        String  key         = "restarts." + now.getEpochSecond();
        int     playersOnline = plugin.getServer().getOnlinePlayers().size();

        // --- YAML log ---
        YamlConfiguration yaml = loadOrCreate();
        yaml.set(key + ".timestamp",      timestamp);
        yaml.set(key + ".initiator",      initiator     != null ? initiator  : "UNKNOWN");
        yaml.set(key + ".reason",         reason        != null ? reason     : "");
        yaml.set(key + ".source",         source        != null ? source     : "unknown");
        yaml.set(key + ".type",           type          != null ? type       : "UNKNOWN");
        yaml.set(key + ".players_online", playersOnline);
        saveYaml(yaml);

        // --- CSV log ---
        appendCsvRow(timestamp, type, source, reason, initiator, playersOnline,
                false, 0, "");

        logger.info("Restart logged: [" + type + "] initiated by " + initiator
                + " | reason: " + reason + " | source: " + source
                + " | players online: " + playersOnline);
    }

    /**
     * Logs a performance-triggered restart event.  The type is set to
     * {@code "PERFORMANCE"} and the average TPS at trigger time is appended
     * to the reason for auditing purposes.
     *
     * @param avgTps   average TPS at the moment of triggering
     * @param reason   configured reason string
     * @param action   "schedule" or "immediate"
     */
    public void logPerformanceTrigger(double avgTps, String reason, String action) {
        String fullReason = reason + " (avg TPS: " + String.format("%.2f", avgTps)
                + ", action: " + action + ")";
        logRestart("PERFORMANCE", fullReason, "performance", "PERFORMANCE");
    }

    /**
     * Best-effort update of the {@code restart_duration_seconds} field in the
     * last row of the CSV file.  Rewrites only the last line.
     *
     * <p>This is a best-effort operation.  If the file cannot be read or the
     * last line does not match the expected CSV format, the method logs a
     * warning and returns without throwing.</p>
     *
     * @param seconds elapsed seconds from restart initiation to server-online
     */
    public void updateLastRestartDuration(int seconds) {
        if (!csvFile.exists()) return;

        try {
            // Read the entire file, find the last non-empty line, replace its last field
            byte[] bytes = java.nio.file.Files.readAllBytes(csvFile.toPath());
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);

            // Find last non-empty line index
            int lastIdx = lines.length - 1;
            while (lastIdx >= 0 && lines[lastIdx].isBlank()) {
                lastIdx--;
            }
            if (lastIdx < 1) {
                // Nothing past the header
                return;
            }

            String lastLine = lines[lastIdx];
            // The last field is restart_duration_seconds — replace it
            int lastComma = lastLine.lastIndexOf(',');
            if (lastComma < 0) return;

            String updated = lastLine.substring(0, lastComma + 1) + seconds;
            lines[lastIdx] = updated;

            // Rebuild and write back
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                sb.append(lines[i]);
                if (i < lines.length - 1) sb.append("\n");
            }
            java.nio.file.Files.write(csvFile.toPath(),
                    sb.toString().getBytes(StandardCharsets.UTF_8));

        } catch (IOException e) {
            logger.warning("Could not update restart duration in CSV log: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // CSV helpers
    // -------------------------------------------------------------------------

    /**
     * Appends one data row to the CSV file, writing the header first if the
     * file does not yet exist or is empty.
     */
    private void appendCsvRow(String timestamp, String type, String source,
                               String reason, String initiator, int playersOnline,
                               boolean gracePeriodUsed, int graceDurationSeconds,
                               String restartDurationSeconds) {
        ensureCsvExists();

        String row = escapeCsv(timestamp)
                + "," + escapeCsv(type)
                + "," + escapeCsv(source)
                + "," + escapeCsv(reason)
                + "," + escapeCsv(initiator)
                + "," + playersOnline
                + "," + gracePeriodUsed
                + "," + graceDurationSeconds
                + "," + restartDurationSeconds; // may be empty string

        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new FileWriter(csvFile, StandardCharsets.UTF_8, true)))) {
            pw.println(row);
        } catch (IOException e) {
            logger.severe("Failed to append to CSV restart log: " + e.getMessage());
        }
    }

    /**
     * Creates the CSV file and writes the header row if the file does not exist
     * or is empty.
     */
    private void ensureCsvExists() {
        if (!csvFile.exists() || csvFile.length() == 0) {
            try {
                //noinspection ResultOfMethodCallIgnored
                csvFile.getParentFile().mkdirs();
                //noinspection ResultOfMethodCallIgnored
                csvFile.createNewFile();
                try (PrintWriter pw = new PrintWriter(
                        new BufferedWriter(
                                new FileWriter(csvFile, StandardCharsets.UTF_8, false)))) {
                    pw.println(CSV_HEADER);
                }
            } catch (IOException e) {
                logger.warning("Could not create CSV restart log file: " + e.getMessage());
            }
        }
    }

    /**
     * Wraps a CSV field value in double-quotes if it contains a comma, a
     * double-quote, or a newline.  Embedded double-quotes are escaped by
     * doubling them (RFC 4180).
     *
     * @param value the raw field value; may be {@code null}
     * @return the escaped CSV field string
     */
    private String escapeCsv(String value) {
        if (value == null) return "";
        // Always quote — simpler and safe
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    // -------------------------------------------------------------------------
    // YAML helpers (unchanged from Phase 1)
    // -------------------------------------------------------------------------

    private YamlConfiguration loadOrCreate() {
        if (!logFile.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                logFile.getParentFile().mkdirs();
                //noinspection ResultOfMethodCallIgnored
                logFile.createNewFile();
            } catch (IOException e) {
                logger.warning("Could not create restart log file: " + e.getMessage());
            }
        }
        return YamlConfiguration.loadConfiguration(logFile);
    }

    private void saveYaml(YamlConfiguration yaml) {
        try {
            yaml.save(logFile);
        } catch (IOException e) {
            logger.severe("Failed to save restart log: " + e.getMessage());
        }
    }
}
