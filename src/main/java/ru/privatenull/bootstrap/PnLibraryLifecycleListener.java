package ru.privatenull.bootstrap;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import ru.privatenull.pnChatPlugin;

/** Keeps pnChat from outliving the shared runtime during shutdown or manual unload. */
public final class PnLibraryLifecycleListener implements Listener {

    private final pnChatPlugin plugin;

    public PnLibraryLifecycleListener(pnChatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!event.getPlugin().getName().equalsIgnoreCase("pnLibrary") || !plugin.isEnabled()) return;
        plugin.getLogger().warning("pnLibrary отключается; pnChat будет безопасно остановлен первым.");
        plugin.getServer().getPluginManager().disablePlugin(plugin);
    }
}
