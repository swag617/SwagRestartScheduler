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
 *   grace_period_used,grace_duration_seconds
 * </pre>
 *
 * <p>{@code grace_period_used} and {@code grace_duration_seconds} reflect whether
 * {@link com.swag617.restartsched.task.RestartTask} actually delayed the restart via the
 * grace-period handler, and for how long. There used to be a third
 * {@code restart_duration_seconds} column plus an {@code updateLastRestartDuration(int)}
 * method meant to fill it in after the fact — both were removed as permanently-dead
 * scaffolding: the server process exits immediately after this log call, so nothing in this
 * plugin is ever in a position to observe (let alone report back) how long the restart itself
 * took.</p>
 *
 * <p>All disk I/O is performed synchronously — this is called just before the
 * server shuts down so async scheduling is not an option at that point.</p>
 */
public class RestartLogger {

    /** CSV header written on first file creation. */
    private static final String CSV_HEADER =
            "timestamp,type,schedule,reason,initiator,players_online,"
            + "grace_period_used,grace_duration_seconds";

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
     * @param initiator            player name, "SCHEDULE", "CONSOLE", or "PERFORMANCE"
     * @param reason               human-readable reason
     * @param source               schedule name or "manual"
     * @param type                 "SCHEDULED", "MANUAL", or "PERFORMANCE"
     * @param gracePeriodUsed      whether the grace-period handler actually delayed this restart
     * @param graceDurationSeconds how many seconds the grace period delayed the restart by
     *                             (0 if {@code gracePeriodUsed} is {@code false})
     */
    public void logRestart(String initiator, String reason, String source, String type,
                            boolean gracePeriodUsed, int graceDurationSeconds) {
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
        yaml.set(key + ".grace_period_used",      gracePeriodUsed);
        yaml.set(key + ".grace_duration_seconds", graceDurationSeconds);
        saveYaml(yaml);

        // --- CSV log ---
        appendCsvRow(timestamp, type, source, reason, initiator, playersOnline,
                gracePeriodUsed, graceDurationSeconds);

        logger.info("Restart logged: [" + type + "] initiated by " + initiator
                + " | reason: " + reason + " | source: " + source
                + " | players online: " + playersOnline);
    }

    /**
     * Logs a performance-triggered restart event.  The type is set to
     * {@code "PERFORMANCE"} and the average TPS at trigger time is appended
     * to the reason for auditing purposes.
     *
     * <p>This logs the moment the trigger fired (queuing a restart), not the restart itself —
     * grace-period data isn't known yet at this point, so it is always logged as unused.</p>
     *
     * @param avgTps   average TPS at the moment of triggering
     * @param reason   configured reason string
     * @param action   "schedule" or "immediate"
     */
    public void logPerformanceTrigger(double avgTps, String reason, String action) {
        String fullReason = reason + " (avg TPS: " + String.format("%.2f", avgTps)
                + ", action: " + action + ")";
        logRestart("PERFORMANCE", fullReason, "performance", "PERFORMANCE", false, 0);
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
                               boolean gracePeriodUsed, int graceDurationSeconds) {
        ensureCsvExists();

        String row = escapeCsv(timestamp)
                + "," + escapeCsv(type)
                + "," + escapeCsv(source)
                + "," + escapeCsv(reason)
                + "," + escapeCsv(initiator)
                + "," + playersOnline
                + "," + gracePeriodUsed
                + "," + graceDurationSeconds;

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
