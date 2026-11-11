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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/**
 * /shinventorypages reload | saveall | info &lt;joueur&gt; | recover &lt;fichier&gt; [force] | history &lt;joueur&gt;.
 * Chaque sous-commande d'administration a sa permission shinventorypages.admin.&lt;nom&gt; ; l'historique a la sienne,
 * shinventorypages.history.
 */
public class CommandSHInventoryPages implements CommandExecutor, TabCompleter {

    /** Sous-commandes et leur permission, dans l'ordre d'affichage de la ligne d'usage. */
    private static final Map<String, String> PERMISSIONS = Map.of(
        "reload", "shinventorypages.admin.reload",
        "saveall", "shinventorypages.admin.saveall",
        "info", "shinventorypages.admin.info",
        "recover", "shinventorypages.admin.recover",
        "history", "shinventorypages.history");
    private static final List<String> SUBCOMMANDS = List.of("reload", "saveall", "info", "recover", "history");

    private final SHInventoryPages plugin;
    private final ConfigManager config;

    public CommandSHInventoryPages(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    /** Sous-commandes que l'expéditeur a le droit d'utiliser. */
    private static List<String> allowed(CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (String sub : SUBCOMMANDS) {
            if (sender.hasPermission(PERMISSIONS.get(sub))) {
                names.add(sub);
            }
        }
        return names;
    }

    private void usage(CommandSender sender, List<String> allowed) {
        MessageUtil.sendMessage(sender, config.getMessage("usage", "%subcommands%", String.join("|", allowed)));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            MessageUtil.sendMessage(sender, config.getMessage("no_permission_command"));
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!allowed.contains(sub)) {
            usage(sender, allowed);
            return true;
        }
        switch (sub) {
            case "reload" -> {
                try {
                    plugin.reloadPluginConfig();
                    MessageUtil.sendMessage(sender, config.getMessage("reload_success"));
                } catch (ConfigManager.ConfigException e) {
                    plugin.getLogger().log(Level.SEVERE, "Rechargement annulé, " + e.getFile() + " illisible : " + e.getMessage());
                    MessageUtil.sendMessage(sender, config.getMessage("reload_invalid", "%file%", e.getFile(), "%error%", String.valueOf(e.getMessage())));
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
            case "info" -> info(sender, allowed, args);
            case "recover" -> {
                if (args.length < 2) {
                    usage(sender, allowed);
                } else {
                    plugin.getInventoryManager().recoverUnsaved(sender, args[1], args.length > 2 && args[2].equalsIgnoreCase("force"));
                }
            }
            default -> history(sender, allowed, args);
        }
        return true;
    }

    /** /shinventorypages history <joueur> : copies de toutes les pages du joueur, connecté ou non. */
    private void history(CommandSender sender, List<String> allowed, String[] args) {
        if (!(sender instanceof Player staff)) {
            MessageUtil.sendMessage(sender, config.getMessage("only_players"));
            return;
        }
        if (args.length < 2) {
            usage(sender, allowed);
            return;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        // Jamais de recherche chez Mojang depuis le thread principal : seuls les joueurs déjà venus sont connus
        org.bukkit.OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            MessageUtil.sendMessage(sender, config.getMessage("history_unknown_player", "%player%", args[1]));
            return;
        }
        String name = target.getName() != null ? target.getName() : args[1];
        plugin.getHistoryMenus().openList(staff, target.getUniqueId(), name, 0);
    }

    private void info(CommandSender sender, List<String> allowed, String[] args) {
        if (args.length < 2) {
            usage(sender, allowed);
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
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return allowed.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!allowed.contains(sub)) return List.of();
        if (args.length == 2 && (sub.equals("history") || sub.equals("info"))) {
            return null; // noms des joueurs connectés
        }
        if (args.length == 2 && sub.equals("recover")) {
            String[] files = new java.io.File(plugin.getDataFolder(), "unsaved").list((dir, name) -> name.endsWith(".yml"));
            return files == null ? List.of() : java.util.Arrays.stream(files).filter(f -> f.startsWith(args[1])).toList();
        }
        return List.of();
    }
}
