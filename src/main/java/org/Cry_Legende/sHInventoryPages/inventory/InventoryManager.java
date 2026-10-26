package org.Cry_Legende.sHInventoryPages.inventory;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.api.ISHInventoryPagesAPI;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class InventoryManager implements ISHInventoryPagesAPI {

    private final SHInventoryPages plugin;
    private final ConfigManager configManager;
    private final Map<UUID, PlayerInventoryData> playerDataCache = new ConcurrentHashMap<>();

    private int switcherSlot;
    private Material switcherMaterial;
    private String switcherNameFormat;
    private List<String> switcherLoreFormat;
    private boolean amountEqualsPage;
    private int fixedAmount;
    private boolean enchantGlow;
    private boolean saveOnPageSwitch;
    private Sound switchSound;
    private Sound failSound;
    private float soundVolume;
    private float soundPitch;
    private final NamespacedKey switcherPDCKey;

    private final Map<UUID, Set<String>> apiBlockedPlayers = new ConcurrentHashMap<>();

    // Identifiant pour le blocage pendant le chargement initial
    private static final String LOADING_BLOCK_ID = "shinventorypages:loading_data";
    // Identifiant en cas d'erreur critique (fallback)
    private static final String DB_ERROR_BLOCK_ID = "shinventorypages:db_error";


    public InventoryManager(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.switcherPDCKey = new NamespacedKey(plugin, "is_switcher");
        updateSettingsFromConfig();
    }

    public void updateSettingsFromConfig() {
        this.switcherSlot = configManager.getSwitcherSlot();
        this.switcherMaterial = configManager.getSwitcherItemMaterial();
        this.switcherNameFormat = configManager.getSwitcherItemName();
        this.switcherLoreFormat = configManager.getSwitcherItemLore();
        this.amountEqualsPage = configManager.isAmountEqualsPage();
        this.fixedAmount = configManager.getFixedAmount();
        this.enchantGlow = configManager.hasEnchantGlow();
        this.saveOnPageSwitch = configManager.saveOnPageSwitch();
        this.switchSound = configManager.soundsEnabled() ? configManager.getSwitchPageSound() : null;
        this.failSound = configManager.soundsEnabled() ? configManager.getSwitchFailSound() : null;
        this.soundVolume = configManager.getSoundVolume();
        this.soundPitch = configManager.getSoundPitch();
        plugin.getLogger().fine("InventoryManager settings updated from config.");
    }

    public void updateAllOnlineSwitcherItems() {
        plugin.getLogger().fine("Updating switcher items for all online players...");
        Bukkit.getOnlinePlayers().forEach(this::updateSwitcherItem);
    }

    // =========================================================================
    //  CHARGEMENT DES DONNÉES ROBUSTE (RETRY + SÉCURITÉ)
    // =========================================================================

    public void loadPlayerData(Player player) {
        // 1. Protection immédiate : On bloque les interactions pendant le chargement
        blockPageSwitching(player, LOADING_BLOCK_ID);

        // 2. Feedback visuel : On met la barrière tout de suite pour dire "Chargement..."
        if (switcherSlot >= 0 && switcherSlot < 36) {
            player.getInventory().setItem(switcherSlot, createBarrierItem());
        }

        plugin.getLogger().fine("Starting data load for " + player.getName() + "...");

        // 3. Lancement de la tentative de chargement (Tentative 1)
        attemptLoadPlayerData(player, 1);
    }

    private void attemptLoadPlayerData(Player player, int attempt) {
        if (!player.isOnline()) return; // Le joueur est parti, on arrête.

        UUID uuid = player.getUniqueId();
        int maxPages = getMaxPagesForPlayer(player);
        final int MAX_RETRIES = 3; // Nombre max d'essais

        plugin.getDatabaseManager().loadPlayerInventoriesAndEquipment(uuid)
            .thenAcceptAsync(loadedData -> {
                // --- SUCCÈS DU CHARGEMENT ---
                processLoadedData(player, loadedData, maxPages);

            }, Bukkit.getScheduler().getMainThreadExecutor(plugin)) // Retour sur le thread principal pour Bukkit API

            .exceptionally(ex -> {
                // --- ÉCHEC DU CHARGEMENT ---
                plugin.getLogger().log(Level.WARNING, "Attempt " + attempt + " failed to load data for " + player.getName());

                if (attempt < MAX_RETRIES) {
                    // Réessai dans 20 ticks (1 seconde)
                    plugin.getLogger().info("Retrying load for " + player.getName() + " (Attempt " + (attempt + 1) + "/" + MAX_RETRIES + ") in 1 second...");
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) {
                            attemptLoadPlayerData(player, attempt + 1);
                        }
                    }, 20L);
                } else {
                    // ÉCHEC TOTAL après 3 essais
                    plugin.getLogger().severe("CRITICAL: Could not load inventory for " + player.getName() + " after " + MAX_RETRIES + " attempts.");

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            // Kick de sécurité.
                            // C'est essentiel pour éviter que le joueur joue avec un inventaire vide et que le plugin
                            // écrase ses données existantes dans la DB lors de la sauvegarde automatique ou du quit.
                            player.kickPlayer(MessageUtil.colorize("&cError loading your inventory data.\nPlease rejoin the server.\n(Database Timeout Protection)"));
                        }
                    });
                }
                return null;
            });
    }

    private void processLoadedData(Player player, PlayerInventoryData loadedData, int maxPages) {
        UUID uuid = player.getUniqueId();
        PlayerInventoryData playerDataToCache;

        // Initialisation si aucune donnée ou nouvelle donnée
        if (loadedData == null || loadedData.getAllPages().isEmpty()) {
            playerDataToCache = new PlayerInventoryData(uuid, maxPages);
            if (!playerDataToCache.getAllPages().containsKey(1)) {
                playerDataToCache.setPage(1, createEmptyInventory());
            }
            playerDataToCache.setCurrentPage(1);
            plugin.getLogger().info("No existing data for " + player.getName() + ". Initializing with default page 1.");
        } else {
            playerDataToCache = loadedData;
            playerDataToCache.setMaxPages(maxPages);

            // Correction des incohérences de page
            if (playerDataToCache.getCurrentPage() > playerDataToCache.getMaxPages() && playerDataToCache.getMaxPages() > 0) {
                playerDataToCache.setCurrentPage(playerDataToCache.getMaxPages());
            } else if (playerDataToCache.getCurrentPage() <= 0) {
                playerDataToCache.setCurrentPage(1);
            }
            // S'assurer que la page actuelle n'est pas null
            if (playerDataToCache.getPage(playerDataToCache.getCurrentPage()) == null) {
                playerDataToCache.setPage(playerDataToCache.getCurrentPage(), createEmptyInventory());
                plugin.getLogger().warning("Content for current page " + playerDataToCache.getCurrentPage() +
                    " for player " + player.getName() + " was missing. Initialized as empty.");
            }
        }

        // Mise en cache
        playerDataCache.put(uuid, playerDataToCache);
        plugin.getLogger().fine("Player data for " + player.getName() + " cached successfully.");

        if (player.isOnline()) {
            // Sauvegarder l'état actuel (probablement vide ou items de connexion) dans l'objet data
            // pour ne pas les perdre si on applique une page DB.
            // NOTE: Si c'est la première connexion, saveCurrentInventoryToData va prendre le kit de départ s'il y en a un.
            saveCurrentInventoryToData(player, playerDataToCache);
            // Pages enregistrées avant le correctif du 24/10 : piles de plus de 99 ramenées avant qu'une page
            // n'arrive dans l'inventaire réel ; ce qui ne trouve pas de place est rendu après l'application de la page
            List<ItemStack> unplaced = playerDataToCache.splitOversizedStacks(switcherSlot);

            // IMPORTANT : On débloque le joueur car le chargement est fini
            unblockPageSwitching(player, LOADING_BLOCK_ID);

            // On met à jour l'inventaire visuel du joueur
            updateSwitcherItem(player);

            // Logique optionnelle : forcer l'application de la page courante pour la synchro
            // Mais attention, saveCurrentInventoryToData vient de sauver ce qu'il a sur lui.
            // Si on veut que la DB soit prioritaire (ex: crash serveur précédent), on ré-applique la page :
            applyInventoryPage(player, playerDataToCache.getCurrentPage());
            giveOrDrop(player, unplaced);

            plugin.getLogger().info("Finished loading for " + player.getName() + ". Inventory ready.");
        } else {
            // Le joueur a déco juste au moment où ça a fini de charger
            playerDataCache.remove(uuid);
            apiBlockedPlayers.remove(uuid);
        }
    }

    // =========================================================================

    public void unloadPlayerData(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerInventoryData data = playerDataCache.get(uuid);

        if (data != null) {
            plugin.getLogger().fine("Unloading player data for " + player.getName());
            saveCurrentInventoryToData(player, data);
            savePlayerData(data, true);
            playerDataCache.remove(uuid);
            plugin.getLogger().fine("Removed player data from cache for " + player.getName());
        } else {
            // Si data est null ici, c'est que le chargement a échoué (kick) ou n'a jamais fini.
            // On ne sauvegarde SURTOUT PAS pour ne pas écraser la DB avec du vide.
            plugin.getLogger().fine("No player data found in cache to unload for " + player.getName() + " (Load failed or incomplete). Skipping save.");
        }
        apiBlockedPlayers.remove(uuid);
    }

    public void saveCurrentInventoryToData(Player player, PlayerInventoryData data) {
        if (data == null || player == null || !player.isOnline()) return;
        PlayerInventory inv = player.getInventory();

        ItemStack[] currentPageContents = new ItemStack[36];
        List<ItemStack> excess = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            if (i == switcherSlot) {
                currentPageContents[i] = null;
            } else {
                ItemStack item = inv.getItem(i);
                if (item == null || item.getType() == Material.AIR) continue;
                int chunk = PlayerInventoryData.serializableChunk(item);
                if (item.getAmount() > chunk) {
                    // Pile trop grosse dans l'inventaire réel : corrigée dans l'inventaire lui-même, sinon chaque
                    // sauvegarde repartirait de la même pile et recréerait l'excédent
                    excess.addAll(PlayerInventoryData.excessParts(item, chunk));
                    item = item.clone();
                    item.setAmount(chunk);
                    inv.setItem(i, item);
                }
                currentPageContents[i] = item.clone();
            }
        }
        data.setPage(data.getCurrentPage(), currentPageContents);
        if (!excess.isEmpty()) {
            relocateLiveExcess(player, data, currentPageContents, excess);
        }

        // --- GESTION DE L'ARMURE ET OFFHAND DÉSACTIVÉE (COMMENTÉE) ---
        // data.setHelmet(inv.getHelmet() != null ? inv.getHelmet().clone() : null);
        // data.setChestplate(inv.getChestplate() != null ? inv.getChestplate().clone() : null);
        // data.setLeggings(inv.getLeggings() != null ? inv.getLeggings().clone() : null);
        // data.setBoots(inv.getBoots() != null ? inv.getBoots().clone() : null);
        // data.setOffHand(inv.getItemInOffHand() != null && inv.getItemInOffHand().getType() != Material.AIR ? inv.getItemInOffHand().clone() : null);

        plugin.getLogger().finest("Saved live inventory (page " + data.getCurrentPage() + ") to PlayerData object for " + player.getName());
    }

    /**
     * Range l'excédent d'une pile trop grosse de l'inventaire réel : emplacements libres de la page courante
     * (inventaire et données), puis autres pages, puis au sol aux pieds du joueur. Réparation silencieuse.
     */
    private void relocateLiveExcess(Player player, PlayerInventoryData data, ItemStack[] currentPageContents, List<ItemStack> parts) {
        PlayerInventory inv = player.getInventory();
        int total = 0;
        int dropped = 0;
        for (ItemStack part : parts) {
            total += part.getAmount();
            int free = -1;
            for (int i = 0; i < 36 && free < 0; i++) {
                if (i != switcherSlot && currentPageContents[i] == null) free = i;
            }
            if (free >= 0) {
                currentPageContents[free] = part.clone();
                inv.setItem(free, part);
            } else if (!data.placeInFreeSlot(part, switcherSlot)) {
                player.getWorld().dropItemNaturally(player.getLocation(), part);
                dropped += part.getAmount();
            }
        }
        plugin.getLogger().warning("Pile au-delà de la taille normale corrigée dans l'inventaire de " + player.getName()
            + " : " + total + " objets redistribués" + (dropped > 0 ? ", dont " + dropped + " posés au sol" : ""));
    }

    /** Rend à un joueur connecté les stacks qui n'ont trouvé de place dans aucune page (au sol si l'inventaire est plein). */
    private void giveOrDrop(Player player, List<ItemStack> parts) {
        if (parts.isEmpty() || !player.isOnline()) return;
        int dropped = 0;
        for (ItemStack left : player.getInventory().addItem(parts.toArray(new ItemStack[0])).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
            dropped += left.getAmount();
        }
        plugin.getLogger().warning("Excédent de piles trop grosses rendu à " + player.getName() + " : " + parts.size()
            + " stack(s)" + (dropped > 0 ? ", dont " + dropped + " objets posés au sol" : ""));
    }

    public void savePlayerData(PlayerInventoryData data, boolean async) {
        if (data == null) return;
        UUID uuid = data.getUuid();

        // Un seul stack de plus de 99 bloquerait la sauvegarde de toutes les pages du joueur
        for (ItemStack lost : data.splitOversizedStacks(switcherSlot)) {
            plugin.getLogger().severe("Plus de place pour un excédent de stack de " + uuid + " : "
                + lost.getAmount() + "x " + lost.getType() + " (non sauvegardé)");
        }

        if (!async) {
            plugin.getLogger().fine("Performing synchronous save for player " + uuid);
            try {
                plugin.getDatabaseManager().saveFullPlayerInventoryDataBlocking(data);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed synchronous save for player " + uuid, e);
            }
        } else {
            plugin.getLogger().fine("Performing asynchronous save for player " + uuid);
            // Copie prise sur le thread appelant : l'écriture async ne touche pas aux structures vivantes
            CompletableFuture<Void> future = plugin.getDatabaseManager().saveFullPlayerInventoryData(data.snapshot());
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    plugin.getLogger().log(Level.SEVERE, "Failed asynchronous save for player " + uuid, ex);
                }
            });
        }
    }

    public void saveAllPlayerData(boolean async) {
        if (playerDataCache.isEmpty()) {
            plugin.getLogger().info("No player data in cache to save during saveAll.");
            return;
        }
        plugin.getLogger().info("Starting save procedure for " + playerDataCache.size() + " players (" + (async ? "async" : "sync") + ")...");

        playerDataCache.forEach((uuid, data) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                saveCurrentInventoryToData(player, data);
            }
            savePlayerData(data, async);
        });
        plugin.getLogger().info("Finished save procedure.");
    }


    public void switchPage(Player player, boolean next) {
        PlayerInventoryData data = playerDataCache.get(player.getUniqueId());
        if (data == null) {
            // Cas rare où le joueur clique alors que le chargement a planté mais pas kické, ou lag
            plugin.getLogger().warning("Player " + player.getName() + " tried to switch page but data not loaded!");
            MessageUtil.sendMessage(player, "&cData loading... Please wait or relog if persistent.");
            // Au cas où, on remet le switcher en mode "Barrière"
            if (switcherSlot >= 0 && switcherSlot < 36) {
                player.getInventory().setItem(switcherSlot, createBarrierItem());
            }
            return;
        }

        if (isBlacklistedWorld(player.getWorld().getName()) || isPageSwitchingApiBlocked(player)) {
            playSound(player, failSound);
            MessageUtil.sendMessage(player, configManager.getMessage("cant_switch_here"));
            updateSwitcherItem(player);
            return;
        }

        int currentPage = data.getCurrentPage();
        int maxPages = data.getMaxPages();
        if (maxPages <= 0) maxPages = 1;
        int newTargetPage;

        if (next) {
            newTargetPage = currentPage + 1;
            if (newTargetPage > maxPages) newTargetPage = 1;
        } else {
            newTargetPage = currentPage - 1;
            if (newTargetPage < 1) newTargetPage = maxPages;
        }

        if (newTargetPage == currentPage && maxPages > 1) {
            playSound(player, failSound);
            return;
        }
        if (newTargetPage == currentPage && maxPages == 1) {
            updateSwitcherItem(player);
            return;
        }

        plugin.getLogger().fine("Player " + player.getName() + " switching from page " + currentPage + " to " + newTargetPage);
        saveCurrentInventoryToData(player, data);
        // la page cible ne doit pas arriver dans l'inventaire réel avec une pile de plus de 99
        List<ItemStack> unplaced = data.splitOversizedStacks(switcherSlot);

        if (saveOnPageSwitch) {
            ItemStack[] pageContentsToSave = data.getPage(currentPage);
            if (pageContentsToSave != null) {
                plugin.getDatabaseManager().savePlayerInventoryPage(data.getUuid(), currentPage, pageContentsToSave)
                    .exceptionally(ex -> {
                        plugin.getLogger().log(Level.SEVERE, "Failed async save of page " + currentPage + " for " + player.getName() + " on page switch.", ex);
                        return null;
                    });
            }
        }

        applyInventoryPage(player, newTargetPage);
        data.setCurrentPage(newTargetPage);
        updateSwitcherItem(player);
        giveOrDrop(player, unplaced);

        playSound(player, switchSound);
        plugin.getLogger().fine("Finished page switch for " + player.getName() + " to page " + newTargetPage);
    }


    private void applyInventoryPage(Player player, int pageNumber) {
        PlayerInventoryData data = playerDataCache.get(player.getUniqueId());
        if (data == null || player == null || !player.isOnline()) {
            return;
        }

        ItemStack[] newPageContents = data.getPage(pageNumber);
        if (newPageContents == null) {
            newPageContents = createEmptyInventory();
            data.setPage(pageNumber, newPageContents);
        }
        if (newPageContents.length != 36) {
            newPageContents = Arrays.copyOf(newPageContents, 36);
            data.setPage(pageNumber, newPageContents);
        }

        PlayerInventory inv = player.getInventory();

        for (int i = 0; i < 36; i++) {
            if (i != switcherSlot) {
                inv.setItem(i, null);
            }
        }
        for (int i = 0; i < 36; i++) {
            if (i != switcherSlot) {
                inv.setItem(i, (newPageContents[i] != null) ? newPageContents[i].clone() : null);
            }
        }

        // Armure/Offhand ignorés ici comme demandé
        player.updateInventory();
    }

    public void updateSwitcherItem(Player player) {
        if (player == null || !player.isOnline()) return;
        PlayerInventoryData data = playerDataCache.get(player.getUniqueId());

        // Si pas de data, on considère que c'est en chargement ou erreur => Barrière
        if (data == null) {
            if (switcherSlot >= 0 && switcherSlot < 36) {
                player.getInventory().setItem(switcherSlot, createBarrierItem());
            }
            return;
        }

        ItemStack switcherToSet;
        if (isBlacklistedWorld(player.getWorld().getName()) || isPageSwitchingApiBlocked(player)) {
            switcherToSet = createBarrierItem();
        } else {
            switcherToSet = createSwitcherItem(data.getCurrentPage(), data.getMaxPages());
        }
        if (switcherSlot >= 0 && switcherSlot < 36) {
            player.getInventory().setItem(switcherSlot, switcherToSet);
        }
    }

    public ItemStack createSwitcherItem(int currentPage, int maxPages) {
        ItemStack item = new ItemStack(switcherMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = MessageUtil.colorize(switcherNameFormat
                .replace("%current_page%", String.valueOf(currentPage))
                .replace("%max_pages%", String.valueOf(maxPages)));
            meta.setDisplayName(name);

            List<String> lore = switcherLoreFormat.stream()
                .map(line -> MessageUtil.colorize(line
                    .replace("%current_page%", String.valueOf(currentPage))
                    .replace("%max_pages%", String.valueOf(maxPages))))
                .collect(Collectors.toList());
            meta.setLore(lore);

            if (enchantGlow) {
                meta.addEnchant(Enchantment.LURE, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(switcherPDCKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        if (amountEqualsPage) {
            item.setAmount(Math.max(1, Math.min(currentPage, item.getMaxStackSize())));
        } else {
            item.setAmount(Math.max(1, Math.min(fixedAmount, item.getMaxStackSize())));
        }
        return item;
    }

    public ItemStack createBarrierItem() {
        ItemStack item = new ItemStack(configManager.getBarrierItemMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MessageUtil.colorize(configManager.getBarrierItemName()));
            List<String> lore = configManager.getBarrierItemLore().stream()
                .map(MessageUtil::colorize)
                .collect(Collectors.toList());
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(switcherPDCKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isBlacklistedWorld(String worldName) {
        return configManager.getBlacklistedWorlds().contains(worldName);
    }

    public boolean isSwitcherItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(switcherPDCKey, PersistentDataType.BYTE);
    }

    public boolean isSwitcherItem(ItemStack item, Player player) {
        return isSwitcherItem(item);
    }

    public int getMaxPagesForPlayer(Player player) {
        if (player == null) return configManager.getDefaultPages();

        int maxPermPages = 0;
        boolean hasWildcard = false;

        for (PermissionAttachmentInfo permInfo : player.getEffectivePermissions()) {
            String perm = permInfo.getPermission().toLowerCase();
            if (perm.startsWith("shinventorypages.pages.")) {
                if (perm.equals("shinventorypages.pages.*")) {
                    hasWildcard = true;
                    break;
                }
                try {
                    int pagesFromPerm = Integer.parseInt(perm.substring("shinventorypages.pages.".length()));
                    if (pagesFromPerm > maxPermPages) {
                        maxPermPages = pagesFromPerm;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        int resultPages;
        if (hasWildcard) {
            resultPages = configManager.getMaxPossiblePages();
        } else if (maxPermPages > 0) {
            resultPages = maxPermPages;
        } else {
            resultPages = configManager.getDefaultPages();
        }

        return Math.min(resultPages, configManager.getMaxPossiblePages());
    }

    public PlayerInventoryData getPlayerData(UUID uuid) {
        return playerDataCache.get(uuid);
    }

    public ItemStack[] createEmptyInventory() {
        return new ItemStack[36];
    }

    private void playSound(Player player, Sound sound) {
        if (player != null && sound != null && configManager.soundsEnabled()) {
            player.playSound(player.getLocation(), sound, soundVolume, soundPitch);
        }
    }

    public int getSwitcherSlot() {
        return switcherSlot;
    }

    @Override
    public void blockPageSwitching(@NotNull Player player, @NotNull String blockIdentifier) {
        apiBlockedPlayers.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(blockIdentifier);
        plugin.getLogger().fine("Page switching blocked for " + player.getName() + " by identifier: " + blockIdentifier);
        if (Bukkit.isPrimaryThread()) {
            updateSwitcherItem(player);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> updateSwitcherItem(player));
        }
    }

    @Override
    public void unblockPageSwitching(@NotNull Player player, @NotNull String blockIdentifier) {
        UUID uuid = player.getUniqueId();
        Set<String> blocks = apiBlockedPlayers.get(uuid);
        if (blocks != null) {
            blocks.remove(blockIdentifier);
            if (blocks.isEmpty()) {
                apiBlockedPlayers.remove(uuid);
                plugin.getLogger().fine("All API page switch blocks removed for " + player.getName());
            }
            if (Bukkit.isPrimaryThread()) {
                updateSwitcherItem(player);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> updateSwitcherItem(player));
            }
        }
    }

    @Override
    public boolean isPageSwitchingApiBlocked(@NotNull Player player) {
        Set<String> blocks = apiBlockedPlayers.get(player.getUniqueId());
        return blocks != null && !blocks.isEmpty();
    }

    @Override
    public boolean isPageSwitchingBlockedBy(@NotNull Player player, @NotNull String blockIdentifier) {
        Set<String> blocks = apiBlockedPlayers.get(player.getUniqueId());
        return blocks != null && blocks.contains(blockIdentifier);
    }
}
