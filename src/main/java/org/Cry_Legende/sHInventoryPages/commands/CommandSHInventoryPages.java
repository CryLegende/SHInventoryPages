package org.Cry_Legende.sHInventoryPages.commands;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/** /shinventorypages reload | saveall | info &lt;joueur&gt; */
public class CommandSHInventoryPages implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reload", "saveall", "info", "recover");

    private final SHInventoryPages plugin;
    private final ConfigManager config;

    public CommandSHInventoryPages(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("shinventorypages.admin")) {
            MessageUtil.sendMessage(sender, config.getMessage("no_permission_command"));
            return true;
        }
        if (args.length == 0) {
            MessageUtil.sendMessage(sender, config.getMessage("command_usage", "%label%", label));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                try {
                    plugin.reloadPluginConfig();
                    MessageUtil.sendMessage(sender, config.getMessage("reload_success"));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error reloading config", e);
                    MessageUtil.sendMessage(sender, config.getMessage("reload_fail"));
                }
            }
            case "saveall" -> {
                MessageUtil.sendMessage(sender, config.getMessage("save_all_started"));
                UUID senderId = sender instanceof Player player ? player.getUniqueId() : null;
                plugin.getInventoryManager().saveAllNow().whenComplete((ignored, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        CommandSender target = senderId == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(senderId);
                        if (target != null) {
                            MessageUtil.sendMessage(target, config.getMessage(error == null ? "save_all_finished" : "save_all_failed"));
                        }
                    }));
            }
            case "info" -> info(sender, label, args);
            case "recover" -> {
                if (args.length < 2) {
                    MessageUtil.sendMessage(sender, config.getMessage("command_usage", "%label%", label));
                } else {
                    plugin.getInventoryManager().recoverUnsaved(sender, args[1], args.length > 2 && args[2].equalsIgnoreCase("force"));
                }
            }
            default -> MessageUtil.sendMessage(sender, config.getMessage("command_usage", "%label%", label));
        }
        return true;
    }

    private void info(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            MessageUtil.sendMessage(sender, config.getMessage("command_usage", "%label%", label));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        PlayerInventoryData data = target != null ? plugin.getInventoryManager().getPlayerData(target.getUniqueId()) : null;
        if (data == null) {
            MessageUtil.sendMessage(sender, config.getMessage("player_not_found", "%player%", args[1]));
            return;
        }
        plugin.getInventoryManager().captureLive(target, data);
        MessageUtil.sendMessage(sender, config.getMessage("info_header", "%player%", target.getName(),
            "%current_page%", String.valueOf(data.getCurrentPage()), "%max_pages%", String.valueOf(data.getMaxPages())));
        Map<Integer, ItemStack[]> pages = new TreeMap<>(data.pagesView());
        for (Map.Entry<Integer, ItemStack[]> page : pages.entrySet()) {
            int slots = 0;
            int items = 0;
            for (ItemStack item : page.getValue()) {
                if (item == null || item.isEmpty()) continue;
                slots++;
                items += item.getAmount();
            }
            String key = page.getKey() > data.getMaxPages() ? "info_hidden_page_line" : "info_page_line";
            MessageUtil.sendMessage(sender, config.getMessage(key, "%page%", String.valueOf(page.getKey()),
                "%slots%", String.valueOf(slots), "%items%", String.valueOf(items)));
        }
        for (int locked : new java.util.TreeSet<>(data.lockedPagesView())) {
            MessageUtil.sendMessage(sender, config.getMessage("info_locked_page_line", "%page%", String.valueOf(locked)));
        }
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("shinventorypages.admin")) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            return null; // noms des joueurs connectés
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("recover")) {
            String[] files = new java.io.File(plugin.getDataFolder(), "unsaved").list((dir, name) -> name.endsWith(".yml"));
            return files == null ? List.of() : java.util.Arrays.stream(files).filter(f -> f.startsWith(args[1])).toList();
        }
        return List.of();
    }
}
