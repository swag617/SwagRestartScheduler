package com.swag617.restartsched.warning;

import com.swag617.restartsched.SwagRestartScheduler;
import com.swag617.restartsched.discord.DiscordNotifier;
import com.swag617.restartsched.schedule.ScheduleManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Manages warning broadcasts, action bar countdowns, titles, subtitles, sounds, and the
 * final-countdown boss bar.
 *
 * <p>The {@link com.swag617.restartsched.task.RestartTask} calls
 * {@link #tick(long, String)} every second with the remaining millis; this class decides
 * which warnings to fire and sends them to all online players.</p>
 *
 * <p>Warnings that have already been fired are tracked in {@code firedWarnings} so
 * they do not repeat. Call {@link #reset(long)} when starting a new countdown.</p>
 */
public class WarningManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final SwagRestartScheduler plugin;
    private final Logger logger;

    /** Sorted descending by secondsRemaining so we can iterate from largest to smallest. */
    private List<WarningDefinition> warnings = new ArrayList<>();

    /** Tracks which warning thresholds (by secondsRemaining) have already been fired. */
    private final Set<Integer> firedWarnings = ConcurrentHashMap.newKeySet();

    /**
     * Tracks whether the Discord "scheduled_restart" notification has already been sent
     * for the current countdown. Reset in {@link #reset(long)} so exactly one Discord
     * ping is sent per countdown, on the first warning threshold crossed.
     */
    private final AtomicBoolean discordNotifiedThisCountdown = new AtomicBoolean(false);

    private int actionBarThreshold = 60;
    private String actionBarFormat  = "<red><bold>Restarting in {seconds}s";

    /**
     * Hard ceiling for {@link #bossBarThresholdSeconds}, enforced regardless of config.
     * A 30-60s boss bar was explicitly judged "too annoying" during review — this is
     * intentionally a final-moments-only countdown, never a long naggy one.
     */
    private static final int BOSSBAR_HARD_CAP_SECONDS = 10;

    private boolean bossBarEnabled = true;
    private int bossBarThresholdSeconds = BOSSBAR_HARD_CAP_SECONDS;
    private String bossBarTitleFormat = "<red><bold>{reason} — restarting in {seconds}s";
    private BossBar.Color bossBarColor = BossBar.Color.RED;
    private BossBar.Overlay bossBarOverlay = BossBar.Overlay.PROGRESS;

    /** The currently displayed final-countdown boss bar, or {@code null} if none is showing. */
    private BossBar activeBossBar = null;

    public WarningManager(SwagRestartScheduler plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Configuration loading
    // -------------------------------------------------------------------------

    /**
     * Loads warning definitions and action-bar settings from the plugin's config.
     * Safe to call on reload — replaces the previous definition list.
     */
    public void reload() {
        List<WarningDefinition> loaded = new ArrayList<>();

        var warningsSection = plugin.getConfig().getConfigurationSection("warnings");
        if (warningsSection == null || !warningsSection.getBoolean("enabled", true)) {
            logger.info("Warnings are disabled in config.yml.");
            warnings = loaded;
            return;
        }

        var intervalsList = warningsSection.getMapList("intervals");
        for (var rawMap : intervalsList) {
            // getMapList returns List<Map<?,?>> — cast to a typed map for clean access
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) rawMap;
            try {
                Object secObj = map.get("seconds");
                if (!(secObj instanceof Number)) {
                    logger.warning("Warning entry missing 'seconds' field — skipped.");
                    continue;
                }
                int seconds = ((Number) secObj).intValue();
                if (seconds < 0) {
                    logger.warning("Warning entry has negative 'seconds' — skipped.");
                    continue;
                }
                String message  = map.get("message")  instanceof String s1 ? s1 : null;
                String title    = map.get("title")    instanceof String s2 ? s2 : null;
                String subtitle = map.get("subtitle") instanceof String s3 ? s3 : null;
                String sound    = map.get("sound")    instanceof String s4 ? s4 : null;
                loaded.add(new WarningDefinition(seconds, message, title, subtitle, sound));
            } catch (ClassCastException | NullPointerException e) {
                logger.warning("Could not parse warning entry: " + e.getMessage());
            }
        }

        // Sort descending so we iterate from the furthest-out warning inward
        loaded.sort(Comparator.comparingInt(WarningDefinition::getSecondsRemaining).reversed());
        warnings = loaded;
        logger.info("Loaded " + loaded.size() + " warning definition(s).");

        actionBarThreshold = warningsSection.getInt("action-bar-threshold", 60);
        actionBarFormat    = warningsSection.getString("action-bar-format", "<red><bold>Restarting in {seconds}s");

        // BossBar countdown — final moments only (see BOSSBAR_HARD_CAP_SECONDS)
        bossBarEnabled = plugin.getConfig().getBoolean("boss_bar.enabled", true);
        int configuredThreshold = plugin.getConfig().getInt("boss_bar.seconds-threshold", BOSSBAR_HARD_CAP_SECONDS);
        bossBarThresholdSeconds = Math.max(0, Math.min(configuredThreshold, BOSSBAR_HARD_CAP_SECONDS));
        bossBarTitleFormat = plugin.getConfig().getString(
                "boss_bar.title-format", "<red><bold>{reason} — restarting in {seconds}s");
        bossBarColor   = parseBarColor(plugin.getConfig().getString("boss_bar.color", "RED"));
        bossBarOverlay = parseBarOverlay(plugin.getConfig().getString("boss_bar.overlay", "PROGRESS"));
    }

    // -------------------------------------------------------------------------
    // Runtime API
    // -------------------------------------------------------------------------

    /**
     * Called once per second by {@link com.swag617.restartsched.task.RestartTask}.
     *
     * @param millisRemaining wall-clock milliseconds until restart
     * @param reason          the restart's human-readable reason string, used as part of the
     *                        final-countdown boss bar title (see {@link #updateBossBar})
     */
    public void tick(long millisRemaining, String reason) {
        long secondsRemaining = millisRemaining / 1000L;

        // Fire any warnings whose threshold we have just crossed
        for (WarningDefinition def : warnings) {
            int threshold = def.getSecondsRemaining();
            if (secondsRemaining <= threshold && !firedWarnings.contains(threshold)) {
                firedWarnings.add(threshold);
                fireWarning(def, secondsRemaining);
            }
        }

        // Action bar countdown
        if (actionBarThreshold > 0 && secondsRemaining <= actionBarThreshold && secondsRemaining > 0) {
            String formatted = actionBarFormat.replace("{seconds}", String.valueOf(secondsRemaining));
            Component actionBar = MM.deserialize(formatted);
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                player.sendActionBar(actionBar);
            }
        }

        // BossBar countdown — final BOSSBAR_HARD_CAP_SECONDS seconds only. Deliberately not
        // shown any earlier: a 30-60s bar was judged too annoying during review.
        if (bossBarEnabled && secondsRemaining > 0 && secondsRemaining <= bossBarThresholdSeconds) {
            updateBossBar(secondsRemaining, reason);
        }
    }

    /**
     * Resets the fired-warnings tracker. Must be called whenever a new countdown starts.
     *
     * @param totalMillis total countdown duration in milliseconds — warnings whose
     *                    threshold exceeds this duration are pre-marked as fired so
     *                    they are never sent (e.g. a 30-min warning won't fire for
     *                    a "/srestart in 1m" countdown).
     */
    public void reset(long totalMillis) {
        firedWarnings.clear();
        discordNotifiedThisCountdown.set(false);
        // A fresh countdown is starting — tear down any boss bar left over from a previous
        // one (should normally already be gone via RestartTask's cancel/hit-zero paths, but
        // guard against a fresh countdown starting while one is still showing).
        hideBossBar();
        long totalSeconds = totalMillis / 1000L;
        for (WarningDefinition def : warnings) {
            if (def.getSecondsRemaining() > totalSeconds) {
                firedWarnings.add(def.getSecondsRemaining());
            }
        }
    }

    /**
     * Broadcasts a cancellation message to all online players.
     *
     * @param mmMessage MiniMessage-formatted string
     */
    public void broadcastCancelled(String mmMessage) {
        if (mmMessage == null || mmMessage.isBlank()) return;
        Component component = MM.deserialize(mmMessage);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(component);
        }
    }

    // -------------------------------------------------------------------------
    // BossBar countdown (final moments only)
    // -------------------------------------------------------------------------

    /**
     * Creates (or updates) the final-countdown boss bar shown to all online players.
     * Title is {@code boss_bar.title-format} with {@code {reason}} and {@code {seconds}}
     * placeholders; progress is {@code secondsRemaining / bossBarThresholdSeconds}.
     */
    private void updateBossBar(long secondsRemaining, String reason) {
        String formatted = bossBarTitleFormat
                .replace("{reason}", reason != null ? reason : "Server restart")
                .replace("{seconds}", String.valueOf(secondsRemaining));
        Component title = MM.deserialize(formatted);

        float progress = bossBarThresholdSeconds > 0
                ? (float) Math.max(0.0, Math.min(1.0, secondsRemaining / (double) bossBarThresholdSeconds))
                : 1.0f;

        if (activeBossBar == null) {
            activeBossBar = BossBar.bossBar(title, progress, bossBarColor, bossBarOverlay);
        } else {
            activeBossBar.name(title);
            activeBossBar.progress(progress);
        }

        // Idempotent: re-showing to an existing viewer is a harmless no-op, and this also
        // picks up any player who joined mid-countdown.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.showBossBar(activeBossBar);
        }
    }

    /**
     * Removes the final-countdown boss bar from all viewers, if one is currently showing.
     * Safe to call even when no bar is active (no-op). Must be called whenever a countdown
     * is cancelled or reaches zero — see {@link com.swag617.restartsched.task.RestartTask}.
     */
    public void hideBossBar() {
        if (activeBossBar == null) return;
        BossBar bar = activeBossBar;
        activeBossBar = null;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.hideBossBar(bar);
        }
    }

    private BossBar.Color parseBarColor(String name) {
        if (name != null) {
            try {
                return BossBar.Color.valueOf(name.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                logger.warning("Unknown boss_bar.color '" + name + "' — defaulting to RED.");
            }
        }
        return BossBar.Color.RED;
    }

    private BossBar.Overlay parseBarOverlay(String name) {
        if (name != null) {
            try {
                return BossBar.Overlay.valueOf(name.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                logger.warning("Unknown boss_bar.overlay '" + name + "' — defaulting to PROGRESS.");
            }
        }
        return BossBar.Overlay.PROGRESS;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void fireWarning(WarningDefinition def, long secondsRemaining) {
        logger.fine("Firing warning at " + def.getSecondsRemaining() + "s threshold (actual: " + secondsRemaining + "s).");

        // Discord: send the "restarting soon" ping once per countdown, on the first
        // warning threshold crossed (mirrors the comment in RestartTask.executeRestart()
        // that assumed this already happened — it never actually fired until this wiring).
        if (discordNotifiedThisCountdown.compareAndSet(false, true)) {
            DiscordNotifier discordNotifier = plugin.getDiscordNotifier();
            if (discordNotifier != null) {
                String timeRemaining = ScheduleManager.formatDuration(secondsRemaining * 1000L);
                int playerCount = plugin.getServer().getOnlinePlayers().size();
                discordNotifier.sendScheduledRestartNotification(timeRemaining, playerCount);
            }
        }

        // Chat broadcast
        if (def.getMessage() != null && !def.getMessage().isBlank()) {
            Component msg = MM.deserialize(def.getMessage());
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                player.sendMessage(msg);
            }
        }

        // Title + subtitle
        boolean hasTitle    = def.getTitle()    != null && !def.getTitle().isBlank();
        boolean hasSubtitle = def.getSubtitle() != null && !def.getSubtitle().isBlank();
        if (hasTitle || hasSubtitle) {
            Component titleComp    = hasTitle    ? MM.deserialize(def.getTitle())    : Component.empty();
            Component subtitleComp = hasSubtitle ? MM.deserialize(def.getSubtitle()) : Component.empty();

            Title.Times times = Title.Times.times(
                    Duration.ofMillis(500),
                    Duration.ofSeconds(3),
                    Duration.ofMillis(500)
            );
            Title title = Title.title(titleComp, subtitleComp, times);

            for (Player player : plugin.getServer().getOnlinePlayers()) {
                player.showTitle(title);
            }
        }

        // Sound
        if (def.getSound() != null && !def.getSound().isBlank()) {
            Sound sound = resolveSound(def.getSound());
            if (sound != null) {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
                }
            } else {
                logger.warning("Unknown sound '" + def.getSound() + "' in warning definition — skipped.");
            }
        }
    }

    private Sound resolveSound(String name) {
        if (name == null) return null;
        try {
            return Sound.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
