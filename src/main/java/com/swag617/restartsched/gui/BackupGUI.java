package com.swag617.restartsched.gui;

import com.swag617.restartsched.SwagRestartScheduler;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 27-slot GUI for configuring the {@code backup} section of config.yml.
 *
 * <p>Slot layout:</p>
 * <pre>
 *  0– 8  filler (top border)
 *  9     filler
 * 10     Toggle: backup.enabled       (GREEN_SHULKER_BOX / RED_SHULKER_BOX)
 * 11     filler
 * 12     Toggle: backup.maintenance_mode (SHIELD, green / gray name)
 * 13     filler
 * 14     Toggle: backup.compress      (PAPER, green / gray name)
 * 15     filler
 * 16     Max Backups (WRITABLE_BOOK)  — left-click opens chat input
 * 17     filler
 * 18–21  filler
 * 22     Back (ARROW)
 * 23–26  filler
 * </pre>
 */
public class BackupGUI implements BaseGUI {

    static final String TITLE = "<gold>Backup Settings";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final SwagRestartScheduler plugin;
    private final MainMenuGUI parent;

    private Inventory inventory;

    public BackupGUI(SwagRestartScheduler plugin, MainMenuGUI parent) {
        this.plugin = plugin;
        this.parent = parent;
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
            case 10 -> {
                // Toggle backup.enabled
                boolean current = plugin.getConfig().getBoolean("backup.enabled", false);
                plugin.getConfig().set("backup.enabled", !current);
                plugin.saveConfig();
                plugin.getBackupManager().reload();
                inventory.setItem(10, buildEnabledToggleItem());
                player.sendMessage(MM.deserialize(
                    "<gray>Backup system " + (!current ? "<green>enabled" : "<red>disabled") + "<gray>."));
            }
            case 12 -> {
                // Toggle backup.maintenance_mode
                boolean current = plugin.getConfig().getBoolean("backup.maintenance_mode", true);
                plugin.getConfig().set("backup.maintenance_mode", !current);
                plugin.saveConfig();
                plugin.getBackupManager().reload();
                inventory.setItem(12, buildMaintenanceModeItem());
                player.sendMessage(MM.deserialize(
                    "<gray>Maintenance mode " + (!current ? "<green>enabled" : "<red>disabled") + "<gray>."));
            }
            case 14 -> {
                // Toggle backup.compress
                boolean current = plugin.getConfig().getBoolean("backup.compress", true);
                plugin.getConfig().set("backup.compress", !current);
                plugin.saveConfig();
                plugin.getBackupManager().reload();
                inventory.setItem(14, buildCompressItem());
                player.sendMessage(MM.deserialize(
                    "<gray>Compression " + (!current ? "<green>enabled" : "<red>disabled") + "<gray>."));
            }
            case 16 -> {
                // Edit max_backups via chat input
                player.sendMessage(MM.deserialize(
                    "<yellow>Type the new max backups value in chat. <gray>(0 = keep forever, current: "
                    + "<white>" + plugin.getConfig().getInt("backup.max_backups", 5) + "<gray>)"));
                plugin.getChatInputListener().registerInput(player.getUniqueId(), input -> {
                    int value;
                    try {
                        value = Integer.parseInt(input.trim());
                    } catch (NumberFormatException ex) {
                        player.sendMessage(MM.deserialize("<red>Invalid number: <white>" + input));
                        return;
                    }
                    if (value < 0) {
                        player.sendMessage(MM.deserialize("<red>Value must be 0 or greater."));
                        return;
                    }
                    plugin.getConfig().set("backup.max_backups", value);
                    plugin.saveConfig();
                    plugin.getBackupManager().reload();
                    if (inventory != null) {
                        inventory.setItem(16, buildMaxBackupsItem());
                    }
                    player.sendMessage(MM.deserialize(
                        "<green>Max backups set to <white>" + value + "<green>."));
                });
            }
            case 22 -> {
                // Back to main menu
                plugin.getGUIManager().unregister(player);
                parent.open(player);
            }
            default -> { /* filler — no action */ }
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
        inventory.setItem(10, buildEnabledToggleItem());
        inventory.setItem(12, buildMaintenanceModeItem());
        inventory.setItem(14, buildCompressItem());
        inventory.setItem(16, buildMaxBackupsItem());
        inventory.setItem(22, GuiItems.back());
    }

    private ItemStack buildEnabledToggleItem() {
        boolean enabled = plugin.getConfig().getBoolean("backup.enabled", false);
        Material mat    = enabled ? Material.GREEN_SHULKER_BOX : Material.RED_SHULKER_BOX;
        String status   = enabled ? "<green>Enabled" : "<red>Disabled";
        return GuiItems.make(mat,
            "<yellow>Backup: " + status,
            List.of("<gray>Left-click to toggle"));
    }

    private ItemStack buildMaintenanceModeItem() {
        boolean enabled = plugin.getConfig().getBoolean("backup.maintenance_mode", true);
        String status   = enabled ? "<green>Enabled" : "<gray>Disabled";
        return GuiItems.make(Material.SHIELD,
            "<yellow>Maintenance Mode: " + status,
            List.of("<gray>Left-click to toggle",
                    "<dark_gray>Uses Maintenance plugin if installed,",
                    "<dark_gray>otherwise falls back to /whitelist on"));
    }

    private ItemStack buildCompressItem() {
        boolean enabled = plugin.getConfig().getBoolean("backup.compress", true);
        String status   = enabled ? "<green>Enabled" : "<gray>Disabled";
        return GuiItems.make(Material.PAPER,
            "<yellow>Compress (.zip): " + status,
            List.of("<gray>Left-click to toggle"));
    }

    private ItemStack buildMaxBackupsItem() {
        int value = plugin.getConfig().getInt("backup.max_backups", 5);
        return GuiItems.make(Material.WRITABLE_BOOK,
            "<yellow>Max Backups: <white>" + value,
            List.of("<gray>Left-click to change",
                    "<dark_gray>0 = keep forever"));
    }
}
