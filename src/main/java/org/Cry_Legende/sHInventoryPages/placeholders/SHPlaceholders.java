package org.Cry_Legende.sHInventoryPages.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.inventory.InventoryManager;
import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class SHPlaceholders extends PlaceholderExpansion {

    private final SHInventoryPages plugin;
    private final InventoryManager inventoryManager;

    public SHPlaceholders(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.inventoryManager = plugin.getInventoryManager();
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "shinventorypages"; // Placeholder will be %shinventorypages_...%
    }

    @Override
    @NotNull
    public String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    @NotNull
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // gardée au /papi reload ; retirée par SHInventoryPages (placeholders.enabled, arrêt)
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    @Nullable
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || !player.isOnline()) {
            return null; // Only provide placeholders for online players
        }

        Player onlinePlayer = player.getPlayer();
        if (onlinePlayer == null) return null; // Should not happen if isOnline is true

        PlayerInventoryData data = inventoryManager.getPlayerData(onlinePlayer.getUniqueId());
        if (data == null) {
            return plugin.getConfigManager().getMessage("placeholder_loading");
        }

        params = params.toLowerCase(); // Standardize parameter name

        switch (params) {
            case "current_page":
                return String.valueOf(data.getCurrentPage());
            case "max_pages":
                return String.valueOf(data.getMaxPages());
            // Add more placeholders here if needed later
            // case "some_other_value":
            //    return "value";
            default:
                return null; // Unknown placeholder
        }
    }
}