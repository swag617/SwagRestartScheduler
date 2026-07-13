package com.swag617.restartsched.gui;

import com.swag617.restartsched.SwagRestartScheduler;
import com.swag617.restartsched.schedule.RestartSchedule;
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

/**
 * 54-slot GUI listing all configured schedules, paginated {@value #PAGE_SIZE} at a time.
 *
 * <p>Schedules occupy slots 0–44 (up to {@value #PAGE_SIZE} entries per page).  Each item is
 * GREEN_WOOL (enabled) or RED_WOOL (disabled).</p>
 * <ul>
 *   <li>Left-click → open {@link ScheduleEditorGUI}</li>
 *   <li>Right-click → toggle enabled / disabled and save immediately</li>
 *   <li>Shift-left-click → prompt (via chat) to permanently delete the schedule</li>
 * </ul>
 *
 * <p>Bottom bar (row 6, slots 45–53):</p>
 * <pre>
 * 45=Back  46=Prev Page  47-48=filler  49=Page Indicator  50-51=filler  52=Next Page  53=Close
 * </pre>
 */
public class ScheduleListGUI implements BaseGUI {

    static final String TITLE = "<gold>Schedules";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("EEE dd MMM HH:mm z");

    private static final int PAGE_SIZE = 45; // slots 0–44

    private final SwagRestartScheduler plugin;
    private final MainMenuGUI parent;

    private Inventory inventory;
    /** Full snapshot of all schedules, unpaginated. */
    private List<RestartSchedule> allSchedules;
    /** Zero-indexed current page. */
    private int page = 0;

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
        allSchedules = new ArrayList<>(plugin.getScheduleManager().getSchedules());
        // Clamp the page in case schedules shrank since this GUI was last open
        int maxPage = Math.max(0, (allSchedules.size() - 1) / PAGE_SIZE);
        if (page > maxPage) page = maxPage;
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
        if (slot == 46) {
            // Previous page
            if (page > 0) {
                page--;
                populate();
            }
            return;
        }
        if (slot == 52) {
            // Next page
            if (hasNextPage()) {
                page++;
                populate();
            }
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE) return;

        List<RestartSchedule> pageSchedules = currentPageSchedules();
        if (slot >= pageSchedules.size()) return;
        RestartSchedule schedule = pageSchedules.get(slot);

        if (type == ClickType.LEFT) {
            // Open editor
            plugin.getGUIManager().unregister(player);
            new ScheduleEditorGUI(plugin, schedule, this).open(player);
        } else if (type == ClickType.RIGHT) {
            // Toggle enabled immediately
            toggleEnabled(schedule, player);
        } else if (type == ClickType.SHIFT_LEFT) {
            // Prompt to permanently delete this schedule
            promptDelete(schedule, player);
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

    private boolean hasNextPage() {
        return (page + 1) * PAGE_SIZE < allSchedules.size();
    }

    private int pageCount() {
        return Math.max(1, (allSchedules.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** Returns the slice of {@link #allSchedules} that belongs on the current page. */
    private List<RestartSchedule> currentPageSchedules() {
        int from = page * PAGE_SIZE;
        if (from >= allSchedules.size()) return List.of();
        int to = Math.min(from + PAGE_SIZE, allSchedules.size());
        return allSchedules.subList(from, to);
    }

    private void populate() {
        // Bottom bar filler
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, GuiItems.filler());
        }
        inventory.setItem(45, GuiItems.back());
        inventory.setItem(53, GuiItems.close());
        inventory.setItem(46, buildPrevPageItem());
        inventory.setItem(52, buildNextPageItem());
        inventory.setItem(49, buildPageIndicatorItem());

        // Schedule items for the current page
        List<RestartSchedule> pageSchedules = currentPageSchedules();
        for (int i = 0; i < pageSchedules.size(); i++) {
            inventory.setItem(i, buildScheduleItem(pageSchedules.get(i)));
        }

        // Fill empty content slots with filler
        for (int i = pageSchedules.size(); i < PAGE_SIZE; i++) {
            inventory.setItem(i, GuiItems.filler());
        }
    }

    private org.bukkit.inventory.ItemStack buildPrevPageItem() {
        if (page <= 0) {
            return GuiItems.make(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>Previous Page",
                    List.of("<dark_gray>Already on the first page"));
        }
        return GuiItems.make(Material.ARROW, "<yellow>Previous Page",
                List.of("<gray>Go to page <white>" + page + "</white>/<white>" + pageCount()));
    }

    private org.bukkit.inventory.ItemStack buildNextPageItem() {
        if (!hasNextPage()) {
            return GuiItems.make(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>Next Page",
                    List.of("<dark_gray>Already on the last page"));
        }
        return GuiItems.make(Material.ARROW, "<yellow>Next Page",
                List.of("<gray>Go to page <white>" + (page + 2) + "</white>/<white>" + pageCount()));
    }

    private org.bukkit.inventory.ItemStack buildPageIndicatorItem() {
        return GuiItems.make(Material.PAPER,
                "<yellow>Page <white>" + (page + 1) + "</white>/<white>" + pageCount(),
                List.of("<gray>" + allSchedules.size() + " schedule(s) total"));
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
        lore.add("<yellow>Shift-left-click <gray>to delete");

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

    /**
     * Prompts (via chat, mirroring the {@link com.swag617.restartsched.gui.ChatInputListener}
     * pattern used elsewhere for destructive/text actions) for confirmation before
     * permanently removing a schedule's section from {@code schedules.yml}.
     *
     * <p>There was previously no way to delete a schedule from the GUI at all — the only
     * option was hand-editing {@code schedules.yml} and running {@code /srestart reload}.</p>
     */
    private void promptDelete(RestartSchedule schedule, Player player) {
        plugin.getGUIManager().unregister(player);
        player.closeInventory();

        String name = schedule.getName();
        player.sendMessage(MM.deserialize(
            "<red>Type <white>CONFIRM</white> in chat to permanently delete schedule <white>"
            + name + "</white>. <gray>Type anything else to cancel."));

        plugin.getChatInputListener().registerInput(player.getUniqueId(), input -> {
            if (input.trim().equalsIgnoreCase("CONFIRM")) {
                FileConfiguration cfg = plugin.getConfigManager().getSchedulesConfig();
                cfg.set("schedules." + name, null);
                plugin.getConfigManager().saveConfig(cfg, plugin.getConfigManager().getSchedulesFile());
                plugin.getScheduleManager().reload();
                player.sendMessage(MM.deserialize(
                    "<green>Schedule <white>" + name + "</white> deleted."));
            } else {
                player.sendMessage(MM.deserialize("<gray>Deletion cancelled."));
            }
            open(player);
        });
    }
}
