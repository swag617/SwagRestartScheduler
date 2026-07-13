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
 * <p>We target the real {@code com.swag.discordutils.DiscordUtils} plugin (package
 * {@code com.swag.discordutils}), via:</p>
 * <pre>
 * DiscordUtils.getInstance().getDiscordBot().sendMessage(String)
 * </pre>
 * <p><b>Limitation:</b> the real DiscordUtils plugin has no webhook-ID concept and no
 * per-notification-type channel routing — {@code DiscordBot#sendMessage(String)} always
 * posts to whichever channel DiscordUtils' own {@code chat.channel-id} config key points
 * at. The old {@code discord.webhook_id} config key from this plugin's config.yml has been
 * removed since it never mapped to anything real; all three notification types
 * (scheduled_restart, manual_restart, server_online) are now routed to that single channel.
 * If per-notification-type channel routing is ever needed, DiscordUtils would need to expose
 * a small {@code ServicesManager}-registered interface (mirroring SwagAPI's {@code IWebService})
 * instead of this reflection-by-class-name approach.</p>
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
    private Object  discordBotInstance = null;
    private Method  sendMessageMethod  = null;

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

    /**
     * Sends an urgent "crash loop detected" alert.
     *
     * <p>Unlike the other notification types, this one is not individually gated by a
     * {@code discord.notifications.*.enabled} toggle — a crash loop is always alert-worthy —
     * but it still respects the top-level {@code discord.enabled} switch and DiscordUtils
     * availability via {@link #isDiscordEnabled()}.</p>
     *
     * @param crashCount      number of unclean shutdowns detected within the window
     * @param windowMinutes   the detection window, in minutes
     * @param cooldownMinutes how long scheduled/performance-triggered restarts are suppressed
     */
    public void sendCrashLoopAlert(int crashCount, int windowMinutes, int cooldownMinutes) {
        if (!isDiscordEnabled()) return;

        String template = plugin.getConfig().getString(
                "crash-loop-safe-mode.discord-message",
                "⚠ CRASH LOOP DETECTED: {crash_count} unclean shutdown(s) within {window}m. "
                        + "Scheduled/performance-triggered restarts suppressed for {cooldown}m.");

        String message = template
                .replace("{crash_count}", String.valueOf(crashCount))
                .replace("{window}", String.valueOf(windowMinutes))
                .replace("{cooldown}", String.valueOf(cooldownMinutes));

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
     * Performs the actual send via reflection into DiscordUtils' DiscordBot.
     * Must be called off the main thread.
     */
    private void doSend(String message) {
        if (!available) return;

        // Lazy-resolve reflection target
        if (!resolved) {
            resolve();
            resolved = true;
        }

        if (!available || sendMessageMethod == null) return;

        try {
            sendMessageMethod.invoke(discordBotInstance, message);
        } catch (Exception e) {
            logger.warning("DiscordUtils DiscordBot#sendMessage failed: " + e.getMessage()
                    + " — disabling Discord notifications.");
            available = false;
        }
    }

    /**
     * Attempts to locate the real DiscordUtils API shape via reflection:
     * {@code com.swag.discordutils.DiscordUtils#getInstance()} then
     * {@code #getDiscordBot()} then {@code DiscordBot#sendMessage(String)}.
     */
    private void resolve() {
        final String className = "com.swag.discordutils.DiscordUtils";

        try {
            Class<?> duClass = Class.forName(className);

            Method getInstance = duClass.getMethod("getInstance");
            Object duInstance = getInstance.invoke(null);
            if (duInstance == null) {
                logger.warning("DiscordUtils.getInstance() returned null — Discord notifications disabled.");
                available = false;
                return;
            }

            Method getDiscordBotMethod = duClass.getMethod("getDiscordBot");
            Object discordBot = getDiscordBotMethod.invoke(duInstance);
            if (discordBot == null) {
                logger.warning("DiscordUtils.getInstance().getDiscordBot() returned null "
                        + "(bot likely failed to connect — check DiscordUtils' bot-token config) "
                        + "— Discord notifications disabled.");
                available = false;
                return;
            }

            Method sendMessage = discordBot.getClass().getMethod("sendMessage", String.class);

            discordBotInstance = discordBot;
            sendMessageMethod  = sendMessage;
            logger.info("DiscordUtils integration resolved via " + className
                    + "#getDiscordBot().sendMessage(String).");
            return;
        } catch (ClassNotFoundException e) {
            logger.warning("DiscordUtils plugin class (" + className + ") not found — "
                    + "Discord notifications disabled.");
        } catch (NoSuchMethodException e) {
            logger.warning("Installed DiscordUtils does not expose the expected API shape "
                    + "(getInstance/getDiscordBot/sendMessage): " + e.getMessage()
                    + " — Discord notifications disabled. This may be an incompatible version of DiscordUtils.");
        } catch (Exception e) {
            logger.warning("Failed to resolve DiscordUtils integration: " + e.getMessage()
                    + " — Discord notifications disabled.");
        }

        available = false;
    }
}
