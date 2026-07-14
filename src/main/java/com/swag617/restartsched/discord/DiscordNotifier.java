package com.swag617.restartsched.discord;

import com.SwagDev.SwagAPI.api.IEventBusService;
import com.SwagDev.SwagAPI.events.SwagCrossPluginMessageEvent;
import com.swag617.restartsched.SwagRestartScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Sends Discord notifications via SwagAPI's shared {@link IEventBusService}, publishing
 * to the {@code discordutils:notify} channel — DiscordUtils (if installed and a
 * matching named webhook is configured) picks it up and posts it, with zero compile-time
 * or reflection-based coupling to DiscordUtils itself.
 *
 * <p>This replaces an earlier reflection-based integration
 * ({@code DiscordUtils.getInstance().getDiscordBot().sendMessage(String)}) that had no
 * per-notification-type channel routing — every message went wherever DiscordUtils'
 * {@code chat.channel-id} pointed. The named-webhook payload below fixes that: restart
 * notifications now get their own {@code webhooks.restart} entry in DiscordUtils'
 * config.yml, independent of its chat channel.</p>
 *
 * <h3>Thread safety</h3>
 * <p>All network I/O (the actual Discord webhook POST) happens inside DiscordUtils,
 * off SwagRestartScheduler's thread entirely — {@link IEventBusService#publish} itself
 * is a synchronous, in-process call, so no async dispatch is needed here.</p>
 */
public class DiscordNotifier {

    private final SwagRestartScheduler plugin;
    private final Logger logger;

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

        send(message);
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

        send(message);
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

        send(message);
    }

    /**
     * Sends an urgent "crash loop detected" alert.
     *
     * <p>Unlike the other notification types, this one is not individually gated by a
     * {@code discord.notifications.*.enabled} toggle — a crash loop is always alert-worthy —
     * but it still respects the top-level {@code discord.enabled} switch.</p>
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

        send(message);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private boolean isDiscordEnabled() {
        return plugin.getConfig().getBoolean("discord.enabled", true);
    }

    /**
     * Publishes the message on SwagAPI's event bus for DiscordUtils to pick up.
     * No-ops (with a one-time-per-call warning) if SwagAPI's event bus isn't registered.
     */
    private void send(String message) {
        RegisteredServiceProvider<IEventBusService> rsp =
                Bukkit.getServicesManager().getRegistration(IEventBusService.class);
        if (rsp == null) {
            logger.warning("discord.enabled is true but SwagAPI's event bus isn't available — notification dropped.");
            return;
        }

        String webhookName = plugin.getConfig().getString("discord.webhook-name", "restart");

        Map<String, Object> data = new HashMap<>();
        data.put("webhook", webhookName);
        data.put("content", message);
        data.put("username", "Server Status");

        rsp.getProvider().publish(new SwagCrossPluginMessageEvent(
                "discordutils:notify", "SwagRestartScheduler", data, null));
    }
}
