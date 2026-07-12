package com.swag617.restartsched.gui;

import com.swag617.restartsched.SwagRestartScheduler;
import com.swag617.restartsched.schedule.RestartSchedule;
import com.swag617.restartsched.schedule.ScheduleManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 54-slot GUI listing all configured schedules.
 *
 * <p>Schedules occupy slots 0–44 (up to 45 entries).  Each item is
 * GREEN_WOOL (enabled) or RED_WOOL (disabled).</p>
 * <ul>
 *   <li>Left-click → open {@link ScheduleEditorGUI}</li>
 *   <li>Right-click → toggle enabled / disabled and save immediately</li>
 * </ul>
 *
 * <p>Bottom bar (row 6, slots 45–53):</p>
 * <pre>
 * 45=Back  46–52=filler  53=Close
 * </pre>
 */
public class ScheduleListGUI implements BaseGUI {

    static final String TITLE = "<gold>Schedules";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("EEE dd MMM HH:mm z");

    private static final int MAX_SCHEDULE_SLOTS = 45; // slots 0–44

    private final SwagRestartScheduler plugin;
    private final MainMenuGUI parent;

    private Inventory inventory;
    /** Snapshot of schedules ordered the same way as the inventory slots. */
    private List<RestartSchedule> orderedSchedules;

    public ScheduleListGUI(SwagRestartScheduler plugin, MainMenuGUI parent) {
        this.plugin  = plugin;
        this.parent  = parent;
    }

    // -------------------------------------------------------------------------
    // BaseGUI
    // -------------------------------------------------------------------------

    @Override
    public void open(Player player) {
        inventory = Bukkit.createInventory(null, 54, MM.deserialize(TITLE));
        orderedSchedules = new ArrayList<>(plugin.getScheduleManager().getSchedules());
        populate();
        player.openInventory(inventory);
        plugin.getGUIManager().register(player, this);
    }

    @Override
    public void handleClick(int slot, ClickType type, Player player) {
        if (slot == 45) {
            // Back
            plugin.getGUIManager().unregister(player);
            parent.open(player);
            return;
        }
        if (slot == 53) {
            // Close
            plugin.getGUIManager().unregister(player);
            player.closeInventory();
            return;
        }
        if (slot < 0 || slot >= MAX_SCHEDULE_SLOTS) return;

        int index = slot;
        if (index >= orderedSchedules.size()) return;
        RestartSchedule schedule = orderedSchedules.get(index);

        if (type == ClickType.LEFT) {
            // Open editor
            plugin.getGUIManager().unregister(player);
            new ScheduleEditorGUI(plugin, schedule, this).open(player);
        } else if (type == ClickType.RIGHT) {
            // Toggle enabled immediately
            toggleEnabled(schedule, player);
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
        // Bottom bar filler
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, GuiItems.filler());
        }
        inventory.setItem(45, GuiItems.back());
        inventory.setItem(53, GuiItems.close());

        // Schedule items
        for (int i = 0; i < orderedSchedules.size() && i < MAX_SCHEDULE_SLOTS; i++) {
            inventory.setItem(i, buildScheduleItem(orderedSchedules.get(i)));
        }

        // Fill empty content slots with filler
        for (int i = orderedSchedules.size(); i < MAX_SCHEDULE_SLOTS; i++) {
            inventory.setItem(i, GuiItems.filler());
        }
    }

    private org.bukkit.inventory.ItemStack buildScheduleItem(RestartSchedule s) {
        Material mat = s.isEnabled() ? Material.GREEN_WOOL : Material.RED_WOOL;
        String status = s.isEnabled() ? "<green>Enabled" : "<red>Disabled";

        String nextStr = s.getNextRestart()
                .map(zdt -> zdt.format(DISPLAY_FMT))
                .orElse("N/A");

        String timeZone = s.getTimezone().getId();

        List<String> lore = new ArrayList<>();
        lore.add(status);
        lore.add("<gray>Timezone: <white>" + timeZone);
        lore.add("<gray>Next restart: <white>" + nextStr);
        lore.add("");
        lore.add("<yellow>Left-click <gray>to edit");
        lore.add("<yellow>Right-click <gray>to toggle");

        return GuiItems.make(mat, "<white>" + s.getName(), lore);
    }

    /**
     * Toggles a schedule's enabled flag in {@code schedules.yml} immediately
     * (no editor needed for a simple on/off toggle) then reloads schedules.
     */
    private void toggleEnabled(RestartSchedule schedule, Player player) {
        FileConfiguration cfg = plugin.getConfigManager().getSchedulesConfig();
        String path = "schedules." + schedule.getName() + ".enabled";
        boolean newState = !schedule.isEnabled();
        cfg.set(path, newState);
        plugin.getConfigManager().saveConfig(cfg, plugin.getConfigManager().getSchedulesFile());
        plugin.getScheduleManager().reload();

        player.sendMessage(MM.deserialize(
            "<gray>Schedule <white>" + schedule.getName() + "</white> "
            + (newState ? "<green>enabled" : "<red>disabled") + "<gray>."));

        // Refresh this GUI with updated data
        plugin.getGUIManager().unregister(player);
        open(player);
    }
}
