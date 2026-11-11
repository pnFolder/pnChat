package ru.privatenull;

import ru.privatenull.integration.Log;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.pnfolder.pnlibrary.bukkit.bootstrap.PnLibraryBootstrap;
import ru.privatenull.chat.AdminChatService;
import ru.privatenull.chat.ChatFormatter;
import ru.privatenull.chat.ChatListener;
import ru.privatenull.chat.ChatMessageService;
import ru.privatenull.chat.ChatModerationService;
import ru.privatenull.chat.ChatStyleCommandExecutor;
import ru.privatenull.chat.ChatStyleService;
import ru.privatenull.chat.PnChatCommand;
import ru.privatenull.bootstrap.PnLibraryLifecycleListener;
import ru.privatenull.integration.PnLibraryRuntime;

import java.io.File;
import java.util.Map;
import java.util.logging.Level;

public final class pnChatPlugin extends JavaPlugin {

    public static final String SUPPORT_DISCORD = "https://discord.gg/rRbzq6cnc6";

    private ChatMessageService messageService;
    private ChatFormatter formatter;
    private ChatStyleService styleService;
    private ChatModerationService moderationService;
    private AdminChatService adminChatService;
    private boolean pnLibraryReady;
    private PnLibraryRuntime pnRuntime;
    private FileConfiguration messagesConfig;

    @Override
    public void onLoad() {
        try {
            pnLibraryReady = PnLibraryBootstrap.ensureInstalled(this, PnLibraryRuntime.MINIMUM_VERSION);
        } catch (Throwable failure) {
            Log.error("Не удалось автоматически подготовить pnLibrary. pnChat не будет запущен.", failure);
        }
    }

    @Override
    public void onEnable() {
        if (!pnLibraryReady) {
            Log.error("pnLibrary не подготовлена: проверьте доступ к GitHub и ошибку загрузки выше.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            pnRuntime = PnLibraryRuntime.connect(this);
        } catch (Throwable failure) {
            Log.error("pnLibrary не удалось включить или подключить к pnChat.", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new PnLibraryLifecycleListener(this), this);
        pnRuntime.register(this::diagnostics);

        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("allowed-links.txt", false);

        File messagesFile = new File(getDataFolder(), "messages.yml");
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);

        styleService = new ChatStyleService(this);
        formatter = new ChatFormatter(this, styleService);
        moderationService = new ChatModerationService(this);
        messageService = new ChatMessageService(this, formatter);
        adminChatService = new AdminChatService(this);

        pnRuntime.registerCommand(PnChatCommand.create(this, adminChatService));
        ChatStyleCommandExecutor styleCommands = new ChatStyleCommandExecutor(this, styleService);
        registerStyleCommand("chatcolor", styleCommands);
        registerStyleCommand("chatfont", styleCommands);
        registerStyleCommand("nick", styleCommands);
        registerStyleCommand("prefix", styleCommands);

        getServer().getPluginManager().registerEvents(
                new ChatListener(messageService, moderationService, adminChatService, this), this);

        pnRuntime.started(getConfig().getDouble("radius", 100), 5);
        logStartupDetails();
    }

    @Override
    public void onDisable() {
        PnLibraryRuntime closing = pnRuntime;
        pnRuntime = null;
        if (closing != null) {
            try {
                closing.close();
            } catch (Exception exception) {
                Log.error("Не удалось корректно закрыть контекст pnLibrary", exception);
            }
        }
    }

    public void reloadPlugin() {
        reloadConfig();
        File messagesFile = new File(getDataFolder(), "messages.yml");
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        styleService.reload();
        formatter.reloadConfig();
        moderationService.reloadConfig();
        moderationService.loadLists();
        adminChatService.reloadConfig();
        Log.info("pnChat: конфигурация перезагружена.");
    }

    public String getMessage(String path, String defaultValue) {
        return messagesConfig != null ? messagesConfig.getString(path, defaultValue) : defaultValue;
    }

    public ChatFormatter getFormatter() {
        return formatter;
    }

    public ChatModerationService getModerationService() {
        return moderationService;
    }

    public ChatMessageService getMessageService() {
        return messageService;
    }

    public AdminChatService getAdminChatService() {
        return adminChatService;
    }

    private Map<String, Object> diagnostics() {
        return Map.of(
                "version", getDescription().getVersion(),
                "radius", getConfig().getDouble("radius", 100));
    }

    private PluginCommand requireCommand(String name) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("Команда /" + name + " отсутствует в plugin.yml");
        }
        return command;
    }

    private void registerStyleCommand(String name, ChatStyleCommandExecutor executor) {
        PluginCommand command = requireCommand(name);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void logStartupDetails() {
        Log.info("Локальный чат: радиус " + getConfig().getInt("radius", 100) + " блоков.");
        Log.info("Глобальный чат: префикс ! перед сообщением.");
        Log.info("Упоминания: @ник и автоупоминания по нику онлайн-игрока.");
    }
}
