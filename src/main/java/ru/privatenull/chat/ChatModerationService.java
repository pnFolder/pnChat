package ru.privatenull.chat;

import ru.privatenull.integration.Log;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.privatenull.pnChatPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatModerationService {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)(?:https?://|www\\.)\\S+|(?:discord\\.gg|discord\\.com/invite|t\\.me)/\\S+|\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:/\\S*)?");
    private static final Pattern OBFUSCATED_PROFANITY = Pattern.compile(
            "(?iu)(?:^|[^a-zа-я0-9])(?:х[уy]й|п[и1u]зд\\w*|[еёe3]б\\w*|бл[я9@]т\\w*|"
                    + "с[уy]к\\w*|долбо[еёe6]б\\w*|у[её]б\\w*|пид[о0]р\\w*|нигг?[еe3]р\\w*|"
                    + "f[\\W_]*u[\\W_]*c[\\W_]*k|n[\\W_]*i[\\W_]*g[\\W_]*g[\\W_]*e[\\W_]*r)(?:$|[^a-zа-я0-9])");

    private final pnChatPlugin plugin;
    private final Map<UUID, MessageTiming> messageTimings = new ConcurrentHashMap<>();

    private int maxMessageLength;
    private long spamCooldownMs;
    private int spamMaxMessages;
    private double capsThreshold;
    private int maxRepeatedChars;
    private boolean linkModerationEnabled;

    private List<String> blockedTerms;
    private List<String> allowedLinks;
    private boolean listsLoaded;

    public ChatModerationService(pnChatPlugin plugin) {
        this.plugin = plugin;
        this.blockedTerms = new ArrayList<>();
        this.allowedLinks = new ArrayList<>();
        reloadConfig();
        loadLists();
    }

    public void reloadConfig() {
        var cfg = plugin.getConfig();
        maxMessageLength = cfg.getInt("max-message-length", 256);
        spamCooldownMs = cfg.getLong("spam-cooldown-ms", 1500L);
        spamMaxMessages = cfg.getInt("spam-max-messages", 2);
        capsThreshold = cfg.getDouble("caps-threshold", 0.7);
        maxRepeatedChars = cfg.getInt("max-repeated-chars", 6);
        linkModerationEnabled = cfg.getBoolean("link-moderation-enabled", true);
    }

    public void loadLists() {
        blockedTerms = loadLines("blocked-words.txt");
        allowedLinks = loadLines("allowed-links.txt");
        listsLoaded = true;
    }

    private List<String> loadLines(String fileName) {
        List<String> lines = new ArrayList<>();
        File file = new File(plugin.getDataFolder(), fileName);

        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        }

        if (!file.exists()) {
            return lines;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
        } catch (Exception ex) {
            Log.error("Не удалось прочитать " + fileName + "", ex);
        }

        return lines;
    }

    public boolean isMutedByExternalPlugin(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        return isEssentialsMuted(player)
                || isPnBansMuted(player)
                || isLiteBansMuted(player)
                || isAdvancedBanMuted(player)
                || isCmiMuted(player);
    }

    /** Optional pnBans API integration; no compile-time dependency is required. */
    private boolean isPnBansMuted(Player player) {
        try {
            Class<?> apiClass = Class.forName("ru.privatenull.pnbans.api.PnBansMuteApi");
            Object api = Bukkit.getServicesManager().load(apiClass);
            if (api == null) return false;
            var method = apiClass.getMethod("isMuted", UUID.class, String.class);
            return Boolean.TRUE.equals(method.invoke(api, player.getUniqueId(), player.getName()));
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Throwable ex) {
            plugin.getLogger().fine("Не удалось проверить mute через pnBans API: " + ex.getMessage());
            return false;
        }
    }

    private boolean isEssentialsMuted(Player player) {
        try {
            var essentials = Bukkit.getPluginManager().getPlugin("Essentials");
            if (essentials == null) {
                return false;
            }
            var getService = essentials.getClass().getMethod("getUser", Player.class);
            if (getService == null) {
                return false;
            }
            Object user = getService.invoke(essentials, player);
            if (user == null) {
                return false;
            }
            var isMutedMethod = user.getClass().getMethod("isMuted");
            return Boolean.TRUE.equals(isMutedMethod.invoke(user));
        } catch (Throwable ex) {
            plugin.getLogger().fine("Не удалось проверить mute через Essentials: " + ex.getMessage());
            return false;
        }
    }

    private boolean isLiteBansMuted(Player player) {
        try {
            Class<?> apiClass = Class.forName("litebans.api.Database");
            var getMethod = apiClass.getMethod("get");
            Object api = getMethod.invoke(null);
            if (api == null) {
                return false;
            }
            var isMutedMethod = api.getClass().getMethod("isPlayerMuted", UUID.class);
            return Boolean.TRUE.equals(isMutedMethod.invoke(api, player.getUniqueId()));
        } catch (Throwable ex) {
            plugin.getLogger().fine("Не удалось проверить mute через LiteBans: " + ex.getMessage());
            return false;
        }
    }

    private boolean isAdvancedBanMuted(Player player) {
        try {
            Class<?> managerClass = Class.forName("me.leoko.advancedban.manager.PunishmentManager");
            var getMethod = managerClass.getMethod("get");
            Object manager = getMethod.invoke(null);
            if (manager == null) {
                return false;
            }
            var isMutedMethod = manager.getClass().getMethod("isMuted", UUID.class);
            return Boolean.TRUE.equals(isMutedMethod.invoke(manager, player.getUniqueId()));
        } catch (Throwable ex) {
            plugin.getLogger().fine("Не удалось проверить mute через AdvancedBan: " + ex.getMessage());
            return false;
        }
    }

    private boolean isCmiMuted(Player player) {
        try {
            Class<?> apiClass = Class.forName("com.Zrips.CMI.CMI");
            var getInstanceMethod = apiClass.getMethod("getInstance");
            Object api = getInstanceMethod.invoke(null);
            if (api == null) {
                return false;
            }
            var isMutedMethod = api.getClass().getMethod("isMuted", UUID.class);
            return Boolean.TRUE.equals(isMutedMethod.invoke(api, player.getUniqueId()));
        } catch (Throwable ex) {
            plugin.getLogger().fine("Не удалось проверить mute через CMI: " + ex.getMessage());
            return false;
        }
    }

    public String sanitizeAndCheck(Player player, String message) {
        if (message == null || message.isBlank()) {
            return null;
        }

        String sanitized = ChatUtils.stripColorCodes(message);
        String trimmed = sanitized.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.length() > maxMessageLength) {
            player.sendMessage(ChatUtils.color(plugin.getMessage("messages.moderation.too-long", "&cСообщение слишком длинное.")));
            return null;
        }

        if (isSpam(player, trimmed)) {
            player.sendMessage(ChatUtils.color(plugin.getMessage("messages.moderation.spam", "&cПожалуйста, не спамьте.")));
            return null;
        }

        if (isCapsLockAbuse(trimmed)) {
            player.sendMessage(ChatUtils.color(plugin.getMessage("messages.moderation.caps", "&cНе злоупотребляйте CAPS LOCK.")));
            return null;
        }

        if (containsProfanity(trimmed)) {
            player.sendMessage(ChatUtils.color(plugin.getMessage("messages.moderation.profanity", "&cМат запрещён.")));
            return null;
        }

        if (containsUrl(trimmed)) {
            player.sendMessage(ChatUtils.color(plugin.getMessage("messages.moderation.link-block", "&cСсылки в чате запрещены. Разрешены только указанные домены.")));
            return null;
        }

        if (hasExcessiveRepeating(trimmed)) {
            player.sendMessage(ChatUtils.color(plugin.getMessage("messages.moderation.repeated", "&cСлишком много повторяющихся символов.")));
            return null;
        }

        return trimmed;
    }

    private boolean isSpam(Player player, String message) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();

        MessageTiming timing = messageTimings.compute(id, (key, existing) -> {
            if (existing == null) {
                return new MessageTiming(now, 1);
            }
            if (now - existing.lastTime < spamCooldownMs) {
                return new MessageTiming(now, existing.count + 1);
            }
            return new MessageTiming(now, 1);
        });

        return timing.count > spamMaxMessages;
    }

    private boolean isCapsLockAbuse(String message) {
        int letters = 0;
        int upper = 0;

        for (char c : message.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }

        if (letters == 0) {
            return false;
        }

        return (double) upper / letters > capsThreshold;
    }

    private boolean containsProfanity(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        if (OBFUSCATED_PROFANITY.matcher(message).find()) {
            return true;
        }

        String normalizedMessage = normalizeText(message);
        if (normalizedMessage.isEmpty()) {
            return false;
        }

        for (String blocked : blockedTerms) {
            String normalizedBlocked = normalizeText(blocked);
            if (normalizedBlocked.isEmpty()) {
                continue;
            }
            if (normalizedMessage.contains(normalizedBlocked)) {
                return true;
            }
        }

        return false;
    }

    private boolean containsUrl(String message) {
        if (!linkModerationEnabled) {
            return false;
        }

        Matcher matcher = URL_PATTERN.matcher(message);
        while (matcher.find()) {
            if (!isAllowedUrl(matcher.group())) {
                return true;
            }
        }

        return false;
    }

    private boolean isAllowedUrl(String rawUrl) {
        if (allowedLinks.isEmpty()) {
            return false;
        }

        String normalizedUrl = normalizeUrl(rawUrl);
        String host = extractHost(rawUrl);
        for (String allowed : allowedLinks) {
            String normalizedAllowed = normalizeUrl(allowed);
            if (normalizedAllowed.isEmpty()) {
                continue;
            }

            if (!host.isEmpty() && (host.equals(normalizedAllowed) || host.endsWith("." + normalizedAllowed))) {
                return true;
            }

            if (normalizedUrl.equals(normalizedAllowed) || normalizedUrl.startsWith(normalizedAllowed + "/")) {
                return true;
            }
        }

        return false;
    }

    private boolean hasExcessiveRepeating(String message) {
        int count = 0;
        char last = 0;

        for (char c : message.toCharArray()) {
            if (c == last) {
                count++;
            } else {
                count = 1;
                last = c;
            }
            if (count >= maxRepeatedChars) {
                return true;
            }
        }

        return false;
    }

    private String normalizeUrl(String input) {
        if (input == null) {
            return "";
        }
        String normalized = trimTrailingUrlPunctuation(input.trim().toLowerCase(Locale.ROOT))
                .replaceFirst("^https?://", "")
                .replaceFirst("^www\\.", "");
        return normalized.replaceAll("[^a-z0-9./:-]+", "");
    }

    private String extractHost(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String normalized = trimTrailingUrlPunctuation(input.trim().toLowerCase(Locale.ROOT));
        try {
            URI uri = URI.create(normalized.matches("(?i)^https?://.*") ? normalized : "http://" + normalized);
            String host = uri.getHost();
            if (host == null) {
                return "";
            }
            return host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException ex) {
            int slash = normalized.indexOf('/');
            String host = slash >= 0 ? normalized.substring(0, slash) : normalized;
            return host.replaceFirst("^www\\.", "").replaceAll("[^a-z0-9.-]+", "");
        }
    }

    private String trimTrailingUrlPunctuation(String input) {
        return input.replaceAll("[.,!?:;)}\\]]+$", "");
    }

    private String normalizeText(String input) {
        if (input == null) {
            return "";
        }
        String lowered = input.toLowerCase(Locale.ROOT);
        lowered = lowered.replace("@", "a")
                .replace("$", "s")
                .replace("0", "o")
                .replace("1", "i")
                .replace("3", "e")
                .replace("4", "a")
                .replace("5", "s")
                .replace("7", "t")
                .replace("+", "t");
        return lowered.replaceAll("[^a-zа-я0-9]+", "");
    }

    private static final class MessageTiming {
        final long lastTime;
        final int count;

        MessageTiming(long lastTime, int count) {
            this.lastTime = lastTime;
            this.count = count;
        }
    }
}
