package com.swag617.restartsched.gui;

import com.swag617.restartsched.SwagRestartScheduler;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 27-slot main menu GUI — the entry point for all GUI navigation.
 *
 * <p>Slot layout:</p>
 * <pre>
 *  0– 8  filler (top border)
 *  9     filler
 * 10     filler
 * 11     View Schedules (CLOCK)
 * 12     filler
 * 13     Create Schedule (CLOCK) — prompts for a name via chat, then opens
 *        {@link ScheduleEditorGUI} seeded with defaults
 * 14     filler
 * 15     Settings (ANVIL)
 * 16     filler
 * 17     filler
 * 18     filler
 * 19     filler
 * 20     Backup Settings (CHEST)
 * 21     filler
 * 22     Restart Logs (BOOK)
 * 23–26  filler
 * </pre>
 */
public class MainMenuGUI implements BaseGUI {

    public static final String TITLE = "<gold>Restart Scheduler";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final SwagRestartScheduler plugin;

    private Inventory inventory;

    public MainMenuGUI(SwagRestartScheduler plugin) {
        this.plugin = plugin;
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
                // View Schedules
                plugin.getGUIManager().unregister(player);
                new ScheduleListGUI(plugin, this).open(player);
            }
            case 13 -> {
                // Create Schedule — prompt for a name via chat, then open the editor
                promptCreateSchedule(player);
            }
            case 15 -> {
                // Settings
                plugin.getGUIManager().unregister(player);
                new SettingsGUI(plugin, this).open(player);
            }
            case 20 -> {
                // Backup Settings
                plugin.getGUIManager().unregister(player);
                new BackupGUI(plugin, this).open(player);
            }
            case 22 -> {
                // Restart Logs
                showLogs(player);
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

        inventory.setItem(11, GuiItems.make(Material.CLOCK, "<yellow>View Schedules",
                List.of("<gray>Browse and edit restart schedules")));
        inventory.setItem(13, GuiItems.make(Material.CLOCK, "<yellow>Create Schedule",
                List.of("<gray>Click to name a new schedule")));
        inventory.setItem(15, GuiItems.make(Material.ANVIL, "<yellow>Settings",
                List.of("<gray>Toggle warnings, reload config")));
        inventory.setItem(20, GuiItems.make(Material.CHEST, "<yellow>Backup Settings",
                List.of("<gray>Configure the pre-restart backup system")));
        inventory.setItem(22, GuiItems.make(Material.BOOK, "<yellow>Restart Logs",
                List.of("<gray>View the last 10 restart entries")));
    }

    /**
     * Prompts the player via chat for a new schedule name, validates it (non-blank,
     * safe characters only, not already in use), then opens {@link ScheduleEditorGUI}
     * seeded with defaults. Nothing is written to {@code schedules.yml} until the
     * player clicks "Save & Close" in the editor.
     */
    private void promptCreateSchedule(Player player) {
        plugin.getGUIManager().unregister(player);
        player.closeInventory();
        player.sendMessage(MM.deserialize(
            "<yellow>Type a name for the new schedule in chat. "
            + "<gray>(letters, numbers, '-' and '_' only; type <red>'cancel'</red> to abort)"));

        plugin.getChatInputListener().registerInput(player.getUniqueId(), input -> {
            String trimmed = input.trim();

            if (trimmed.equalsIgnoreCase("cancel")) {
                player.sendMessage(MM.deserialize("<gray>Schedule creation cancelled."));
                open(player);
                return;
            }
            if (trimmed.isBlank()) {
                player.sendMessage(MM.deserialize("<red>Name cannot be blank."));
                open(player);
                return;
            }
            if (!trimmed.matches("[A-Za-z0-9_-]+")) {
                player.sendMessage(MM.deserialize(
                    "<red>Invalid name — only letters, numbers, '-' and '_' are allowed "
                    + "(no spaces, dots, or colons)."));
                open(player);
                return;
            }
            boolean duplicate = plugin.getScheduleManager().getSchedules().stream()
                    .anyMatch(s -> s.getName().equalsIgnoreCase(trimmed));
            if (duplicate) {
                player.sendMessage(MM.deserialize(
                    "<red>A schedule named <white>" + trimmed + "</white> already exists."));
                open(player);
                return;
            }

            new ScheduleEditorGUI(plugin, trimmed, new ScheduleListGUI(plugin, this)).open(player);
        });
    }

    /**
     * Replaces the Restart Logs item in the open inventory with a live lore
     * snapshot of the last 10 log entries so the player can read them without
     * leaving the GUI.
     */
    private void showLogs(Player player) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>--- Last 10 restarts ---");

        try {
            File logFile = new File(plugin.getDataFolder(), "logs/restart-log.yml");
            if (!logFile.exists()) {
                lore.add("<gray><i>No log entries yet.</i>");
            } else {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(logFile);
                var restartsSection = yaml.getConfigurationSection("restarts");
                if (restartsSection == null) {
                    lore.add("<gray><i>No log entries yet.</i>");
                } else {
                    // Keys are epoch seconds — sort descending for newest first
                    List<String> keys = new ArrayList<>(restartsSection.getKeys(false));
                    keys.sort((a, b) -> {
                        try {
                            return Long.compare(Long.parseLong(b), Long.parseLong(a));
                        } catch (NumberFormatException e) {
                            return b.compareTo(a);
                        }
                    });

                    int count = 0;
                    for (String key : keys) {
                        if (count >= 10) break;
                        var entry = restartsSection.getConfigurationSection(key);
                        if (entry == null) continue;

                        String ts        = entry.getString("timestamp", "?");
                        String initiator = entry.getString("initiator", "?");
                        String type      = entry.getString("type", "?");
                        String reason    = entry.getString("reason", "");

                        // Truncate timestamp to date+time for compact display
                        String shortTs = ts.length() >= 16 ? ts.substring(0, 16) : ts;
                        lore.add("<white>" + shortTs + " <gray>" + type + " <dark_gray>by <white>" + initiator);
                        if (!reason.isBlank()) {
                            lore.add("<dark_gray>  " + reason);
                        }
                        count++;
                    }
                    if (keys.isEmpty()) {
                        lore.add("<gray><i>No log entries yet.</i>");
                    }
                }
            }
        } catch (Exception e) {
            lore.add("<red>Error reading log file.");
            plugin.getLogger().warning("Failed to read restart log for GUI: " + e.getMessage());
        }

        // Update the logs item in the open inventory
        if (inventory != null) {
            inventory.setItem(22, GuiItems.make(Material.BOOK, "<yellow>Restart Logs", lore));
        }
        player.sendMessage(MM.deserialize("<gray>Log entries loaded — hover the <white>Restart Logs</white> item to read."));
    }
}
