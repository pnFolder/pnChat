package ru.privatenull.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.pnfolder.pnlibrary.api.commands.BadUsage;
import org.pnfolder.pnlibrary.api.commands.CommandContext;
import org.pnfolder.pnlibrary.api.commands.CommandDefinition;
import org.pnfolder.pnlibrary.api.commands.NoPermission;
import org.pnfolder.pnlibrary.api.commands.PlayerOnly;
import ru.privatenull.pnChatPlugin;

import java.util.UUID;
import java.util.function.Consumer;

/** Команда /pnchat (reload, ac) на библиотечных командах pnLibrary. */
public final class PnChatCommand {

    private static final String USAGE_DEFAULT = "&cИспользование: /pnchat reload | /pnchat ac";

    private PnChatCommand() {
    }

    public static CommandDefinition create(pnChatPlugin plugin, AdminChatService adminChatService) {
        Consumer<CommandContext> usage = context -> send(context, plugin.getMessage("messages.commands.usage", USAGE_DEFAULT));
        Consumer<CommandContext> toggleAdminChat = context -> {
            Player player = player(context);
            if (player != null) adminChatService.toggleAdminChat(player);
        };
        return CommandDefinition.builder("pnchat")
                .permission("pnchat.admin")
                .playerOnly()
                .onError(NoPermission.class, (context, error) -> send(context, "&cНедостаточно прав."))
                .onError(PlayerOnly.class, (context, error) -> send(context,
                        plugin.getMessage("messages.general.only-player", "&cТолько игроки могут использовать чат.")))
                .onError(BadUsage.class, (context, error) -> usage.accept(context))
                .executes(usage)
                .literal("reload", node -> node.executes(context -> {
                    plugin.reloadPlugin();
                    send(context, plugin.getMessage("messages.general.reload-success", "&aКонфигурация pnChat перезагружена."));
                }))
                .literal("ac", node -> node.executes(toggleAdminChat))
                .literal("adminchat", node -> node.executes(toggleAdminChat))
                .build();
    }

    private static Player player(CommandContext context) {
        try {
            return Bukkit.getPlayer(UUID.fromString(context.getSender().getId()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void send(CommandContext context, String legacy) {
        Component text = LegacyComponentSerializer.legacySection().deserialize(ChatUtils.color(legacy));
        context.getSender().send(text);
    }
}
