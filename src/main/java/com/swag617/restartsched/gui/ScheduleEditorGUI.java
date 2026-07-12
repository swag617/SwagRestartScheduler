package com.swag617.restartsched.gui;

import com.swag617.restartsched.SwagRestartScheduler;
import com.swag617.restartsched.schedule.RestartSchedule;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 54-slot GUI for editing a single {@link RestartSchedule}.
 *
 * <p>A mutable working copy of the schedule's fields is held in this instance.
 * Changes are only persisted to {@code schedules.yml} when the player clicks
 * "Save & Close".  Closing the inventory without saving silently discards
 * changes.</p>
 *
 * <p>Slot layout:</p>
 * <pre>
 *  0– 8   filler (top border)
 *  9      filler
 * 10      Toggle enabled (GREEN_WOOL / RED_WOOL)
 * 11      filler
 * 12      filler
 * 13      Restart Times (CLOCK)
 * 14      filler
 * 15      filler
 * 16      Timezone (COMPASS)
 * 17      filler
 * 18–26   filler (middle gap)
 * 27      filler
 * 28      Active Days (GRASS_BLOCK)
 * 29–30   filler
 * 31      Save & Close (PAPER)
 * 32–33   filler
 * 34      Discard Changes (BARRIER)
 * 35      filler
 * 36–44   filler
 * 45–53   filler (bottom border) except slot 49 = Back arrow
 * </pre>
 */
public class ScheduleEditorGUI implements BaseGUI {

    private static final String TITLE_PREFIX = "<gold>Edit: ";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("EEE dd MMM HH:mm z");

    /** Ordered list of timezone options cycled through by clicking the Timezone item. */
    private static final List<String> TIMEZONE_OPTIONS = List.of(
            "America/New_York", "America/Chicago", "America/Denver",
            "America/Los_Angeles", "Europe/London", "Europe/Paris", "UTC"
    );

    private final SwagRestartScheduler plugin;
    private final String scheduleName;
    private final ScheduleListGUI parent;

    // ---- Mutable working copy ------------------------------------------------
    private boolean enabled;
    private ZoneId timezone;
    private final Set<DayOfWeek> activeDays;
    private final List<LocalTime> times;
    private int priority;
    // --------------------------------------------------------------------------

    private Inventory inventory;

    public ScheduleEditorGUI(SwagRestartScheduler plugin, RestartSchedule schedule,
                             ScheduleListGUI parent) {
        this.plugin        = plugin;
        this.scheduleName  = schedule.getName();
        this.parent        = parent;
        this.enabled       = schedule.isEnabled();
        this.timezone      = schedule.getTimezone();
        // EnumSet.copyOf throws on empty collection, so initialise explicitly
        Set<DayOfWeek> srcDays = schedule.getDays();
        this.activeDays = srcDays.isEmpty()
                ? EnumSet.noneOf(DayOfWeek.class)
                : EnumSet.copyOf(srcDays);
        this.times         = new ArrayList<>(schedule.getTimes());
        this.priority      = schedule.getPriority();
    }

    // -------------------------------------------------------------------------
    // BaseGUI
    // -------------------------------------------------------------------------

    @Override
    public void open(Player player) {
        inventory = Bukkit.createInventory(null, 54,
                MM.deserialize(TITLE_PREFIX + scheduleName));
        populate();
        player.openInventory(inventory);
        plugin.getGUIManager().register(player, this);
    }

    @Override
    public void handleClick(int slot, ClickType type, Player player) {
        switch (slot) {
            case 10 -> {
                // Toggle enabled
                enabled = !enabled;
                inventory.setItem(10, buildEnabledItem());
            }
            case 13 -> {
                // Edit times
                plugin.getGUIManager().unregister(player);
                new TimeEditGUI(plugin, scheduleName, times, this).open(player);
            }
            case 16 -> {
                // Cycle timezone
                cycleTimezone();
                inventory.setItem(16, buildTimezoneItem());
            }
            case 28 -> {
                // Edit active days
                plugin.getGUIManager().unregister(player);
                new DaySelectGUI(plugin, activeDays, this).open(player);
            }
            case 31 -> {
                // Save & Close
                saveToFile();
                plugin.getScheduleManager().reload();
                player.sendMessage(MM.deserialize(
                    "<green>Schedule <white>" + scheduleName + "</white> saved."));
                plugin.getGUIManager().unregister(player);
                parent.open(player);
            }
            case 34 -> {
                // Discard
                player.sendMessage(MM.deserialize("<yellow>Changes discarded."));
                plugin.getGUIManager().unregister(player);
                parent.open(player);
            }
            case 49 -> {
                // Back (alias for discard + back)
                plugin.getGUIManager().unregister(player);
                parent.open(player);
            }
            default -> { /* filler — do nothing */ }
        }
    }

    @Override
    public void handleClose(Player player) {
        // Auto-discard on inventory close (no save prompt)
        plugin.getGUIManager().unregister(player);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    // -------------------------------------------------------------------------
    // Expose mutable state for child GUIs (TimeEditGUI, DaySelectGUI)
    // -------------------------------------------------------------------------

    public List<LocalTime> getMutableTimes() {
        return times;
    }

    public Set<DayOfWeek> getMutableDays() {
        return activeDays;
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    /**
     * Writes the current working copy back to {@code schedules.yml}.
     * Called only when the player explicitly clicks "Save & Close".
     */
    private void saveToFile() {
        FileConfiguration cfg = plugin.getConfigManager().getSchedulesConfig();
        String path = "schedules." + scheduleName;

        cfg.set(path + ".enabled",  enabled);
        cfg.set(path + ".timezone", timezone.getId());

        List<String> dayList = activeDays.stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.toList());
        cfg.set(path + ".days", dayList);

        List<String> timeList = times.stream()
                .map(t -> t.format(TIME_FMT))
                .collect(Collectors.toList());
        cfg.set(path + ".times", timeList);
        cfg.set(path + ".priority", priority);

        plugin.getConfigManager().saveConfig(cfg, plugin.getConfigManager().getSchedulesFile());
    }

    // -------------------------------------------------------------------------
    // Internal rendering
    // -------------------------------------------------------------------------

    private void populate() {
        // Fill everything with filler first
        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, GuiItems.filler());
        }

        inventory.setItem(10, buildEnabledItem());
        inventory.setItem(13, buildTimesItem());
        inventory.setItem(16, buildTimezoneItem());
        inventory.setItem(28, buildDaysItem());
        inventory.setItem(31, GuiItems.save());
        inventory.setItem(34, GuiItems.make(Material.BARRIER, "<red>Discard Changes"));
        inventory.setItem(49, GuiItems.back());
    }

    private ItemStack buildEnabledItem() {
        Material mat = enabled ? Material.GREEN_WOOL : Material.RED_WOOL;
        String status = enabled ? "<green>Enabled" : "<red>Disabled";
        return GuiItems.make(mat, status, List.of(
                "<gray>Left-click to toggle",
                "<dark_gray>Schedule: <white>" + scheduleName
        ));
    }

    private ItemStack buildTimesItem() {
        List<String> lore = new ArrayList<>();
        if (times.isEmpty()) {
            lore.add("<gray><i>No times configured</i>");
        } else {
            for (LocalTime t : times) {
                lore.add("<white>" + t.format(TIME_FMT));
            }
        }
        lore.add("");
        lore.add("<gray>Left-click to edit");
        return GuiItems.make(Material.CLOCK, "<yellow>Restart Times", lore);
    }

    private ItemStack buildTimezoneItem() {
        // Compute next restart in this timezone for the preview
        String nextStr = computeNextRestartPreview();
        return GuiItems.make(Material.COMPASS, "<yellow>Timezone: <white>" + timezone.getId(),
                List.of(
                        "<gray>Left-click to cycle",
                        "<dark_gray>Next (preview): <white>" + nextStr
                ));
    }

    private ItemStack buildDaysItem() {
        String dayStr = activeDays.isEmpty() ? "<red>None" :
                activeDays.stream()
                        .map(d -> d.name().substring(0, 3))
                        .collect(Collectors.joining(", "));
        return GuiItems.make(Material.GRASS_BLOCK, "<yellow>Active Days",
                List.of(
                        "<white>" + dayStr,
                        "<gray>Left-click to edit"
                ));
    }

    private void cycleTimezone() {
        int current = TIMEZONE_OPTIONS.indexOf(timezone.getId());
        if (current < 0) current = 0;
        int next = (current + 1) % TIMEZONE_OPTIONS.size();
        timezone = ZoneId.of(TIMEZONE_OPTIONS.get(next));
    }

    /**
     * Builds a human-readable preview of the next restart given the current
     * working-copy settings.  Used only for display in the timezone item's lore.
     */
    private String computeNextRestartPreview() {
        if (activeDays.isEmpty() || times.isEmpty()) return "N/A";
        try {
            RestartSchedule preview = new RestartSchedule(
                    scheduleName, enabled, timezone, activeDays, times, priority);
            Optional<ZonedDateTime> next = preview.getNextRestart();
            return next.map(zdt -> zdt.format(DISPLAY_FMT)).orElse("N/A");
        } catch (Exception e) {
            return "N/A";
        }
    }
}
