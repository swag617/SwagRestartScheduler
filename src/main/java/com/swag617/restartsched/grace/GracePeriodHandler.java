package com.swag617.restartsched.grace;

import com.swag617.restartsched.SwagRestartScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks whether a server restart should be delayed based on configurable
 * "grace period" conditions.
 *
 * <h3>Conditions supported</h3>
 * <ul>
 *   <li><b>combat</b> — at least one online player is in combat, detected via
 *       the optional CombatLogX soft-dependency.  If CombatLogX is absent the
 *       combat condition is ignored.</li>
 *   <li><b>worlds</b> — at least one online player is in a world whose name
 *       matches any entry in the configured world list.  Entries support a
 *       simple {@code *} wildcard, e.g. {@code "dungeon_*"}.</li>
 *   <li><b>min-players-online</b> — the server's online player count is at least
 *       {@code grace_period.conditions.min-players-online} (0 disables this check).</li>
 * </ul>
 *
 * <h3>Grace period lifecycle</h3>
 * <ol>
 *   <li>When the countdown reaches zero, {@link com.swag617.restartsched.task.RestartTask}
 *       calls {@link #shouldDelay(long)} with the epoch-millis at which the
 *       restart was originally due.</li>
 *   <li>If {@code shouldDelay} returns {@code true} the task reschedules itself
 *       for {@code check_interval_seconds} later.</li>
 *   <li>Once {@code max_delay_minutes} has elapsed since the original due time
 *       the method always returns {@code false}, forcing the restart regardless
 *       of conditions.</li>
 * </ol>
 */
public class GracePeriodHandler {

    private final SwagRestartScheduler plugin;
    private final Logger logger;

    // CombatLogX reflection cache — resolved once on first use
    private boolean combatLogXChecked = false;
    private Method  combatLogXIsInCombatMethod = null;
    private Object  combatLogXApiInstance      = null;

    public GracePeriodHandler(SwagRestartScheduler plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the restart should be delayed.
     *
     * <p>Always returns {@code false} (force restart) once
     * {@code max_delay_minutes} has elapsed since {@code originalDueMillis}.</p>
     *
     * @param originalDueMillis the epoch-millis at which the restart was
     *                          originally scheduled to execute
     */
    public boolean shouldDelay(long originalDueMillis) {
        if (!isEnabled()) return false;

        long maxDelayMs = plugin.getConfig()
                .getLong("grace_period.max_delay_minutes", 15) * 60_000L;

        // If we have exceeded the maximum grace period, force the restart
        if (System.currentTimeMillis() - originalDueMillis >= maxDelayMs) {
            logger.info("Grace period max delay reached — proceeding with restart.");
            return false;
        }

        // Check conditions
        boolean checkCombat = plugin.getConfig().getBoolean("grace_period.conditions.combat", true);
        List<String> worldPatterns = plugin.getConfig().getStringList("grace_period.conditions.worlds");

        if (checkCombat && isAnyCombat()) {
            broadcastDelay();
            return true;
        }

        if (!worldPatterns.isEmpty() && isAnyPlayerInProtectedWorld(worldPatterns)) {
            broadcastDelay();
            return true;
        }

        int minPlayersOnline = plugin.getConfig().getInt("grace_period.conditions.min-players-online", 0);
        if (minPlayersOnline > 0 && Bukkit.getOnlinePlayers().size() >= minPlayersOnline) {
            broadcastDelay();
            return true;
        }

        return false;
    }

    /** Returns {@code true} if the grace period system is enabled in config. */
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("grace_period.enabled", false);
    }

    /** Returns the configured check interval in ticks (seconds * 20). */
    public long getCheckIntervalTicks() {
        int seconds = plugin.getConfig().getInt("grace_period.check_interval_seconds", 5);
        return Math.max(1L, seconds) * 20L;
    }

    // -------------------------------------------------------------------------
    // Condition checks
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if any online player is currently in combat.
     * Uses CombatLogX API via reflection; returns {@code false} if unavailable.
     */
    private boolean isAnyCombat() {
        if (!Bukkit.getPluginManager().isPluginEnabled("CombatLogX")) return false;

        // Lazy-resolve the reflection target
        if (!combatLogXChecked) {
            resolveCombatLogX();
            combatLogXChecked = true;
        }

        if (combatLogXIsInCombatMethod == null || combatLogXApiInstance == null) return false;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("swagrestart.bypass.grace")) continue;
            try {
                Object result = combatLogXIsInCombatMethod.invoke(combatLogXApiInstance, player);
                if (Boolean.TRUE.equals(result)) return true;
            } catch (Exception e) {
                // Reflection failure — log once and disable
                logger.warning("CombatLogX reflection failed: " + e.getMessage() + " — disabling combat check.");
                combatLogXIsInCombatMethod = null;
                return false;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if any online player (not bypassing grace) is in a
     * world whose name matches at least one of the given patterns.
     */
    private boolean isAnyPlayerInProtectedWorld(List<String> patterns) {
        List<Pattern> compiled = compilePatterns(patterns);
        if (compiled.isEmpty()) return false;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("swagrestart.bypass.grace")) continue;
            String worldName = player.getWorld().getName();
            for (Pattern p : compiled) {
                if (p.matcher(worldName).matches()) return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void broadcastDelay() {
        String msg = plugin.getConfig().getString(
                "grace_period.message", "<yellow>Restart delayed - players in protected area");
        net.kyori.adventure.text.Component component =
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(msg);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(component);
        }
        logger.info("Grace period active — restart delayed.");
    }

    /**
     * Compiles wildcard world-name patterns to regex {@link Pattern}s.
     * {@code *} is converted to {@code .*}; the rest is quoted.
     */
    private List<Pattern> compilePatterns(List<String> entries) {
        List<Pattern> result = new java.util.ArrayList<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            try {
                // Convert simple wildcard: split on *, quote each segment, rejoin with .*
                String[] parts = entry.split("\\*", -1);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) sb.append(".*");
                    sb.append(Pattern.quote(parts[i]));
                }
                result.add(Pattern.compile("^" + sb + "$", Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                logger.warning("Grace period: invalid world pattern '" + entry + "' — skipped.");
            }
        }
        return result;
    }

    /**
     * Attempts to locate the CombatLogX API class and its
     * {@code isInCombat(Player)} (or equivalent) method via reflection.
     * Sets {@link #combatLogXApiInstance} and {@link #combatLogXIsInCombatMethod}
     * if successful, otherwise leaves them null.
     */
    private void resolveCombatLogX() {
        // CombatLogX v11+ API: me.lokka30.combatlogx.api.CombatLogXAPI
        // Older builds may use different class names — we try the most common ones.
        String[] candidateClasses = {
            "me.lokka30.combatlogx.api.CombatLogXAPI",
            "combatlogx.api.CombatLogXAPI",
            "me.sage.combatlogx.CombatLogX"
        };
        String[] candidateMethods = {"isInCombat", "isTagged", "isInCombat"};

        for (int i = 0; i < candidateClasses.length; i++) {
            try {
                Class<?> apiClass = Class.forName(candidateClasses[i]);
                Method method = apiClass.getMethod(candidateMethods[i], Player.class);

                // Try to get the singleton instance via a static getter
                Object instance = null;
                try {
                    Method getInstance = apiClass.getMethod("getInstance");
                    instance = getInstance.invoke(null);
                } catch (Exception ex) {
                    // Some versions expose the API object differently; try the plugin itself
                    org.bukkit.plugin.Plugin clxPlugin =
                            Bukkit.getPluginManager().getPlugin("CombatLogX");
                    if (clxPlugin != null) {
                        try {
                            Method getApi = clxPlugin.getClass().getMethod("getAPI");
                            instance = getApi.invoke(clxPlugin);
                        } catch (Exception ex2) {
                            instance = clxPlugin; // last resort: the plugin class itself
                        }
                    }
                }

                if (instance != null) {
                    combatLogXApiInstance      = instance;
                    combatLogXIsInCombatMethod = method;
                    logger.info("CombatLogX integration resolved via " + candidateClasses[i]);
                    return;
                }
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                // Try next candidate
            }
        }

        logger.warning("CombatLogX is enabled but its API could not be resolved via reflection. "
                + "Combat-based grace period condition will be skipped.");
    }
}
