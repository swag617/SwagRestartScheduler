package com.swag617.restartsched.web;

import com.SwagDev.SwagAPI.api.IWebService;
import com.swag617.restartsched.SwagRestartScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Registers the SwagRestartScheduler web config editor with SwagAPI's shared
 * {@link IWebService}.
 *
 * <p>SwagRestartScheduler does not own an HttpServer — SwagAPI's shared server does.
 * Login is handled entirely by SwagAPI's own session-cookie system: the mount point at
 * {@code /swagapi/swagrestartscheduler/} is already gated by SwagAPI's login before
 * {@link WebEditorHttpHandler} ever runs, so the editor has no password/auth of its own.
 * See {@link IWebService#getSessionUsername} for reading who's currently signed in.</p>
 *
 * <p>This is a soft dependency: if SwagAPI is not installed or its {@link IWebService}
 * has not been registered with Bukkit's ServicesManager, {@link #enable()} logs a warning
 * and returns without registering — the rest of the plugin continues to function normally.</p>
 *
 * <p>Config key: {@code web-editor.enabled} (default {@code true}) gates whether this
 * module registers at all.</p>
 */
public class WebEditorModule {

    private final SwagRestartScheduler plugin;
    private IWebService webService;
    private boolean registered = false;

    public WebEditorModule(SwagRestartScheduler plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers the web editor with SwagAPI's {@link IWebService}, if enabled in config
     * and if SwagAPI is present. Safe to call even if SwagAPI is absent — logs a warning
     * and returns rather than throwing.
     */
    public void enable() {
        if (!plugin.getConfig().getBoolean("web-editor.enabled", true)) {
            plugin.getLogger().info("Web config editor disabled in config, skipping.");
            return;
        }

        RegisteredServiceProvider<IWebService> rsp =
                Bukkit.getServicesManager().getRegistration(IWebService.class);
        if (rsp == null) {
            plugin.getLogger().warning(
                    "SwagAPI IWebService not present — web config editor unavailable. Is SwagAPI installed and enabled?");
            return;
        }

        webService = rsp.getProvider();
        webService.registerModule(plugin, new WebEditorHttpHandler(plugin));
        registered = true;
        plugin.getLogger().info("Web config editor registered at " + getUrl());
    }

    /**
     * Unregisters the web editor from SwagAPI's {@link IWebService}. Safe to call even if
     * {@link #enable()} never registered anything.
     */
    public void disable() {
        if (!registered || webService == null) {
            return;
        }
        webService.unregisterModule(plugin);
        registered = false;
    }

    /** Returns whether the web editor is currently registered with SwagAPI. */
    public boolean isRegistered() {
        return registered;
    }

    /**
     * Returns the full browser URL to the web editor
     * (e.g. {@code "http://192.168.1.10:8080/swagapi/swagrestartscheduler/"}),
     * or {@code null} if the editor is not currently registered.
     */
    public String getUrl() {
        if (webService == null || !registered) {
            return null;
        }
        return webService.getPluginUrl(plugin.getName().toLowerCase());
    }
}
