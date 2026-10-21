package org.Cry_Legende.sHInventoryPages.listeners;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.inventory.InventoryManager;
import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class PlayerListener implements Listener {

    private final SHInventoryPages plugin;
    private final InventoryManager inventoryManager;
    private final ConfigManager configManager;

    public PlayerListener(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.inventoryManager = plugin.getInventoryManager();
        this.configManager = plugin.getConfigManager();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        inventoryManager.loadPlayerData(player);
//        new BukkitRunnable() {
//            @Override
//            public void run() {
//                if (player.isOnline()) {
//                    inventoryManager.loadPlayerData(player);
//                }
//            }
//        }.runTaskLater(plugin, 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        handlePlayerDisconnect(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        handlePlayerDisconnect(event.getPlayer());
    }

    private void handlePlayerDisconnect(Player player) {
        inventoryManager.unloadPlayerData(player);
    }

    /**
     * Gère le débordement des items (ex: /give ou crate quand full).
     * Utilise ItemSpawnEvent pour attraper l'item AVANT qu'il ne tombe dans le vide (SkyBlock).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item itemEntity = event.getEntity();

        // --- SÉCURITÉ 1 : Délai de ramassage ---
        // Si l'item a un délai (ex: joueur qui drop avec 'Q'), on l'ignore.
        // Les items systèmes (give/overflow) ont généralement un délai de 0.
        // Seuil à 10 ticks (0.5s) pour être sûr.
        if (itemEntity.getPickupDelay() > 10) {
            return;
        }

        // Rayon de recherche très court (1.0 bloc) pour éviter de voler les items du voisin
        List<Entity> nearbyEntities = itemEntity.getNearbyEntities(1.0, 1.0, 1.0);

        for (Entity entity : nearbyEntities) {
            if (entity instanceof Player player) {

                // --- SÉCURITÉ 2 : Distance stricte ---
                // L'item doit spawner quasi SUR le joueur (cas typique d'un inventaire plein)
                if (player.getLocation().distanceSquared(itemEntity.getLocation()) > 2.25) { // ~1.5 blocks au carré
                    continue;
                }

                // --- SÉCURITÉ 3 : Vérification de l'inventaire actuel ---
                // Si l'inventaire actuel a de la place (slot vide ou stack incomplet),
                // on laisse Minecraft gérer le ramassage normalement.
                if (canItemFitInInventory(player.getInventory(), itemEntity.getItemStack())) {
                    continue;
                }

                PlayerInventoryData data = inventoryManager.getPlayerData(player.getUniqueId());
                // Si le joueur n'a pas de pages supplémentaires, on ne fait rien.
                if (data == null || data.getMaxPages() <= 1) {
                    continue;
                }

                // Tentative de placement dans les pages suivantes
                boolean itemPlaced = tryPlaceItemInOverflow(data, itemEntity.getItemStack());

                if (itemPlaced) {
                    event.setCancelled(true); // Annule le spawn physique (empêche la chute dans le vide)
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.5f);

                    // Message optionnel pour prévenir le joueur
                    MessageUtil.sendMessage(player, configManager.getMessage("item_sent_to_next_page"));

                    // Force l'update visuel pour éviter les items fantômes
                    player.updateInventory();

                    // Sauvegarde asynchrone pour sécuriser l'item
                    inventoryManager.savePlayerData(data, true);
                    return; // Item traité, on arrête.
                }
            }
        }
    }

    /**
     * Vérifie si un item peut rentrer dans l'inventaire actuel.
     */
    private boolean canItemFitInInventory(PlayerInventory inv, ItemStack item) {
        if (item == null) return true;

        // 1. Slot complètement vide ?
        if (inv.firstEmpty() != -1) return true;

        // 2. Stack incomplet compatible ?
        for (ItemStack content : inv.getStorageContents()) {
            if (content != null && content.isSimilar(item)) {
                if (content.getAmount() < content.getMaxStackSize()) {
                    int spaceRemaining = content.getMaxStackSize() - content.getAmount();
                    if (spaceRemaining >= item.getAmount()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Essaye de placer l'item dans les pages suivantes.
     * Pour éviter les duolications avec ItemSpawnEvent, cette méthode fonctionne en mode "Tout ou Rien".
     * Elle ne retourne true (et modifie les pages) que si l'item ENTIER peut être stocké.
     */
    private boolean tryPlaceItemInOverflow(PlayerInventoryData data, ItemStack itemToPlace) {
        if (itemToPlace == null || itemToPlace.getType() == Material.AIR) {
            return false;
        }

        int amountToStore = itemToPlace.getAmount();
        int currentPage = data.getCurrentPage();
        int maxPages = data.getMaxPages();

        // --- ÉTAPE 1 : Simulation / Vérification de l'espace ---
        // On vérifie d'abord si on AURAIT assez de place, sans rien modifier.
        int simulatedSpaceFound = 0;

        for (int i = 1; i < maxPages; i++) {
            if (simulatedSpaceFound >= amountToStore) break;

            int pageToCheck = (currentPage - 1 + i) % maxPages + 1;
            if (pageToCheck == currentPage) continue;

            ItemStack[] pageContents = data.getPage(pageToCheck);
            if (pageContents == null) {
                // Page vide = 36 slots * 64 items (large place)
                simulatedSpaceFound += (36 * itemToPlace.getMaxStackSize());
            } else {
                for (ItemStack item : pageContents) {
                    if (item == null || item.getType() == Material.AIR) {
                        simulatedSpaceFound += itemToPlace.getMaxStackSize();
                    } else if (item.isSimilar(itemToPlace)) {
                        simulatedSpaceFound += (item.getMaxStackSize() - item.getAmount());
                    }
                }
            }
        }

        // Si pas assez de place pour TOUT l'item, on annule (pour éviter de dupliquer ou perdre une moitié)
        if (simulatedSpaceFound < amountToStore) {
            return false;
        }

        // --- ÉTAPE 2 : Application réelle ---
        ItemStack remainingItem = itemToPlace.clone();

        for (int i = 1; i < maxPages; i++) {
            if (remainingItem.getAmount() <= 0) break;

            int pageToCheck = (currentPage - 1 + i) % maxPages + 1;
            if (pageToCheck == currentPage) continue;

            ItemStack[] pageContents = data.getPage(pageToCheck);
            if (pageContents == null) {
                pageContents = inventoryManager.createEmptyInventory();
                data.setPage(pageToCheck, pageContents);
            }

            boolean pageChanged = false;

            // 1. Remplir les stacks existants
            for (ItemStack item : pageContents) {
                if (remainingItem.getAmount() <= 0) break;
                if (item != null && item.isSimilar(remainingItem) && item.getAmount() < item.getMaxStackSize()) {
                    int space = item.getMaxStackSize() - item.getAmount();
                    int toAdd = Math.min(space, remainingItem.getAmount());

                    item.setAmount(item.getAmount() + toAdd);
                    remainingItem.setAmount(remainingItem.getAmount() - toAdd);
                    pageChanged = true;
                }
            }

            // 2. Remplir les slots vides
            if (remainingItem.getAmount() > 0) {
                for (int j = 0; j < pageContents.length; j++) {
                    if (remainingItem.getAmount() <= 0) break;
                    if (pageContents[j] == null || pageContents[j].getType() == Material.AIR) {
                        pageContents[j] = remainingItem.clone();
                        remainingItem.setAmount(0); // Tout est placé
                        pageChanged = true;
                        // Attention: on a cloné le reste, donc le stack dans l'inv est bon, on sort de la boucle interne
                        break;
                    }
                }
            }

            if (pageChanged) {
                data.setPage(pageToCheck, pageContents);
            }
        }

        return true;
    }


    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        PlayerInventoryData data = inventoryManager.getPlayerData(player.getUniqueId());
        if (data == null) {
            plugin.getLogger().warning("PlayerDeathEvent: No PlayerInventoryData for " + player.getName());
            event.getDrops().removeIf(item -> item != null && inventoryManager.isSwitcherItem(item));
            return;
        }

        boolean keepInventoryGamerule = Boolean.TRUE.equals(player.getWorld().getGameRuleValue(org.bukkit.GameRule.KEEP_INVENTORY));
        event.getDrops().removeIf(item -> item != null && inventoryManager.isSwitcherItem(item));

        if (keepInventoryGamerule) {
            return;
        }

        event.setKeepInventory(false);
        event.setKeepLevel(false);

        List<ItemStack> itemsToDropFromPages = new ArrayList<>();
        String dropMode = configManager.getDeathDropMode().toUpperCase();

        if (dropMode.equals("CURRENT")) {
            ItemStack[] currentPageContents = data.getPage(data.getCurrentPage());
            if (currentPageContents != null) {
                for (int i = 0; i < currentPageContents.length; i++) {
                    ItemStack item = currentPageContents[i];
                    if (item != null && item.getType() != Material.AIR) {
                        itemsToDropFromPages.add(item.clone());
                        currentPageContents[i] = null;
                    }
                }
            }
        } else if (dropMode.equals("ALL")) {
            for (Integer pageNum : new HashSet<>(data.getAllPages().keySet())) {
                ItemStack[] pageContents = data.getPage(pageNum);
                if (pageContents != null) {
                    for (int i = 0; i < pageContents.length; i++) {
                        ItemStack item = pageContents[i];
                        if (item != null && item.getType() != Material.AIR) {
                            itemsToDropFromPages.add(item.clone());
                            pageContents[i] = null;
                        }
                    }
                }
            }
        }

        if (dropMode.equals("CURRENT") || dropMode.equals("ALL")) {
            event.getDrops().clear();
            event.getDrops().addAll(itemsToDropFromPages);

            PlayerInventory inv = player.getInventory();
            if (inv.getHelmet() != null) event.getDrops().add(inv.getHelmet().clone());
            data.setHelmet(null);
            if (inv.getChestplate() != null) event.getDrops().add(inv.getChestplate().clone());
            data.setChestplate(null);
            if (inv.getLeggings() != null) event.getDrops().add(inv.getLeggings().clone());
            data.setLeggings(null);
            if (inv.getBoots() != null) event.getDrops().add(inv.getBoots().clone());
            data.setBoots(null);
            if (inv.getItemInOffHand().getType() != Material.AIR) event.getDrops().add(inv.getItemInOffHand().clone());
            data.setOffHand(null);
        }

        inventoryManager.savePlayerData(data, true);

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player onlinePlayer = Bukkit.getPlayer(player.getUniqueId());
            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                PlayerInventory pInv = onlinePlayer.getInventory();
                if (!keepInventoryGamerule) {
                    for (int i = 0; i < 36; i++) {
                        if (i != inventoryManager.getSwitcherSlot()) {
                            pInv.setItem(i, null);
                        }
                    }
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        new BukkitRunnable() {
            @Override
            public void run() {
                Player onlinePlayer = Bukkit.getPlayer(player.getUniqueId());
                if (onlinePlayer == null || !onlinePlayer.isOnline()) return;
                if (inventoryManager.getPlayerData(onlinePlayer.getUniqueId()) == null) {
                    inventoryManager.loadPlayerData(onlinePlayer);
                } else {
                    PlayerInventoryData data = inventoryManager.getPlayerData(onlinePlayer.getUniqueId());
                    if (data != null) {
                        inventoryManager.updateSwitcherItem(onlinePlayer);
                    }
                }
            }
        }.runTaskLater(plugin, 1L);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(player.getUniqueId());
            if (p != null && p.isOnline()) inventoryManager.updateSwitcherItem(p);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (configManager.preventSwitcherDrop() && inventoryManager.isSwitcherItem(event.getItemDrop().getItemStack(), event.getPlayer())) {
            event.setCancelled(true);
            MessageUtil.sendMessage(event.getPlayer(), configManager.getMessage("cant_drop_switcher"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (configManager.preventSwitcherMove()) {
            ItemStack mainHandItem = event.getMainHandItem();
            ItemStack offHandItem = event.getOffHandItem();
            if (inventoryManager.isSwitcherItem(mainHandItem, event.getPlayer()) ||
                inventoryManager.isSwitcherItem(offHandItem, event.getPlayer())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().toLowerCase().split(" ")[0];
        if (command.startsWith("/lp") || command.startsWith("/luckperms") || command.contains("permission")) {
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player onlinePlayer = Bukkit.getPlayer(player.getUniqueId());
                if (onlinePlayer == null || !onlinePlayer.isOnline()) return;
                PlayerInventoryData data = inventoryManager.getPlayerData(onlinePlayer.getUniqueId());
                if (data != null) {
                    int oldMaxPages = data.getMaxPages();
                    int newMaxPages = inventoryManager.getMaxPagesForPlayer(onlinePlayer);
                    if (oldMaxPages != newMaxPages) {
                        data.setMaxPages(newMaxPages);
                        if (data.getCurrentPage() > newMaxPages && newMaxPages > 0) {
                            data.setCurrentPage(newMaxPages);
                            inventoryManager.switchPage(onlinePlayer, false);
                        }
                        inventoryManager.updateSwitcherItem(onlinePlayer);
                        MessageUtil.sendMessage(onlinePlayer, configManager.getMessage("max_pages_updated", "%max_pages%", String.valueOf(newMaxPages)));
                    }
                }
            }, 20L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGamemodeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(player.getUniqueId());
            if (p != null && p.isOnline()) inventoryManager.updateSwitcherItem(p);
        }, 1L);
    }
}
