package com.swag617.restartsched.gui;

import com.swag617.restartsched.SwagRestartScheduler;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central registry and factory for all in-game GUIs.
 *
 * <p>Tracks which {@link BaseGUI} is currently open for each player and exposes
 * a {@link #openMainMenu(Player)} factory method used by the command handler.
 * The {@link GUIListener} calls {@link #getOpenGUI(UUID)} to route click events
 * to the correct implementation.</p>
 *
 * <p>All methods must be called on the main server thread.</p>
 */
public class GUIManager {

    private final SwagRestartScheduler plugin;

    /** UUID → currently open GUI for that player. */
    private final Map<UUID, BaseGUI> openGUIs = new HashMap<>();

    public GUIManager(SwagRestartScheduler plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Registry
    // -------------------------------------------------------------------------

    /**
     * Registers a GUI as the currently open GUI for the given player.
     * Any previous registration is silently replaced.
     */
    public void register(Player player, BaseGUI gui) {
        openGUIs.put(player.getUniqueId(), gui);
    }

    /**
     * Removes the GUI registration for the given player.
     * Safe to call even if the player has no registration.
     */
    public void unregister(Player player) {
        openGUIs.remove(player.getUniqueId());
    }

    /**
     * Returns the currently open {@link BaseGUI} for the given UUID,
     * or {@code null} if none is registered.
     */
    public BaseGUI getOpenGUI(UUID uuid) {
        return openGUIs.get(uuid);
    }

    // -------------------------------------------------------------------------
    // Factory / entry points
    // -------------------------------------------------------------------------

    /** Opens the main menu for the given player. */
    public void openMainMenu(Player player) {
        new MainMenuGUI(plugin).open(player);
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    /** Clears all open GUI registrations. Called from {@code onDisable}. */
    public void shutdown() {
        openGUIs.clear();
    }
}
