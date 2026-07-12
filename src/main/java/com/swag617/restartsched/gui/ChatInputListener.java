package com.swag617.restartsched.gui;

import com.swag617.restartsched.SwagRestartScheduler;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Captures the next chat message from players who have been registered
 * as awaiting chat input (e.g. typing a time into the {@link TimeEditGUI}).
 *
 * <p>The chat event is cancelled so the typed text is never broadcast to other
 * players. The registered {@link Consumer} is always invoked on the main server
 * thread regardless of whether {@link AsyncPlayerChatEvent} fires async.</p>
 *
 * <p>Registrations are automatically cleaned up when the player disconnects.</p>
 */
public class ChatInputListener implements Listener {

    private final SwagRestartScheduler plugin;

    /**
     * Maps player UUID to the consumer that should receive their next chat message.
     * Uses a {@link ConcurrentHashMap} because {@link AsyncPlayerChatEvent} may
     * fire off the main thread.
     */
    private final ConcurrentHashMap<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public ChatInputListener(SwagRestartScheduler plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Registration API
    // -------------------------------------------------------------------------

    /**
     * Registers a player to receive their next chat message via {@code callback}.
     *
     * <p>Only one callback can be active per player at a time; registering again
     * replaces the previous one.</p>
     *
     * @param uuid     player UUID
     * @param callback consumer invoked (on main thread) with the raw chat input
     */
    public void registerInput(UUID uuid, Consumer<String> callback) {
        pending.put(uuid, callback);
    }

    /**
     * Removes any pending chat-input registration for the given player.
     * Safe to call even if no registration exists.
     */
    public void unregisterInput(UUID uuid) {
        pending.remove(uuid);
    }

    /** Returns {@code true} if the player currently has a pending chat-input registration. */
    public boolean isPending(UUID uuid) {
        return pending.containsKey(uuid);
    }

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    @SuppressWarnings("deprecation") // AsyncPlayerChatEvent is deprecated in Paper 1.21 but still functional in 1.20.4
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Consumer<String> callback = pending.remove(uuid);
        if (callback == null) return;

        // Cancel the chat event so the message is not broadcast
        event.setCancelled(true);

        final String input = event.getMessage();

        // Always dispatch the callback on the main thread — GUI manipulation requires it
        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(input));
    }

    /** Clean up pending registrations when a player disconnects. */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
