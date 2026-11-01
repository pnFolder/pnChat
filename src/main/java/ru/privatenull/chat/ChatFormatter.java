package ru.privatenull.chat;

import ru.privatenull.integration.Cooldowns;
import ru.privatenull.integration.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import ru.privatenull.pnChatPlugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatFormatter {

    private static final Pattern MENTION_PATTERN = Pattern.compile("(?<![A-Za-z0-9_&@#./:-])@?([A-Za-z0-9_]{1,16})(?![A-Za-z0-9_])");

    private final pnChatPlugin plugin;
    private final ChatStyleService styleService;

    private String localFormat;
    private String globalFormat;
    private String adminFormat;
    private String mentionFormat;
    private String mentionSound;
    private float mentionSoundVolume;
    private float mentionSoundPitch;
    private long mentionSoundCooldownMs;
    private boolean mentionSoundEnabled;
    private boolean plainNameMentionsEnabled;
    private int plainNameMentionMinLength;
    private Set<String> plainNameMentionIgnoredWords;
    private boolean allowHexColors;
    private boolean colorPermRequired;
    private String colorPermission;

    public ChatFormatter(pnChatPlugin plugin, ChatStyleService styleService) {
        this.plugin = plugin;
        this.styleService = styleService;
        reloadConfig();
    }

    public void reloadConfig() {
        var cfg = plugin.getConfig();
        localFormat = cfg.getString("local-format", "&8[&aL&8] %luckperms_prefix%&7{player} &8» &f{message}");
        globalFormat = cfg.getString("global-format", "&8[&dG&8] %luckperms_prefix%&f{player} &8» &f{message}");
        adminFormat = cfg.getString("admin-format", "&8[&cADMIN&8] %luckperms_prefix%&f{player}&7: &f{message}");
        mentionFormat = cfg.getString("mention-format", "&c@{player}");
        mentionSound = cfg.getString("mention-sound", "entity.experience_orb.pickup");
        mentionSoundVolume = (float) cfg.getDouble("mention-sound-volume", 1.0);
        mentionSoundPitch = (float) cfg.getDouble("mention-sound-pitch", 1.0);
        mentionSoundCooldownMs = cfg.getLong("mention-sound-cooldown-ms", 4000L);
        mentionSoundEnabled = cfg.getBoolean("mention-sound-enabled", true);
        plainNameMentionsEnabled = cfg.getBoolean("plain-name-mentions-enabled", true);
        plainNameMentionMinLength = Math.max(1, Math.min(16, cfg.getInt("plain-name-mention-min-length", 3)));
        plainNameMentionIgnoredWords = new HashSet<>();
        for (String ignoredWord : cfg.getStringList("plain-name-mention-ignored-words")) {
            if (ignoredWord != null && !ignoredWord.isBlank()) {
                plainNameMentionIgnoredWords.add(ignoredWord.toLowerCase(Locale.ROOT));
            }
        }
        allowHexColors = cfg.getBoolean("allow-hex-colors", true);
        colorPermRequired = cfg.getBoolean("color-permission-required", false);
        colorPermission = cfg.getString("color-permission", "pnchat.color");
    }

    public String format(Player player, String channel, String message) {
        String format;
        if (channel.equalsIgnoreCase("global")) {
            format = globalFormat;
        } else if (channel.equalsIgnoreCase("admin")) {
            format = adminFormat;
        } else {
            format = localFormat;
        }

        String processedMessage = styleService.apply(player.getUniqueId(), sanitizeMessage(player, message));
        String chatName = styleService.getNickname(player.getUniqueId(), player.getName());
        String displayName = styleService.getNickname(player.getUniqueId(), player.getDisplayName());
        String customPrefix = styleService.getCustomPrefix(player.getUniqueId());
        String rawPrefix = customPrefix == null
                ? getLuckPermsPrefixSoft(player)
                : customPrefix;
        String effectivePrefix = withTrailingSpace(rawPrefix);
        String effectiveFormat = addImplicitPrefix(format, effectivePrefix);

        String formatWithNativePlaceholders = effectiveFormat
                .replace("{player}", chatName)
                .replace("{display_name}", displayName)
                .replace("{real_name}", player.getName())
                .replace("{world}", player.getWorld().getName())
                .replace("{luckperms_prefix}", effectivePrefix)
                .replace("{prefix}", effectivePrefix)
                .replace("%luckperms_prefix%", effectivePrefix);

        String formatWithExternalPlaceholders = applyPlaceholdersSoft(player, formatWithNativePlaceholders);
        String built = formatWithExternalPlaceholders.replace("{message}", processedMessage);
        String result = ChatUtils.color(built);
        return allowHexColors ? ChatUtils.translateHex(result) : result;
    }

    private String withTrailingSpace(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        return Character.isWhitespace(prefix.charAt(prefix.length() - 1)) ? prefix : prefix + " ";
    }

    private String addImplicitPrefix(String format, String prefix) {
        if (prefix.isEmpty() || format.contains("{prefix}")
                || format.contains("{luckperms_prefix}") || format.contains("%luckperms_prefix%")) {
            return format;
        }
        if (format.contains("{player}")) {
            return format.replace("{player}", "{prefix}{player}");
        }
        if (format.contains("{display_name}")) {
            return format.replace("{display_name}", "{prefix}{display_name}");
        }
        return "{prefix}" + format;
    }

    public String formatMention(Player sender, Player target) {
        return ChatUtils.color(mentionFormat.replace("{player}", target.getName()));
    }

    private String applyPlaceholdersSoft(Player player, String input) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return input;
        }
        try {
            Class<?> apiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Method method = apiClass.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
            Object result = method.invoke(null, player, input);
            return result == null ? input : String.valueOf(result);
        } catch (Throwable ex) {
            plugin.getLogger().fine("Не удалось применить PlaceholderAPI: " + ex.getMessage());
            return input;
        }
    }

    private String getLuckPermsPrefixSoft(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            return "";
        }
        try {
            Class<?> lpClass = Class.forName("net.luckperms.api.LuckPerms");
            Object lp = Bukkit.getServicesManager().load(lpClass);
            if (lp == null) {
                return "";
            }
            Object userManager = lpClass.getMethod("getUserManager").invoke(lp);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, player.getUniqueId());
            if (user == null) {
                return "";
            }
            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object metaData = null;
            for (Method method : cachedData.getClass().getMethods()) {
                if (method.getParameterCount() == 0 && method.getName().equals("getMetaData")) {
                    metaData = method.invoke(cachedData);
                    break;
                }
            }
            if (metaData == null) {
                return "";
            }
            Object prefix = metaData.getClass().getMethod("getPrefix").invoke(metaData);
            return prefix == null ? "" : String.valueOf(prefix);
        } catch (Throwable ex) {
            plugin.getLogger().fine("Не удалось получить префикс LuckPerms: " + ex.getMessage());
            return "";
        }
    }

    private String sanitizeMessage(Player player, String input) {
        if (input == null) {
            return "";
        }
        return applyFormatting(player, input.replace("\\", "\\\\"));
    }

    private String applyFormatting(Player player, String input) {
        if (input == null) {
            return "";
        }

        if (colorPermRequired && !player.hasPermission(colorPermission)
                && !player.hasPermission("pnchat.format")
                && !player.hasPermission("pnchat.chatcolor")) {
            return ChatUtils.stripColorCodes(input);
        }

        String result = formatMentions(player, input);
        result = ChatUtils.color(result);
        return allowHexColors ? ChatUtils.translateHex(result) : result;
    }

    private String formatMentions(Player sender, String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        Map<String, Player> onlinePlayers = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlinePlayers.put(player.getName().toLowerCase(Locale.ROOT), player);
        }

        if (onlinePlayers.isEmpty()) {
            return input;
        }

        Matcher matcher = MENTION_PATTERN.matcher(input);
        StringBuilder buffer = new StringBuilder();

        while (matcher.find()) {
            String nick = matcher.group(1);
            String normalizedNick = nick.toLowerCase(Locale.ROOT);
            boolean explicitMention = matcher.group().startsWith("@");
            if (!explicitMention && shouldSkipPlainNameMention(normalizedNick)) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
                continue;
            }

            Player target = onlinePlayers.get(normalizedNick);
            if (target != null && !target.getUniqueId().equals(sender.getUniqueId())) {
                String replacement = formatMention(sender, target);
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
                playMentionSound(target);
            } else {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(0)));
            }
        }

        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private boolean shouldSkipPlainNameMention(String normalizedNick) {
        return !plainNameMentionsEnabled
                || normalizedNick.length() < plainNameMentionMinLength
                || plainNameMentionIgnoredWords.contains(normalizedNick);
    }

    private void playMentionSound(Player target) {
        if (!mentionSoundEnabled) {
            return;
        }

        if (!Cooldowns.acquire(target.getUniqueId(), "mention-sound", mentionSoundCooldownMs)) {
            return;
        }

        Tasks.sync(() -> {
            if (!target.isOnline()) {
                return;
            }
            Location location = target.getLocation();
            target.playSound(location, mentionSound, SoundCategory.MASTER, mentionSoundVolume, mentionSoundPitch);
        });
    }
}
