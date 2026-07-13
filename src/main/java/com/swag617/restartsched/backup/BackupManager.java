package com.swag617.restartsched.backup;

import com.swag617.restartsched.SwagRestartScheduler;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Manages the optional pre-restart backup system.
 *
 * <p>When {@code backup.enabled} is {@code true} in config.yml, this manager
 * is called by {@link com.swag617.restartsched.task.RestartTask} before the
 * server actually stops.  It:</p>
 * <ol>
 *   <li>Broadcasts a "backup starting" message to all online players.</li>
 *   <li>Dispatches {@code save-all} so world data is flushed to disk first.</li>
 *   <li>Runs {@link BackupTask} asynchronously so the main thread is not blocked.</li>
 *   <li>On completion (success or failure), calls the {@code onComplete} runnable
 *       back on the main thread so the actual server restart can proceed.</li>
 *   <li>Prunes old backups according to {@code backup.max_backups}.</li>
 * </ol>
 *
 * <p>The restart is <em>always</em> triggered after backup — a backup failure
 * never permanently blocks the server from restarting.</p>
 */
public class BackupManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    // Broadcast strings (MiniMessage format)
    private static final String MSG_STARTING =
            "<gold>Backup in progress \u2014 restart will follow shortly.";
    private static final String MSG_SUCCESS  =
            "<green>\u2714 <yellow>Backup complete! Restarting now...";
    private static final String MSG_FAILED   =
            "<red>\u2718 <yellow>Backup failed \u2014 restarting anyway.";

    private final SwagRestartScheduler plugin;

    // ---- Config values (populated by reload()) ----
    private boolean  enabled;
    private File     backupDir;
    private List<String> includeList;
    private int      maxBackups;
    private boolean  compress;
    private boolean  maintenanceMode;

    public BackupManager(SwagRestartScheduler plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /**
     * (Re-)reads the {@code backup} section from config.yml.
     * Safe to call on the main thread; no I/O beyond config reads.
     */
    public void reload() {
        enabled         = plugin.getConfig().getBoolean("backup.enabled", false);
        maxBackups      = plugin.getConfig().getInt("backup.max_backups", 5);
        compress        = plugin.getConfig().getBoolean("backup.compress", true);
        maintenanceMode = plugin.getConfig().getBoolean("backup.maintenance_mode", true);

        includeList = plugin.getConfig().getStringList("backup.include");
        if (includeList == null || includeList.isEmpty()) {
            includeList = List.of("world", "world_nether", "world_the_end");
        }

        String destPath = plugin.getConfig().getString(
                "backup.destination",
                "plugins/SwagRestartScheduler/backups");

        File dest = new File(destPath);
        if (!dest.isAbsolute()) {
            // Resolve relative to the server's working directory
            try {
                File serverRoot = new File(".").getCanonicalFile();
                dest = new File(serverRoot, destPath);
            } catch (IOException e) {
                plugin.getLogger().warning("[Backup] Could not resolve server root, "
                        + "using relative path as-is: " + e.getMessage());
                dest = new File(destPath);
            }
        }
        backupDir = dest;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns {@code true} if the backup system is enabled in config. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Returns the resolved destination {@link File} for backup archives. */
    public File getBackupDir() {
        return backupDir;
    }

    /**
     * Main entry point called by {@link com.swag617.restartsched.task.RestartTask}.
     *
     * <p>Must be called on the <strong>main thread</strong>.  Broadcasts the
     * "backup starting" message, flushes world data, then hands off to
     * {@link BackupTask} asynchronously.  {@code onComplete} is called back on
     * the main thread once the backup finishes (regardless of success/failure).</p>
     *
     * @param onComplete runnable to execute on the main thread after backup;
     *                   this is what actually triggers the server restart
     */
    public void runBackup(Runnable onComplete) {
        // 1. Enable maintenance / whitelist so no new players join during backup
        if (maintenanceMode) {
            boolean viaMaintenancePlugin = Bukkit.getPluginManager().isPluginEnabled("Maintenance");
            if (viaMaintenancePlugin) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "maintenance true");
                plugin.getLogger().info("[Backup] Maintenance mode enabled via Maintenance plugin.");
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist on");
                plugin.getLogger().info("[Backup] Whitelist enabled (Maintenance plugin not found).");
            }
            // Persist which mechanism we used so a future onEnable() (after the restart this
            // backup precedes) can detect and revert it — runBackup() always runs right before
            // the server goes down, so nothing in this JVM lifetime gets a chance to turn it
            // back off otherwise. See SwagRestartScheduler#onEnable() ->
            // BackupManager#checkAndClearMaintenanceMarker().
            writeMaintenanceMarker(viaMaintenancePlugin ? "maintenance" : "whitelist");
        }

        // 2. Broadcast to players
        broadcastAll(MSG_STARTING);

        // 3. Flush world data before copying
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");

        // 4. Resolve source folders (warn and skip any that don't exist)
        List<File> sourceFolders = resolveSourceFolders();

        // 5. Launch async backup
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new BackupTask(
                plugin,
                backupDir,
                sourceFolders,
                compress,
                success -> {
                    // BackupTask calls this consumer — still on the async thread.
                    // Re-schedule everything that follows back onto the main thread.
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (success) {
                            broadcastAll(MSG_SUCCESS);
                            pruneOldBackups();
                        } else {
                            broadcastAll(MSG_FAILED);
                        }
                        onComplete.run();
                    });
                }
        ));
    }

    // -------------------------------------------------------------------------
    // Maintenance-mode marker (Bug fix: maintenance/whitelist was never reverted)
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link File} used to record that the last backup left the server in
     * maintenance mode or whitelisted, pending a revert on the next startup.
     */
    private File maintenanceMarkerFile() {
        return new File(plugin.getDataFolder(), "maintenance.lock");
    }

    /**
     * Writes a marker file recording which mechanism ({@code "maintenance"} or
     * {@code "whitelist"}) was just enabled, so {@link #checkAndClearMaintenanceMarker()}
     * can revert it on the next plugin startup.
     */
    private void writeMaintenanceMarker(String mode) {
        try {
            Files.writeString(maintenanceMarkerFile().toPath(), mode, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("[Backup] Could not write maintenance marker — "
                    + "the server may stay in maintenance/whitelist mode after the next restart "
                    + "until an admin manually clears it: " + e.getMessage());
        }
    }

    /**
     * Checks for a maintenance marker left behind by a previous backup and reverts the
     * maintenance/whitelist state that was enabled before the backup ran, then deletes
     * the marker.  Must be called from {@code onEnable()}, early, before players can join.
     *
     * <p>Safe to call even if no marker exists (does nothing).  Best-effort: if the
     * {@code Maintenance} plugin is no longer installed by the time this runs, a warning
     * is logged instead of silently leaving the server inaccessible with no explanation.</p>
     */
    public void checkAndClearMaintenanceMarker() {
        File marker = maintenanceMarkerFile();
        if (!marker.exists()) return;

        String mode;
        try {
            mode = Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            plugin.getLogger().warning("[Backup] Could not read maintenance marker ("
                    + e.getMessage() + ") — leaving current maintenance/whitelist state unchanged. "
                    + "Check manually whether the server is still in maintenance mode or whitelisted.");
            return;
        }

        if ("maintenance".equals(mode)) {
            if (Bukkit.getPluginManager().isPluginEnabled("Maintenance")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "maintenance false");
                plugin.getLogger().info("[Backup] Reverted Maintenance-plugin maintenance mode "
                        + "that was left on by the previous backup.");
            } else {
                plugin.getLogger().warning("[Backup] The previous backup left the server in "
                        + "Maintenance-plugin maintenance mode, but the Maintenance plugin is not "
                        + "currently enabled to revert it. Please check manually.");
            }
        } else if ("whitelist".equals(mode)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "whitelist off");
            plugin.getLogger().info("[Backup] Reverted whitelist that was left on by the previous backup.");
        } else {
            plugin.getLogger().warning("[Backup] Unrecognized maintenance marker contents ('" + mode
                    + "') — ignoring. Please check manually whether maintenance mode or the "
                    + "whitelist need to be disabled.");
        }

        //noinspection ResultOfMethodCallIgnored
        marker.delete();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves {@code backup.include} entries against the server working
     * directory.  Entries that are absolute paths are used as-is.
     */
    private List<File> resolveSourceFolders() {
        List<File> resolved = new ArrayList<>();
        File serverRoot;
        try {
            serverRoot = new File(".").getCanonicalFile();
        } catch (IOException e) {
            plugin.getLogger().warning("[Backup] Could not determine server root: " + e.getMessage());
            serverRoot = new File(".");
        }

        for (String entry : includeList) {
            File f = new File(entry);
            if (!f.isAbsolute()) {
                f = new File(serverRoot, entry);
            }
            if (!f.exists()) {
                plugin.getLogger().warning("[Backup] Configured include folder does not exist, "
                        + "skipping: " + f.getAbsolutePath());
                continue;
            }
            resolved.add(f);
        }
        return resolved;
    }

    /**
     * Deletes the oldest backup files/directories when the count exceeds
     * {@code max_backups}.  A value of 0 means keep forever.
     *
     * <p>Must be called on the main thread (only does fast file unlinking,
     * not bulk I/O).</p>
     */
    private void pruneOldBackups() {
        if (maxBackups <= 0) return;
        if (!backupDir.exists()) return;

        File[] entries = backupDir.listFiles(f ->
                f.getName().startsWith("backup_"));
        if (entries == null || entries.length <= maxBackups) return;

        // Sort oldest-first by last-modified timestamp
        Arrays.sort(entries, Comparator.comparingLong(File::lastModified));

        int toDelete = entries.length - maxBackups;
        for (int i = 0; i < toDelete; i++) {
            File old = entries[i];
            boolean deleted = deleteRecursive(old);
            if (deleted) {
                plugin.getLogger().info("[Backup] Pruned old backup: " + old.getName());
            } else {
                plugin.getLogger().warning("[Backup] Could not delete old backup: "
                        + old.getAbsolutePath());
            }
        }
    }

    /** Recursively deletes a file or directory. Returns {@code true} if fully deleted. */
    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return f.delete();
    }

    /** Sends a MiniMessage-formatted message to all online players. */
    private void broadcastAll(String miniMessageText) {
        var component = MM.deserialize(miniMessageText);
        for (var player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(component);
        }
        plugin.getLogger().info("[Backup] " + MM.stripTags(miniMessageText));
    }
}
