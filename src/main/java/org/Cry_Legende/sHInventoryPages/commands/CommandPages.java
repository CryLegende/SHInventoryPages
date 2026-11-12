package org.Cry_Legende.sHInventoryPages.commands;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.inventory.InventoryManager;
import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.Cry_Legende.sHInventoryPages.menu.PagesMenu;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** /pages : menu des pages du joueur. /pages &lt;numéro&gt; : porte directement cette page. */
public class CommandPages implements CommandExecutor, TabCompleter {

    private final SHInventoryPages plugin;
    private final ConfigManager config;
    private final InventoryManager manager;

    public CommandPages(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.manager = plugin.getInventoryManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendMessage(sender, config.getMessage("only_players"));
            return true;
        }
        if (!config.isMenuEnabled() || !PagesMenu.allowed(player, PagesMenu.PERMISSION)) {
            MessageUtil.sendMessage(player, config.getMessage("no_permission_command"));
            return true;
        }
        if (args.length == 0) {
            plugin.getPagesMenu().open(player);
            return true;
        }
        PlayerInventoryData data = manager.getPlayerData(player.getUniqueId());
        if (data == null) {
            MessageUtil.sendMessage(player, config.getMessage("data_loading"));
            return true;
        }
        int page;
        try {
            page = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            MessageUtil.sendMessage(player, config.getMessage("pages_usage"));
            return true;
        }
        if (!data.isAccessible(page)) {
            manager.playFailSound(player);
            MessageUtil.sendMessage(player, config.getMessage("pages_unknown_page", "%page%", args[0], "%max_pages%", String.valueOf(data.getMaxPages())));
            return true;
        }
        manager.switchToPage(player, page);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> pages = new ArrayList<>();
        if (args.length != 1 || !(sender instanceof Player player)) return pages;
        PlayerInventoryData data = manager.getPlayerData(player.getUniqueId());
        if (data == null) return pages;
        for (int page = 1; page <= data.getMaxPages(); page++) {
            String number = String.valueOf(page);
            if (data.isAccessible(page) && number.startsWith(args[0])) pages.add(number);
        }
        return pages;
    }
}
