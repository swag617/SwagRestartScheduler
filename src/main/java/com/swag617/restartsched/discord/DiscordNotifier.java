package com.swag617.restartsched.discord;

import com.swag617.restartsched.SwagRestartScheduler;
import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Sends Discord notifications via the optional DiscordUtils soft-dependency.
 *
 * <p>All calls are guarded by {@code Bukkit.getPluginManager().isPluginEnabled("DiscordUtils")}.
 * If DiscordUtils is absent, or if the reflection-based API lookup fails, all
 * methods become no-ops and a one-time warning is logged.</p>
 *
 * <h3>Reflection target</h3>
 * <p>We look for {@code me.swagbot.discordutils.DiscordUtils#sendWebhookMessage(String, String)}.
 * If the class or method cannot be found at first use the notifier disables itself silently.</p>
 *
 * <h3>Thread safety</h3>
 * <p>All network I/O is dispatched via {@code runTaskAsynchronously} to avoid
 * blocking the main thread.</p>
 */
public class DiscordNotifier {

    private final SwagRestartScheduler plugin;
    private final Logger logger;

    /** Set to {@code false} permanently if reflection resolution fails. */
    private volatile boolean available = true;
    private volatile boolean resolved  = false;

    // Reflection-cached fields (null until resolved)
    private Object  discordUtilsInstance  = null;
    private Method  sendWebhookMethod     = null;

    public DiscordNotifier(SwagRestartScheduler plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Public notification methods
    // -------------------------------------------------------------------------

    /**
     * Sends a "server restarting soon" notification.
     *
     * @param timeRemaining human-readable time string (e.g. "5m 30s")
     * @param playerCount   number of online players at dispatch time
     */
    public void sendScheduledRestartNotification(String timeRemaining, int playerCount) {
        if (!isDiscordEnabled()) return;
        if (!plugin.getConfig().getBoolean("discord.notifications.scheduled_restart.enabled", true)) return;

        String template = plugin.getConfig().getString(
                "discord.notifications.scheduled_restart.message",
                "Server restarting in {time}. Players online: {player_count}");

        String message = template
                .replace("{time}", timeRemaining)
                .replace("{player_count}", String.valueOf(playerCount));

        sendAsync(message);
    }

    /**
     * Sends a "manual restart initiated" notification.
     *
     * @param reason    reason string provided by the initiator
     * @param initiator display name of the player or "CONSOLE"
     */
    public void sendManualRestartNotification(String reason, String initiator) {
        if (!isDiscordEnabled()) return;
        if (!plugin.getConfig().getBoolean("discord.notifications.manual_restart.enabled", true)) return;

        String template = plugin.getConfig().getString(
                "discord.notifications.manual_restart.message",
                "Manual restart — {reason}. Initiated by: {initiator}");

        String message = template
                .replace("{reason}",    reason    != null ? reason    : "")
                .replace("{initiator}", initiator != null ? initiator : "unknown");

        sendAsync(message);
    }

    /**
     * Sends a "server is back online" notification.
     * Called from {@code SwagRestartScheduler.onEnable()}.
     */
    public void sendServerOnlineNotification() {
        if (!isDiscordEnabled()) return;
        if (!plugin.getConfig().getBoolean("discord.notifications.server_online.enabled", true)) return;

        String message = plugin.getConfig().getString(
                "discord.notifications.server_online.message",
                "Server has restarted successfully!");

        sendAsync(message);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private boolean isDiscordEnabled() {
        if (!available) return false;
        if (!plugin.getConfig().getBoolean("discord.enabled", true)) return false;
        return Bukkit.getPluginManager().isPluginEnabled("DiscordUtils");
    }

    /**
     * Dispatches the send operation on an async thread.
     */
    private void sendAsync(String message) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> doSend(message));
    }

    /**
     * Performs the actual webhook send via reflection.
     * Must be called off the main thread.
     */
    private void doSend(String message) {
        if (!available) return;

        // Lazy-resolve reflection target
        if (!resolved) {
            resolve();
            resolved = true;
        }

        if (!available || sendWebhookMethod == null) return;

        String webhookId = plugin.getConfig().getString("discord.webhook_id", "");
        if (webhookId.isBlank() || webhookId.equals("your_webhook_id")) {
            logger.fine("Discord webhook_id is not configured — skipping notification.");
            return;
        }

        try {
            sendWebhookMethod.invoke(discordUtilsInstance, webhookId, message);
        } catch (Exception e) {
            logger.warning("DiscordUtils sendWebhookMessage failed: " + e.getMessage()
                    + " — disabling Discord notifications.");
            available = false;
        }
    }

    /**
     * Attempts to locate the DiscordUtils API class and its
     * {@code sendWebhookMessage(String, String)} method via reflection.
     */
    private void resolve() {
        String[] candidateClasses = {
            "me.swagbot.discordutils.DiscordUtils",
            "net.essentialsx.discord.util.DiscordUtil",
            "com.discordutils.DiscordUtils"
        };

        for (String className : candidateClasses) {
            try {
                Class<?> cls = Class.forName(className);
                Method method = cls.getMethod("sendWebhookMessage", String.class, String.class);

                // Try static getInstance()
                Object instance = null;
                try {
                    Method getInstance = cls.getMethod("getInstance");
                    instance = getInstance.invoke(null);
                } catch (Exception ex) {
                    // Try getting it from the plugin manager
                    org.bukkit.plugin.Plugin duPlugin =
                            Bukkit.getPluginManager().getPlugin("DiscordUtils");
                    if (duPlugin != null) {
                        try {
                            Method getInst = duPlugin.getClass().getMethod("getInstance");
                            instance = getInst.invoke(duPlugin);
                        } catch (Exception ex2) {
                            instance = duPlugin;
                        }
                    }
                }

                if (instance != null) {
                    discordUtilsInstance = instance;
                    sendWebhookMethod    = method;
                    logger.info("DiscordUtils integration resolved via " + className);
                    return;
                }
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                // Try next
            }
        }

        logger.warning("DiscordUtils is enabled as a soft-dependency but its API "
                + "could not be resolved via reflection. Discord notifications disabled.");
        available = false;
    }
}
