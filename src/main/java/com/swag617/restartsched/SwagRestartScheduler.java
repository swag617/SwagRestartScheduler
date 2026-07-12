package com.swag617.restartsched;

import com.swag617.restartsched.automation.PreRestartCommandExecutor;
import com.swag617.restartsched.backup.BackupManager;
import com.swag617.restartsched.command.RestartCommand;
import com.swag617.restartsched.config.ConfigManager;
import com.swag617.restartsched.discord.DiscordNotifier;
import com.swag617.restartsched.grace.GracePeriodHandler;
import com.swag617.restartsched.gui.ChatInputListener;
import com.swag617.restartsched.gui.GUIListener;
import com.swag617.restartsched.gui.GUIManager;
import com.swag617.restartsched.logging.RestartLogger;
import com.swag617.restartsched.performance.PerformanceTrigger;
import com.swag617.restartsched.performance.TpsMonitor;
import com.swag617.restartsched.schedule.ScheduleManager;
import com.swag617.restartsched.warning.WarningManager;
import com.swag617.restartsched.web.WebEditorModule;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * SwagRestartScheduler — Phase 3
 *
 * <p>Entry point for the plugin.  Initialises all managers in dependency order
 * and registers the {@code /restart} command.</p>
 *
 * <h3>Manager initialisation order (onEnable)</h3>
 * <ol>
 *   <li>{@link ConfigManager}             — must be first; everything else reads config</li>
 *   <li>{@link RestartLogger}             — no dependencies on other managers</li>
 *   <li>{@link WarningManager}            — reads warning definitions from config</li>
 *   <li>{@link GracePeriodHandler}        — reads grace_period section from config</li>
 *   <li>{@link PreRestartCommandExecutor} — reads pre_restart section from config</li>
 *   <li>{@link DiscordNotifier}           — soft-dep on DiscordUtils</li>
 *   <li>{@link GUIManager}               — registry for open GUIs</li>
 *   <li>{@link ChatInputListener}         — chat input capture for GUIs</li>
 *   <li>{@link GUIListener}              — routes click events to GUIs</li>
 *   <li>{@link ScheduleManager}          — reads schedules; starts the countdown task</li>
 *   <li>{@link TpsMonitor}               — (Phase 3) rolling TPS window</li>
 *   <li>{@link PerformanceTrigger}        — (Phase 3) evaluates TPS threshold</li>
 * </ol>
 *
 * <h3>Shutdown order (onDisable)</h3>
 * <ol>
 *   <li>{@link PerformanceTrigger#stop()} — cancels eval task</li>
 *   <li>{@link TpsMonitor#stop()}         — cancels sampling task</li>
 *   <li>{@link ScheduleManager#shutdown()} — cancels active BukkitRunnable</li>
 *   <li>{@link GUIManager#shutdown()}      — clears GUI registry</li>
 * </ol>
 */
public class SwagRestartScheduler extends JavaPlugin {

    // Phase 1 singletons
    private ConfigManager   configManager;
    private RestartLogger   restartLogger;
    private WarningManager  warningManager;
    private ScheduleManager scheduleManager;

    // Phase 2 singletons
    private GracePeriodHandler        gracePeriodHandler;
    private PreRestartCommandExecutor preRestartCommandExecutor;
    private DiscordNotifier           discordNotifier;
    private GUIManager                guiManager;
    private ChatInputListener         chatInputListener;
    private GUIListener               guiListener;

    // Phase 3 singletons
    private TpsMonitor        tpsMonitor;
    private PerformanceTrigger performanceTrigger;

    // Backup system
    private BackupManager backupManager;

    // Web editor
    private WebEditorModule webEditorModule;

    // -------------------------------------------------------------------------
    // JavaPlugin lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();

        // 1. Config — must be first
        configManager = new ConfigManager(this);
        configManager.initialLoad();

        // 1a. Backup manager — reads config, no I/O beyond that
        backupManager = new BackupManager(this);
        backupManager.reload();

        // 2. Logger
        restartLogger = new RestartLogger(this);

        // 3. Warning manager
        warningManager = new WarningManager(this);
        warningManager.reload();

        // 4. Phase 2 — Grace period
        gracePeriodHandler = new GracePeriodHandler(this);

        // 5. Phase 2 — Pre-restart commands
        preRestartCommandExecutor = new PreRestartCommandExecutor(this);

        // 6. Phase 2 — Discord notifier
        discordNotifier = new DiscordNotifier(this);

        // 7. Phase 2 — GUI infrastructure
        guiManager        = new GUIManager(this);
        chatInputListener = new ChatInputListener(this);
        guiListener       = new GUIListener(this);
        getServer().getPluginManager().registerEvents(guiListener,       this);
        getServer().getPluginManager().registerEvents(chatInputListener, this);

        // 8. Schedule manager — loads schedules and starts the first task
        scheduleManager = new ScheduleManager(this);
        scheduleManager.reload();

        // 9. Phase 3 — TPS monitor + performance trigger
        tpsMonitor         = new TpsMonitor(this);
        performanceTrigger = new PerformanceTrigger(this, tpsMonitor);
        performanceTrigger.start(); // starts tpsMonitor internally if enabled

        // 10. Phase 3 — Copy web config editor to data folder
        saveWebEditor();

        // 11. Web editor — registers with SwagAPI's shared IWebService (soft dep)
        webEditorModule = new WebEditorModule(this);
        webEditorModule.enable();

        // 13. Command
        registerCommand();

        // 14. Discord: server came back online notification
        discordNotifier.sendServerOnlineNotification();

        long elapsed = System.currentTimeMillis() - start;
        getLogger().info("SwagRestartScheduler enabled in " + elapsed + "ms.");
    }

    @Override
    public void onDisable() {
        // Web editor — unregister from SwagAPI's shared IWebService
        if (webEditorModule != null) {
            webEditorModule.disable();
        }

        // Phase 3 shutdown first
        if (performanceTrigger != null) {
            performanceTrigger.stop();
        }
        if (tpsMonitor != null) {
            tpsMonitor.stop();
        }

        // Phase 1/2 shutdown
        if (scheduleManager != null) {
            scheduleManager.shutdown();
        }
        if (guiManager != null) {
            guiManager.shutdown();
        }
        getLogger().info("SwagRestartScheduler disabled.");
    }

    // -------------------------------------------------------------------------
    // Command registration
    // -------------------------------------------------------------------------

    private void registerCommand() {
        PluginCommand cmd = getCommand("srestart");
        if (cmd == null) {
            getLogger().severe("Could not find 'srestart' command in plugin.yml! Commands will not work.");
            return;
        }
        RestartCommand handler = new RestartCommand(this);
        cmd.setExecutor(handler);
        cmd.setTabCompleter(handler);
    }

    // -------------------------------------------------------------------------
    // Web editor
    // -------------------------------------------------------------------------

    /**
     * Copies {@code web/config-editor.html} from the plugin jar to the data folder
     * if it does not already exist there.
     */
    private void saveWebEditor() {
        File webDir = new File(getDataFolder(), "web");
        File editorFile = new File(webDir, "config-editor.html");

        if (!editorFile.exists()) {
            try {
                saveResource("web/config-editor.html", false);
                getLogger().info("Web config editor copied to: " + editorFile.getAbsolutePath());
            } catch (Exception e) {
                getLogger().warning("Could not copy web/config-editor.html: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Accessors (used by managers and commands)
    // -------------------------------------------------------------------------

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public RestartLogger getRestartLogger() {
        return restartLogger;
    }

    public WarningManager getWarningManager() {
        return warningManager;
    }

    public ScheduleManager getScheduleManager() {
        return scheduleManager;
    }

    // Phase 2 accessors

    public GracePeriodHandler getGracePeriodHandler() {
        return gracePeriodHandler;
    }

    public PreRestartCommandExecutor getPreRestartCommandExecutor() {
        return preRestartCommandExecutor;
    }

    public DiscordNotifier getDiscordNotifier() {
        return discordNotifier;
    }

    public GUIManager getGUIManager() {
        return guiManager;
    }

    public ChatInputListener getChatInputListener() {
        return chatInputListener;
    }

    // Phase 3 accessors

    public TpsMonitor getTpsMonitor() {
        return tpsMonitor;
    }

    public PerformanceTrigger getPerformanceTrigger() {
        return performanceTrigger;
    }

    // Backup accessor

    public BackupManager getBackupManager() {
        return backupManager;
    }

    // Web editor accessor

    public WebEditorModule getWebEditorModule() {
        return webEditorModule;
    }
}
