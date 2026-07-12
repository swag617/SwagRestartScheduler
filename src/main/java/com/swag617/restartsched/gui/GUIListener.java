package com.swag617.restartsched.gui;

import com.swag617.restartsched.SwagRestartScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * Routes {@link InventoryClickEvent} and {@link InventoryCloseEvent} to the
 * correct {@link BaseGUI} implementation via the {@link GUIManager}.
 *
 * <p>Detection strategy: we look up the player's UUID in {@link GUIManager}
 * rather than matching inventory titles, which is more robust and avoids
 * MiniMessage-to-plain-text conversion issues.  If a registration exists and
 * the clicked inventory matches the registered GUI's backing inventory, the
 * event is cancelled and dispatched.</p>
 */
public class GUIListener implements Listener {

    private final SwagRestartScheduler plugin;

    public GUIListener(SwagRestartScheduler plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Click handling
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        BaseGUI gui = plugin.getGUIManager().getOpenGUI(player.getUniqueId());
        if (gui == null) return;

        // Only intercept clicks inside our custom inventory (not the player's own inv)
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(gui.getInventory())) return;

        // Always cancel — prevent taking items out of the GUI
        event.setCancelled(true);

        int slot = event.getSlot();
        if (slot < 0) return;

        gui.handleClick(slot, event.getClick(), player);
    }

    // -------------------------------------------------------------------------
    // Close handling
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        BaseGUI gui = plugin.getGUIManager().getOpenGUI(player.getUniqueId());
        if (gui == null) return;

        // Only fire handleClose if the closed inventory is ours
        if (event.getInventory().equals(gui.getInventory())) {
            gui.handleClose(player);
        }
    }
}
