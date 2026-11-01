package ru.privatenull.integration;

import org.bukkit.plugin.java.JavaPlugin;
import org.pnfolder.pnlibrary.api.commands.CommandDefinition;
import org.pnfolder.pnlibrary.api.diagnostics.DiagnosticConfiguration;
import org.pnfolder.pnlibrary.api.diagnostics.DiagnosticContainer;
import org.pnfolder.pnlibrary.api.logging.MessageBox;
import org.pnfolder.pnlibrary.api.plugin.ModuleContext;
import org.pnfolder.pnlibrary.api.plugin.PluginRegistration;
import org.pnfolder.pnlibrary.api.remote.PolicyErrorAction;
import org.pnfolder.pnlibrary.api.remote.RemotePolicySettings;
import org.pnfolder.pnlibrary.api.runtime.PnLibrary;
import org.pnfolder.pnlibrary.api.runtime.PnLibraryIdentity;
import org.pnfolder.pnlibrary.api.runtime.PnLibraryProvider;
import org.pnfolder.pnlibrary.api.updates.UpdateChannel;

import java.util.function.Supplier;
import java.util.logging.Level;

/** Managed pnLibrary integration loaded only after the dependency is enabled. */
public final class PnLibraryRuntime {

    public static final String MINIMUM_VERSION = "0.0.1-dev.6";
    private static final String MODULE_ID = "pnchat";
    private static final int BSTATS_PROJECT_ID = 32607;
    private static final String FASTSTATS_TOKEN = "7e4411d995e3aadd6f8f615ead5ba3d8";
    private static final String POLICY_SOURCE =
            "https://raw.githubusercontent.com/pnFolder/pnlibrary-policy/main/.pnlibrary/policies/PnChatPolicy.java";
    private static final String UPDATE_OWNER = "pnFolder";
    private static final String UPDATE_REPOSITORY = "pnChat";

    private final JavaPlugin owner;
    private final PnLibrary runtime;
    private PluginRegistration registration;
    private ModuleContext module;

    private PnLibraryRuntime(JavaPlugin owner, PnLibrary runtime) {
        this.owner = owner;
        this.runtime = runtime;
    }

    public static PnLibraryRuntime connect(JavaPlugin owner) {
        PnLibrary runtime = PnLibraryProvider.get();
        if (!runtime.isAtLeastVersion(MINIMUM_VERSION)) {
            throw new IllegalStateException("pnChat требует pnLibrary " + MINIMUM_VERSION
                    + " или новее, установлена " + runtime.getVersion());
        }
        return new PnLibraryRuntime(owner, runtime);
    }

    public void register(Supplier<java.util.Map<String, Object>> diagnostics) {
        if (registration != null) throw new IllegalStateException("pnChat уже зарегистрирован в pnLibrary");
        registration = runtime.getPlugins().register(owner);
        try {
            module = registration.registerModule(MODULE_ID, builder -> builder
                    // A policy that cannot be checked (offline server, JRE without a compiler) never stops pnChat;
                    // only an explicit denial from the policy does.
                    .remotePolicy(RemotePolicySettings.builder()
                            .source(POLICY_SOURCE)
                            .onError(PolicyErrorAction.KEEP_RUNNING)
                            .support(PnLibraryIdentity.SUPPORT_URL)
                            .build())
                    .metrics(metrics -> metrics.bStats(BSTATS_PROJECT_ID).fastStats(FASTSTATS_TOKEN))
                    .diagnostics(owner.getDataFolder().toPath(), DiagnosticContainer.builder("pnchat")
                            .snapshot(diagnostics::get)
                            .configuration(DiagnosticConfiguration.builder("config.yml").build())
                            .build())
                    .updates(UPDATE_OWNER, UPDATE_REPOSITORY, updater -> updater
                            .channel(UpdateChannel.STABLE)
                            .automaticDownload(false)
                            .artifact("(?i)^pnChat-[0-9].*\\.jar$", 21)));
            Log.bind(owner, module);
            Tasks.bind(module);
            Cooldowns.bind(module);
        } catch (RuntimeException failure) {
            closeRegistration();
            throw failure;
        }
    }

    /** Регистрирует команду в pnLibrary; она снимается вместе с модулем. */
    public void registerCommand(CommandDefinition command) {
        if (module == null) throw new IllegalStateException("pnChat не зарегистрирован в pnLibrary");
        module.registerCommand(command);
    }

    public void started(double radius, int commands) {
        if (module == null) return;
        MessageBox report = module.getLifecycle().enabled()
                .ok("Локальный чат", "радиус " + radius + " блоков, глобальный через !")
                .ok("Команды", commands + " зарегистрировано");
        optional(report, "PlaceholderAPI", "плейсхолдеры в формате чата");
        optional(report, "LuckPerms", "префиксы и права");
        optional(report, "pnBans", "проверка мутов");
        report.ok("Диагностика", "/pn debug pnchat").show();
    }

    private void optional(MessageBox report, String plugin, String purpose) {
        if (owner.getServer().getPluginManager().isPluginEnabled(plugin)) report.ok(plugin, purpose);
        else report.skip(plugin, "не установлен");
    }

    public void close() {
        ModuleContext closing = module;
        module = null;
        if (closing != null && !closing.isClosed()) {
            try {
                stoppedOn(closing);
            } catch (RuntimeException failure) {
                owner.getLogger().log(Level.FINE, "Не удалось вывести сообщение об остановке pnChat", failure);
            }
        }
        closeRegistration();
    }

    private void stoppedOn(ModuleContext closing) {
        closing.getLifecycle().disabled()
                .ok("Чат", "обработка сообщений остановлена")
                .ok("Диагностика", "/pn debug pnchat")
                .show();
    }

    private void closeRegistration() {
        Log.unbind();
        Tasks.unbind();
        Cooldowns.unbind();
        PluginRegistration closing = registration;
        registration = null;
        if (closing != null && !closing.isClosed()) closing.close();
    }
}
