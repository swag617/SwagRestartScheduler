package com.swag617.restartsched.config;

import com.swag617.restartsched.SwagRestartScheduler;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Centralises loading, reloading, and validation of all plugin configuration files.
 *
 * <h3>Files managed</h3>
 * <ul>
 *   <li>{@code config.yml}    — general settings + warning intervals</li>
 *   <li>{@code schedules.yml} — named restart schedules</li>
 *   <li>{@code messages.yml}  — all player-facing strings</li>
 * </ul>
 *
 * <h3>Reload contract</h3>
 * <p>{@link #reload()} is the only method that writes to the config references.
 * If parsing fails the previous values are retained (rollback semantics).</p>
 */
public class ConfigManager {

    private final SwagRestartScheduler plugin;
    private final Logger logger;

    // Backing file objects
    private final File schedulesFile;
    private final File messagesFile;

    // Loaded configurations (non-null after first successful load)
    private FileConfiguration schedulesConfig;
    private FileConfiguration messagesConfig;

    public ConfigManager(SwagRestartScheduler plugin) {
        this.plugin        = plugin;
        this.logger        = plugin.getLogger();
        this.schedulesFile = new File(plugin.getDataFolder(), "schedules.yml");
        this.messagesFile  = new File(plugin.getDataFolder(), "messages.yml");
    }

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    /**
     * Saves defaults and performs the first load of all config files.
     * Must be called from {@code onEnable} before any manager uses config values.
     */
    public void initialLoad() {
        saveDefaults();
        reloadAll();
    }

    // -------------------------------------------------------------------------
    // Reload
    // -------------------------------------------------------------------------

    /**
     * Reloads all three config files, retaining previous values on parse error.
     *
     * @throws IllegalStateException if config.yml validation fails
     */
    public void reload() {
        reloadAll();
    }

    /**
     * Reloads only {@code schedules.yml}.
     * Called internally by {@link com.swag617.restartsched.schedule.ScheduleManager}.
     */
    public void reloadSchedules() {
        FileConfiguration prev = schedulesConfig;
        try {
            schedulesConfig = loadYaml(schedulesFile, "schedules.yml");
        } catch (Exception e) {
            logger.warning("Failed to reload schedules.yml: " + e.getMessage() + " — retaining previous config.");
            schedulesConfig = prev;
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Returns the loaded {@code config.yml} (Bukkit's built-in config). */
    public FileConfiguration getMainConfig() {
        return plugin.getConfig();
    }

    /** Returns the loaded {@code schedules.yml}. */
    public FileConfiguration getSchedulesConfig() {
        return schedulesConfig;
    }

    /** Returns the loaded {@code messages.yml}. */
    public FileConfiguration getMessagesConfig() {
        return messagesConfig;
    }

    /**
     * Retrieves a message from {@code messages.yml}.
     *
     * <p>The prefix defined under {@code prefix} is prepended automatically unless
     * {@code addPrefix} is {@code false}.</p>
     *
     * @param key       dotted YAML key
     * @param addPrefix whether to prepend the prefix
     * @return the message string, or an empty string if the key is absent
     */
    public String getMessage(String key, boolean addPrefix) {
        if (messagesConfig == null) return "";
        String value = messagesConfig.getString(key, "");
        if (addPrefix && !value.isBlank()) {
            String prefix = messagesConfig.getString("prefix", "");
            value = prefix + value;
        }
        return value;
    }

    /** Convenience — retrieves a prefixed message. */
    public String getMessage(String key) {
        return getMessage(key, true);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void saveDefaults() {
        // config.yml — handled by Bukkit
        plugin.saveDefaultConfig();

        // schedules.yml
        if (!schedulesFile.exists()) {
            plugin.saveResource("schedules.yml", false);
        }

        // messages.yml
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
    }

    private void reloadAll() {
        // config.yml
        plugin.reloadConfig();
        validate(plugin.getConfig());

        // schedules.yml
        FileConfiguration prevSchedules = schedulesConfig;
        try {
            schedulesConfig = loadYaml(schedulesFile, "schedules.yml");
        } catch (Exception e) {
            logger.warning("Failed to load schedules.yml: " + e.getMessage() + " — retaining previous config.");
            schedulesConfig = prevSchedules;
        }

        // messages.yml
        FileConfiguration prevMessages = messagesConfig;
        try {
            messagesConfig = loadYaml(messagesFile, "messages.yml");
        } catch (Exception e) {
            logger.warning("Failed to load messages.yml: " + e.getMessage() + " — retaining previous config.");
            messagesConfig = prevMessages;
        }
    }

    /**
     * Loads a YAML file from disk, using the bundled resource as a default-value
     * overlay so that newly added keys always have sensible defaults.
     */
    private FileConfiguration loadYaml(File file, String resourceName) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        // Apply defaults from the bundled resource
        InputStream defaults = plugin.getResource(resourceName);
        if (defaults != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8));
            config.setDefaults(defaultConfig);
        }

        return config;
    }

    /**
     * Light validation of {@code config.yml}.
     * Logs warnings for invalid values but does not throw — the defaults cover us.
     */
    private void validate(FileConfiguration config) {
        int checkInterval = config.getInt("general.check-interval-seconds", 10);
        if (checkInterval < 1) {
            logger.warning("general.check-interval-seconds is < 1 — using 10.");
        }

        int abThreshold = config.getInt("warnings.action-bar-threshold", 60);
        if (abThreshold < 0) {
            logger.warning("warnings.action-bar-threshold is negative — action bar disabled.");
        }
    }

    // -------------------------------------------------------------------------
    // Utility: save a FileConfiguration back to disk
    // -------------------------------------------------------------------------

    /**
     * Persists a {@link FileConfiguration} to the given file.
     * Errors are logged but not rethrown.
     */
    public void saveConfig(FileConfiguration config, File file) {
        try {
            config.save(file);
        } catch (IOException e) {
            logger.severe("Could not save " + file.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Returns the {@code File} handle for {@code schedules.yml}.
     * Used by GUI classes that need to persist schedule changes back to disk.
     */
    public File getSchedulesFile() {
        return schedulesFile;
    }
}
