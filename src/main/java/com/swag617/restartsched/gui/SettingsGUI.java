package com.swag617.restartsched.gui;

import com.swag617.restartsched.SwagRestartScheduler;
import com.swag617.restartsched.schedule.ScheduleManager;
import com.swag617.restartsched.task.RestartTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 27-slot settings GUI.
 *
 * <p>Slot layout:</p>
 * <pre>
 *  0– 8  filler (top border)
 *  9     filler
 * 10     Warning System toggle (CLOCK)
 * 11     filler
 * 12     filler
 * 13     Reload Config (PAPER)
 * 14     filler
 * 15     filler
 * 16     Next Restart Info (COMPASS)
 * 17     filler
 * 18–21  filler
 * 22     Back (ARROW)
 * 23–26  filler
 * </pre>
 */
public class SettingsGUI implements BaseGUI {

    static final String TITLE = "<gold>Settings";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("EEE dd MMM HH:mm z");

    private final SwagRestartScheduler plugin;
    private final MainMenuGUI parent;

    private Inventory inventory;

    public SettingsGUI(SwagRestartScheduler plugin, MainMenuGUI parent) {
        this.plugin  = plugin;
        this.parent  = parent;
    }

    // -------------------------------------------------------------------------
    // BaseGUI
    // -------------------------------------------------------------------------

    @Override
    public void open(Player player) {
        inventory = Bukkit.createInventory(null, 27, MM.deserialize(TITLE));
        populate();
        player.openInventory(inventory);
        plugin.getGUIManager().register(player, this);
    }

    @Override
    public void handleClick(int slot, ClickType type, Player player) {
        switch (slot) {
            case 11 -> {
                // Toggle warning system
                boolean current = plugin.getConfig().getBoolean("warnings.enabled", true);
                plugin.getConfig().set("warnings.enabled", !current);
                plugin.saveConfig();
                plugin.getWarningManager().reload();
                player.sendMessage(MM.deserialize(
                    "<gray>Warning system " + (!current ? "<green>enabled" : "<red>disabled") + "<gray>."));
                // Refresh item
                inventory.setItem(11, buildWarningToggleItem());
            }
            case 13 -> {
                // Reload config
                try {
                    plugin.getConfigManager().reload();
                    plugin.getWarningManager().reload();
                    plugin.getScheduleManager().reload();
                    player.sendMessage(MM.deserialize("<green>Configuration reloaded successfully."));
                } catch (Exception e) {
                    player.sendMessage(MM.deserialize("<red>Reload failed: <white>" + e.getMessage()));
                }
                // Refresh next restart info
                inventory.setItem(15, buildNextRestartItem());
            }
            case 15 -> {
                // Info only — refresh display
                inventory.setItem(15, buildNextRestartItem());
            }
            case 22 -> {
                // Back
                plugin.getGUIManager().unregister(player);
                parent.open(player);
            }
            default -> { /* filler */ }
        }
    }

    @Override
    public void handleClose(Player player) {
        plugin.getGUIManager().unregister(player);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void populate() {
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, GuiItems.filler());
        }
        inventory.setItem(11, buildWarningToggleItem());
        inventory.setItem(13, GuiItems.make(Material.PAPER, "<yellow>Reload Config",
                List.of("<gray>Reloads all configuration files")));
        inventory.setItem(15, buildNextRestartItem());
        inventory.setItem(22, GuiItems.back());
    }

    private org.bukkit.inventory.ItemStack buildWarningToggleItem() {
        boolean warningsEnabled = plugin.getConfig().getBoolean("warnings.enabled", true);
        String status = warningsEnabled ? "<green>Enabled" : "<red>Disabled";
        return GuiItems.make(Material.CLOCK, "<yellow>Warning System: " + status,
                List.of("<gray>Left-click to toggle"));
    }

    private org.bukkit.inventory.ItemStack buildNextRestartItem() {
        List<String> lore = new ArrayList<>();

        RestartTask active = plugin.getScheduleManager().getActiveTask();
        if (active != null) {
            long millis = active.getMillisRemaining();
            lore.add("<gray>Active task: <white>" + active.getSourceName());
            lore.add("<gray>Time remaining: <white>" + ScheduleManager.formatDuration(millis));
            lore.add("<gray>Reason: <white>" + active.getReason());
        } else {
            Optional<ZonedDateTime> next = plugin.getScheduleManager().findNextRestart();
            if (next.isPresent()) {
                ZonedDateTime zdt = next.get();
                long millis = zdt.toInstant().toEpochMilli() - System.currentTimeMillis();
                lore.add("<gray>Next scheduled: <white>" + zdt.format(DISPLAY_FMT));
                lore.add("<gray>In: <white>" + ScheduleManager.formatDuration(millis));
            } else {
                lore.add("<gray>No upcoming restart scheduled.");
            }
        }
        lore.add("");
        lore.add("<dark_gray>Click to refresh");

        return GuiItems.make(Material.COMPASS, "<yellow>Next Restart Info", lore);
    }
}
