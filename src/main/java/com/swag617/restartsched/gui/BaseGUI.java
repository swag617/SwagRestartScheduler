package com.swag617.restartsched.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Common contract for all SwagRestartScheduler GUI screens.
 *
 * <p>Implementations hold their own {@link Inventory} instance and are
 * responsible for populating it in {@link #open(Player)}.  The
 * {@link GUIListener} routes click events back to the implementation via
 * {@link #handleClick(int, org.bukkit.event.inventory.ClickType, Player)}.</p>
 */
public interface BaseGUI {

    /**
     * Build, populate, and open this GUI for the given player.
     * Always called on the main thread.
     */
    void open(Player player);

    /**
     * Handle a slot click inside this GUI.
     *
     * @param slot  the clicked slot index
     * @param type  the click type (LEFT, RIGHT, etc.)
     * @param player the player who clicked
     */
    void handleClick(int slot, org.bukkit.event.inventory.ClickType type, Player player);

    /**
     * Called when the player closes this GUI.
     * Implementations should clean up any state here.
     */
    default void handleClose(Player player) { }

    /**
     * Returns the backing {@link Inventory}, or {@code null} if not yet opened.
     */
    Inventory getInventory();
}
