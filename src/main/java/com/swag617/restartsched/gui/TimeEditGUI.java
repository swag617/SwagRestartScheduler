package com.swag617.restartsched.gui;

import com.swag617.restartsched.SwagRestartScheduler;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 54-slot GUI for editing the list of restart times on a schedule.
 *
 * <p>Each existing time is displayed as a CLOCK item; left-clicking removes it.
 * The "Add Time" button (slot 49) prompts the player to type a time in chat via
 * {@link ChatInputListener}.</p>
 *
 * <p>Times are stored as a mutable {@link List} provided by the caller
 * ({@link ScheduleEditorGUI}), so changes here are immediately reflected there.</p>
 *
 * <p>Slot layout (54 slots):</p>
 * <pre>
 *  0– 8   filler
 *  9–44   time items (up to 36 times; rare to have that many)
 * 45      Back arrow
 * 46–48   filler
 * 49      Add Time (GREEN_DYE)
 * 50–52   filler
 * 53      Close (BARRIER)
 * </pre>
 */
public class TimeEditGUI implements BaseGUI {

    private static final String TITLE_PREFIX = "<gold>Edit Times: ";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Slots used to display time items. */
    private static final int TIME_SLOT_START = 9;
    private static final int TIME_SLOT_END   = 44; // inclusive

    private final SwagRestartScheduler plugin;
    private final String scheduleName;
    private final List<LocalTime> times;     // mutable list from ScheduleEditorGUI
    private final ScheduleEditorGUI parent;

    private Inventory inventory;

    public TimeEditGUI(SwagRestartScheduler plugin, String scheduleName,
                       List<LocalTime> times, ScheduleEditorGUI parent) {
        this.plugin        = plugin;
        this.scheduleName  = scheduleName;
        this.times         = times;
        this.parent        = parent;
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
        if (slot == 49) {
            // Add Time — prompt via chat
            plugin.getGUIManager().unregister(player);
            player.closeInventory();

            player.sendMessage(MM.deserialize(
                "<gray>Type a time <white>(HH:mm)</white> in chat to add it. " +
                "Type <red>'cancel'</red> to abort."));

            plugin.getChatInputListener().registerInput(player.getUniqueId(), input -> {
                if (!input.equalsIgnoreCase("cancel")) {
                    try {
                        LocalTime parsed = LocalTime.parse(input.trim(), TIME_FMT);
                        if (!times.contains(parsed)) {
                            times.add(parsed);
                            times.sort(LocalTime::compareTo);
                            player.sendMessage(MM.deserialize(
                                "<green>Added time <white>" + parsed.format(TIME_FMT) + "</white>."));
                        } else {
                            player.sendMessage(MM.deserialize(
                                "<yellow>That time is already in the list."));
                        }
                    } catch (DateTimeParseException e) {
                        player.sendMessage(MM.deserialize(
                            "<red>Invalid time format. Use <white>HH:mm</white> (24-hour), e.g. <white>03:00</white>."));
                    }
                } else {
                    player.sendMessage(MM.deserialize("<gray>Add time cancelled."));
                }
                // Reopen the GUI after handling input
                open(player);
            });
            return;
        }

        // Time slot — left-click removes
        if (slot >= TIME_SLOT_START && slot <= TIME_SLOT_END && type == ClickType.LEFT) {
            int index = slot - TIME_SLOT_START;
            if (index < times.size()) {
                LocalTime removed = times.remove(index);
                player.sendMessage(MM.deserialize(
                    "<red>Removed time <white>" + removed.format(TIME_FMT) + "</white>."));
                // Refresh the GUI in place
                repopulate();
            }
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
        // Fill everything
        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, GuiItems.filler());
        }

        // Time items
        for (int i = 0; i < times.size() && i <= (TIME_SLOT_END - TIME_SLOT_START); i++) {
            inventory.setItem(TIME_SLOT_START + i, buildTimeItem(times.get(i)));
        }

        // Nav/action
        inventory.setItem(45, GuiItems.back());
        inventory.setItem(49, GuiItems.make(Material.LIME_DYE, "<green>Add Time",
                List.of("<gray>Click to type a new time in chat")));
        inventory.setItem(53, GuiItems.close());
    }

    /** Re-renders the time items and fixed buttons without reopening the inventory. */
    private void repopulate() {
        if (inventory == null) return;

        // Clear time area
        for (int i = TIME_SLOT_START; i <= TIME_SLOT_END; i++) {
            inventory.setItem(i, GuiItems.filler());
        }
        for (int i = 0; i < times.size() && i <= (TIME_SLOT_END - TIME_SLOT_START); i++) {
            inventory.setItem(TIME_SLOT_START + i, buildTimeItem(times.get(i)));
        }
    }

    private ItemStack buildTimeItem(LocalTime time) {
        return GuiItems.make(Material.CLOCK,
                "<white>" + time.format(TIME_FMT),
                List.of("<gray>Left-click to <red>remove</red>"));
    }
}
