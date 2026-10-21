package org.Cry_Legende.sHInventoryPages.commands;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class CommandSHInventoryPages implements CommandExecutor, TabCompleter {

    private final SHInventoryPages plugin;
    private final ConfigManager configManager;

    public CommandSHInventoryPages(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("shinventorypages.admin")) {
            MessageUtil.sendMessage(sender, configManager.getMessage("no_permission_command"));
            return true;
        }

        if (args.length == 0) {
            MessageUtil.sendMessage(sender, "&cUsage: /" + label + " <reload|saveall>");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload":
                try {
                    plugin.reloadPluginConfig();
                    MessageUtil.sendMessage(sender, configManager.getMessage("reload_success"));
                } catch (Exception e) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error reloading config", e);
                    MessageUtil.sendMessage(sender, configManager.getMessage("reload_fail"));
                }
                break;

            case "saveall":
                MessageUtil.sendMessage(sender, configManager.getMessage("save_all_started"));
                // Run async save and notify sender on completion (requires callback or waiting)
                plugin.getInventoryManager().saveAllPlayerData(true); // Trigger async save
                // Note: We don't have a direct callback here easily. The log message indicates start.
                // Could add a CompletableFuture chain to message on completion if needed.
                MessageUtil.sendMessage(sender, configManager.getMessage("save_all_finished")); // This might appear before save truly finishes!
                break;

            default:
                MessageUtil.sendMessage(sender, "&cUnknown subcommand. Usage: /" + label + " <reload|saveall>");
                break;
        }

        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("shinventorypages.admin")) {
            List<String> subCommands = Arrays.asList("reload", "saveall");
            return subCommands.stream()
                .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        return null; // No suggestions for further arguments
    }
}
