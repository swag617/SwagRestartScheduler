package com.swag617.restartsched.gui;

import com.swag617.restartsched.SwagRestartScheduler;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.Set;

/**
 * 27-slot GUI for toggling which days of the week are active for a schedule.
 *
 * <p>The caller provides a mutable {@link Set} of {@link DayOfWeek}; clicks toggle
 * days in that set directly.  The "Done" button closes the GUI and the caller's
 * set already reflects the chosen days — no save step needed here.</p>
 *
 * <p>Slot layout:</p>
 * <pre>
 *  0  1  2  3  4  5  6  7  8   ← filler row
 *  9 [M][T][W][T][F][S][S] 17  ← day toggles (slots 10-16)
 * 18 19 20 21[Done]23 24 25 26  ← Done = slot 22
 * </pre>
 */
public class DaySelectGUI implements BaseGUI {

    static final String TITLE = "<gold>Select Days";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    // Day of week → slot mapping
    private static final DayOfWeek[] SLOT_TO_DAY = new DayOfWeek[27];
    private static final int[] DAY_TO_SLOT = new int[8]; // index by DayOfWeek.getValue() (1=Mon … 7=Sun)

    static {
        DayOfWeek[] ordered = {
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
        };
        for (int i = 0; i < ordered.length; i++) {
            int slot = 10 + i;
            SLOT_TO_DAY[slot] = ordered[i];
            DAY_TO_SLOT[ordered[i].getValue()] = slot;
        }
    }

    private final SwagRestartScheduler plugin;
    private final Set<DayOfWeek> activeDays;   // mutable — modified in place
    private final ScheduleEditorGUI parent;    // reopen on Done

    private Inventory inventory;

    public DaySelectGUI(SwagRestartScheduler plugin, Set<DayOfWeek> activeDays,
                        ScheduleEditorGUI parent) {
        this.plugin     = plugin;
        this.activeDays = activeDays;
        this.parent     = parent;
    }

    // -------------------------------------------------------------------------
    // BaseGUI
    // -------------------------------------------------------------------------

    @Override
    public void open(Player player) {
        inventory = Bukkit.createInventory(null, 27,
                MM.deserialize(TITLE));
        populate();
        player.openInventory(inventory);
        plugin.getGUIManager().register(player, this);
    }

    @Override
    public void handleClick(int slot, ClickType type, Player player) {
        if (slot == 22) {
            // Done — go back to editor
            plugin.getGUIManager().unregister(player);
            parent.open(player);
            return;
        }

        DayOfWeek day = SLOT_TO_DAY[slot < 27 ? slot : 0];
        if (day == null) return;

        // Toggle the day
        if (activeDays.contains(day)) {
            activeDays.remove(day);
        } else {
            activeDays.add(day);
        }
        refresh(slot, day);
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
        // Fill all slots with filler first
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, GuiItems.filler());
        }

        // Day toggles
        DayOfWeek[] ordered = {
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
        };
        for (DayOfWeek day : ordered) {
            int slot = DAY_TO_SLOT[day.getValue()];
            inventory.setItem(slot, buildDayItem(day));
        }

        // Done button
        inventory.setItem(22, GuiItems.make(Material.PAPER, "<green>Done"));
    }

    private void refresh(int slot, DayOfWeek day) {
        if (inventory != null) {
            inventory.setItem(slot, buildDayItem(day));
        }
    }

    private ItemStack buildDayItem(DayOfWeek day) {
        boolean active = activeDays.contains(day);
        Material mat = active ? Material.LIME_DYE : Material.GRAY_DYE;
        String status = active ? "<green>Active" : "<gray>Inactive";
        String name = "<white>" + capitalize(day.name());
        return GuiItems.make(mat, name, java.util.List.of(status, "<dark_gray>Left-click to toggle"));
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.charAt(0) + s.substring(1).toLowerCase();
    }
}
