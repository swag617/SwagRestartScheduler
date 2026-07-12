package com.swag617.restartsched.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility factory for common GUI item stacks.
 *
 * <p>All display names and lore use Adventure components via MiniMessage so
 * that colour codes are consistent with the rest of the plugin.</p>
 */
public final class GuiItems {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private GuiItems() { }

    // -------------------------------------------------------------------------
    // Generic builders
    // -------------------------------------------------------------------------

    /**
     * Creates an {@link ItemStack} with an Adventure display name and optional lore.
     *
     * @param material  material to use
     * @param nameMM    MiniMessage string for the display name
     * @param loreMM    MiniMessage strings for each lore line (may be empty)
     * @return configured item stack
     */
    public static ItemStack make(Material material, String nameMM, List<String> loreMM) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(MM.deserialize(nameMM));

        if (loreMM != null && !loreMM.isEmpty()) {
            List<Component> loreComponents = new ArrayList<>();
            for (String line : loreMM) {
                loreComponents.add(MM.deserialize(line));
            }
            meta.lore(loreComponents);
        }

        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack make(Material material, String nameMM) {
        return make(material, nameMM, List.of());
    }

    // -------------------------------------------------------------------------
    // Standard GUI chrome
    // -------------------------------------------------------------------------

    /** Transparent filler pane with a blank display name (no tooltip text). */
    public static ItemStack filler() {
        return make(Material.GRAY_STAINED_GLASS_PANE, "<gray> ");
    }

    /** Standard back arrow button. */
    public static ItemStack back() {
        return make(Material.ARROW, "<yellow>Back");
    }

    /** Standard close/barrier button. */
    public static ItemStack close() {
        return make(Material.BARRIER, "<red>Close");
    }

    /** Standard save-and-close paper button. */
    public static ItemStack save() {
        return make(Material.PAPER, "<green>Save & Close");
    }
}
