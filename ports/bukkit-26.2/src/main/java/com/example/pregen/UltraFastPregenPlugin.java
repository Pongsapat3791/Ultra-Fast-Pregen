package com.example.pregen;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.MinecraftServer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.command.VanillaCommandWrapper;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Bukkit entry point, standing in for the mod builds' mixins into the server: enabling and
 * disabling follow the server's start and stop, and /pregen runs Brigadier's own command tree
 * (PreGenCommand) in a dispatcher of this plugin, with the sender as Minecraft's command source.
 */
public final class UltraFastPregenPlugin extends JavaPlugin implements TabExecutor {
    /** /pregen and its boss bar; ops have it by default. */
    static final String COMMAND_PERMISSION = "ultrafastpregen.command";

    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    private MinecraftServer server;

    @Override
    public void onEnable() {
        server = ((CraftServer) getServer()).getServer();
        Platform.plugin = this;
        PreGenMod.init(getDataFolder().toPath(), getDescription().getVersion());
        Platform.checkInternals();
        getLogger().info("Running on " + Platform.name() + ".");
        PregenRuns.onServerCreated(server);
        PreGenCommand.register(dispatcher);
        PluginCommand command = getCommand("pregen");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        // On the first tick, with every world loaded: a run the last stop cut short may continue.
        Platform.runNextTick(this, () -> PregenRuns.onServerTick(server));
        // While a run is active the empty server does not pause; Bukkit runs this also while it is paused.
        Platform.runEveryTick(this, () -> {
            if (PregenRuns.isRunning()) ServerAccess.keepAwake(server);
        });
    }

    @Override
    public void onDisable() {
        // The server stops (or /reload unloads the plugin): the run stops after the batch being saved.
        if (server != null) PregenRuns.onServerStopping(server);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        CommandSourceStack source = VanillaCommandWrapper.getListener(sender);
        String input = args.length == 0 ? "pregen" : "pregen " + String.join(" ", args);
        try {
            dispatcher.execute(input, source);
        } catch (CommandSyntaxException failure) {
            source.sendFailure(ComponentUtils.fromMessage(failure.getRawMessage()));
            if (failure.getInput() != null && failure.getCursor() >= 0) {
                int cursor = Math.min(failure.getInput().length(), failure.getCursor());
                String context = (cursor > 10 ? "..." : "") + failure.getInput().substring(Math.max(0, cursor - 10), cursor);
                source.sendFailure(Component.literal("§7/" + context + "§c§n" + failure.getInput().substring(cursor) + "§r§c§o<--[HERE]"));
            }
        } catch (RuntimeException failure) {
            PreGenMod.LOGGER.error("[PreGen] /{} failed", input, failure);
            source.sendFailure(Component.literal("§c[PreGen] " + failure));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        CommandSourceStack source = VanillaCommandWrapper.getListener(sender);
        ParseResults<CommandSourceStack> parse = dispatcher.parse("pregen " + String.join(" ", args), source);
        return dispatcher.getCompletionSuggestions(parse).join().getList().stream().map(Suggestion::getText).toList();
    }
}
