package ru.privatenull;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import ru.privatenull.chat.AdminChatService;
import ru.privatenull.chat.ChatCommandExecutor;
import ru.privatenull.chat.ChatFormatter;
import ru.privatenull.chat.ChatListener;
import ru.privatenull.chat.ChatMessageService;
import ru.privatenull.chat.ChatModerationService;
import ru.privatenull.chat.ChatStyleCommandExecutor;
import ru.privatenull.chat.ChatStyleService;
import ru.privatenull.chat.ChatTabCompleter;
import ru.privatenull.pnlibrary.lifecycle.PluginBanner;
import ru.privatenull.pnlibrary.update.UpdateChecker;
import ru.privatenull.pnlibrary.update.UpdateSettings;

import java.io.File;

public final class pnChatPlugin extends JavaPlugin {

    public static final String SUPPORT_DISCORD = "https://discord.gg/rRbzq6cnc6";
    private static final String GITHUB_REPOSITORY = "pnFolder/pnChat";
    private static final int BSTATS_PLUGIN_ID = 32607;

    private ChatMessageService messageService;
    private ChatFormatter formatter;
    private ChatStyleService styleService;
    private ChatModerationService moderationService;
    private AdminChatService adminChatService;
    private UpdateChecker updateChecker;
    private FileConfiguration messagesConfig;

    @Override
    public void onEnable() {
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

        PluginCommand mainCommand = requireCommand("pnchat");
        mainCommand.setExecutor(new ChatCommandExecutor(this, messageService, adminChatService));
        mainCommand.setTabCompleter(new ChatTabCompleter());
        ChatStyleCommandExecutor styleCommands = new ChatStyleCommandExecutor(this, styleService);
        registerStyleCommand("chatcolor", styleCommands);
        registerStyleCommand("chatfont", styleCommands);
        registerStyleCommand("nick", styleCommands);
        registerStyleCommand("prefix", styleCommands);

        getServer().getPluginManager().registerEvents(
                new ChatListener(messageService, moderationService, adminChatService, this), this);

        setupUpdateChecker();
        new Metrics(this, BSTATS_PLUGIN_ID);
        PluginBanner.enabled(this, SUPPORT_DISCORD);
        logStartupDetails();
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) {
            updateChecker.cancel();
        }
        PluginBanner.disabled(this, SUPPORT_DISCORD);
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
        if (updateChecker != null) {
            updateChecker.restart(updateSettings());
        }
        getLogger().info("pnChat: конфигурация перезагружена.");
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

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    private void setupUpdateChecker() {
        updateChecker = new UpdateChecker(this, updateSettings());
        updateChecker.start();
    }

    private UpdateSettings updateSettings() {
        return new UpdateSettings(true, GITHUB_REPOSITORY, "pnchat.admin", 6L, SUPPORT_DISCORD);
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
        getLogger().info("Локальный чат: радиус " + getConfig().getInt("radius", 100) + " блоков.");
        getLogger().info("Глобальный чат: префикс ! перед сообщением.");
        getLogger().info("Упоминания: @ник и автоупоминания по нику онлайн-игрока.");
        getLogger().info("Анонимная статистика bStats: ID " + BSTATS_PLUGIN_ID + ".");
    }
}
