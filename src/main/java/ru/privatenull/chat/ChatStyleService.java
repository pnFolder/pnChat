package ru.privatenull.chat;

import ru.privatenull.integration.Log;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public class ChatStyleService {

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration config;

    public ChatStyleService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "chat-styles.yml");
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public String apply(UUID playerId, String message) {
        String path = "players." + playerId;
        String color = config.getString(path + ".color", "");
        List<String> fonts = config.getStringList(path + ".fonts");
        if (color.isEmpty() && fonts.isEmpty()) {
            return message;
        }
        return color + String.join("", fonts) + message;
    }

    public String getNickname(UUID playerId, String fallback) {
        String nickname = config.getString("players." + playerId + ".nickname");
        return nickname == null || nickname.isBlank() ? fallback : nickname;
    }

    public String getCustomPrefix(UUID playerId) {
        String prefix = config.getString("players." + playerId + ".prefix");
        return prefix == null || prefix.isBlank() ? null : prefix;
    }

    public void setColor(UUID playerId, String color) {
        String path = "players." + playerId + ".color";
        config.set(path, color == null || color.isBlank() ? null : color);
        save();
    }

    public void setFonts(UUID playerId, List<String> fonts) {
        String path = "players." + playerId + ".fonts";
        config.set(path, fonts == null || fonts.isEmpty() ? null : fonts);
        save();
    }

    public void setNickname(UUID playerId, String nickname) {
        setTextValue(playerId, "nickname", nickname);
    }

    public void setCustomPrefix(UUID playerId, String prefix) {
        setTextValue(playerId, "prefix", prefix);
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(file);
    }

    private void setTextValue(UUID playerId, String key, String value) {
        String path = "players." + playerId + "." + key;
        config.set(path, value == null || value.isBlank() ? null : value);
        save();
    }

    private void save() {
        try {
            config.save(file);
        } catch (IOException exception) {
            Log.error("Не удалось сохранить стили чата", exception);
        }
    }
}
