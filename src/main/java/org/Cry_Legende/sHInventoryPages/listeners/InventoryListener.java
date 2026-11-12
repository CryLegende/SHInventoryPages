package org.Cry_Legende.sHInventoryPages.listeners;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.inventory.InventoryManager;
import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.Cry_Legende.sHInventoryPages.menu.PagesMenu;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le sélecteur de pages (et la barrière) est un objet d'interface : il ne quitte jamais son emplacement. Aucun
 * clic, glisser, touche numérique, échange de main, jet, pose, cadre, support d'armure, artisanat, entonnoir ou
 * ramassage ne peut le déplacer ou le copier ; un exemplaire trouvé ailleurs que sur son emplacement est supprimé.
 * Priorité LOWEST : la décision est prise avant les autres plugins.
 */
public class InventoryListener implements Listener {

    private final SHInventoryPages plugin;
    private final InventoryManager manager;
    private final ConfigManager config;
    /**
     * Emplacements annoncés par le client créatif de chaque joueur dans le tick en cours : {tick, nombre}. Un clic
     * n'en annonce qu'un ; un inventaire vidé d'un coup les annonce tous.
     */
    private final Map<UUID, int[]> creativeSlots = new ConcurrentHashMap<>();

    public InventoryListener(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.manager = plugin.getInventoryManager();
        this.config = plugin.getConfigManager();
    }

    /** Reçoit aussi InventoryCreativeEvent, qui partage la liste d'écouteurs d'InventoryClickEvent. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event instanceof InventoryCreativeEvent) countCreativeSlot(player);
        int switcherSlot = manager.getSwitcherSlot();
        Inventory clicked = event.getClickedInventory();
        boolean ownInventory = clicked instanceof PlayerInventory inventory && isHolder(inventory, player);
        PlayerInventory inv = player.getInventory();

        // Touche numérique vers l'emplacement du sélecteur quand il est dans la barre d'action, quel que soit
        // l'inventaire visé (un coffre compris)
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() == switcherSlot) {
            deny(event, player);
            return;
        }

        if (ownInventory && event.getSlot() == switcherSlot) {
            event.setCancelled(true);
            handleSwitcherClick(player, event);
            return;
        }

        // Emplacement du sélecteur d'un autre joueur (inventaire ouvert par le staff) : jamais modifiable
        if (clicked instanceof PlayerInventory && !ownInventory && event.getSlot() == switcherSlot) {
            event.setCancelled(true);
            return;
        }

        // Exemplaire hors de sa place (curseur, autre emplacement, autre inventaire) : supprimé
        if (manager.isSwitcherItem(event.getCursor())) {
            deny(event, player);
            clearCursorLater(player);
            return;
        }
        if (manager.isSwitcherItem(event.getCurrentItem())) {
            event.setCancelled(true);
            event.setCurrentItem(null);
            resyncLater(player);
            return;
        }
        int hotbar = event.getHotbarButton();
        if (event.getClick() == ClickType.NUMBER_KEY && hotbar >= 0 && manager.isSwitcherItem(inv.getItem(hotbar))) {
            event.setCancelled(true);
            inv.setItem(hotbar, null);
            resyncLater(player);
            return;
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND && manager.isSwitcherItem(inv.getItemInOffHand())) {
            event.setCancelled(true);
            inv.setItemInOffHand(null);
            resyncLater(player);
        }
    }

    private void handleSwitcherClick(Player player, InventoryClickEvent event) {
        boolean creative = event instanceof InventoryCreativeEvent || player.getGameMode() == GameMode.CREATIVE;
        if (creative) {
            // Le client créatif écrit lui-même ses emplacements : l'état du serveur lui est renvoyé
            resyncLater(player);
        }
        if (!manager.isSwitcherItem(player.getInventory().getItem(manager.getSwitcherSlot()))) {
            resyncLater(player);
            return;
        }
        if (event instanceof InventoryCreativeEvent) {
            creativeClick(player, event.getCursor());
            return;
        }
        switch (event.getClick()) {
            case RIGHT -> manager.switchPage(player, true);
            case LEFT -> manager.switchPage(player, false);
            case SHIFT_RIGHT -> openMenu(player, true);
            case SHIFT_LEFT -> openMenu(player, false);
            // Clic molette : envoyé seulement par un client en mode créatif, devant un coffre ou un autre conteneur
            case MIDDLE -> openMenu(player, null);
            case NUMBER_KEY -> wearPage(player, event.getHotbarButton() + 1);
            default -> {
            }
        }
    }

    /**
     * Inventaire du mode créatif : le client n'envoie pas le clic mais l'emplacement tel qu'il le voit ensuite, si bien
     * que clic gauche, clic droit et shift + clic ne se distinguent pas. Le sélecteur pris en main (emplacement annoncé
     * vide, ou réduit) ouvre le menu des pages ; un objet posé dessus, ou un inventaire vidé d'un coup, ne l'ouvre pas.
     */
    private void creativeClick(Player player, ItemStack announced) {
        boolean taken = announced == null || announced.isEmpty() || manager.isSwitcherItem(announced);
        if (!taken || !config.isMenuEnabled() || !PagesMenu.allowed(player, PagesMenu.PERMISSION)) return;
        UUID uuid = player.getUniqueId();
        int tick = Bukkit.getCurrentTick();
        Bukkit.getScheduler().runTask(plugin, () -> {
            int[] burst = creativeSlots.get(uuid);
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || (burst != null && burst[0] == tick && burst[1] > 1)) return;
            plugin.getPagesMenu().open(online);
        });
    }

    private void countCreativeSlot(Player player) {
        int tick = Bukkit.getCurrentTick();
        creativeSlots.compute(player.getUniqueId(), (uuid, burst) ->
            burst == null || burst[0] != tick ? new int[]{tick, 1} : new int[]{tick, burst[1] + 1});
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        creativeSlots.remove(event.getPlayer().getUniqueId());
    }

    /** Shift + clic : menu des pages ; sans menu (désactivé, permission refusée), page suivante ou précédente comme un clic. */
    private void openMenu(Player player, Boolean next) {
        if (!config.isMenuEnabled() || !PagesMenu.allowed(player, PagesMenu.PERMISSION)) {
            if (next != null) manager.switchPage(player, next);
            return;
        }
        UUID uuid = player.getUniqueId();
        // Au tick suivant : un inventaire ne s'ouvre pas pendant le traitement d'un clic
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) plugin.getPagesMenu().open(online);
        });
    }

    /** Touche 1 à 9 au survol du sélecteur : page de ce numéro. */
    private void wearPage(Player player, int page) {
        // Le client a déjà échangé les deux emplacements à l'écran : l'état du serveur lui est renvoyé
        resyncLater(player);
        if (!config.isMenuNumberKeys()) return;
        PlayerInventoryData data = manager.getPlayerData(player.getUniqueId());
        if (data != null && !data.isAccessible(page)) {
            manager.playFailSound(player);
            return;
        }
        manager.switchToPage(player, page);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (manager.isSwitcherItem(event.getOldCursor())) {
            event.setCancelled(true);
            clearCursorLater(player);
            return;
        }
        int switcherSlot = manager.getSwitcherSlot();
        InventoryView view = event.getView();
        for (int rawSlot : event.getRawSlots()) {
            if (view.getInventory(rawSlot) instanceof PlayerInventory && view.convertSlot(rawSlot) == switcherSlot) {
                event.setCancelled(true);
                resyncLater(player);
                return;
            }
        }
    }

    /** Après un rangement à la main : copie fraîche de la page courante en mémoire (rien n'est écrit si identique). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) manager.captureIfLoaded(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (manager.isSwitcherItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            MessageUtil.sendMessage(event.getPlayer(), config.getMessage("cant_drop_switcher"));
            resyncLater(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (manager.isSwitcherItem(event.getMainHandItem()) || manager.isSwitcherItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    // --- Sélecteur en main (emplacement dans la barre d'action) : aucune utilisation possible ---

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (manager.isSwitcherItem(event.getItem())) {
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (manager.isSwitcherItem(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (manager.isSwitcherItem(event.getPlayerItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (manager.isSwitcherItem(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (manager.isSwitcherItem(item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    // --- Exemplaire au sol (ancien bug) : jamais ramassé, supprimé ---

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (manager.isSwitcherItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (manager.isSwitcherItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    private static boolean isHolder(PlayerInventory inventory, Player player) {
        HumanEntity holder = inventory.getHolder();
        return holder != null && holder.getUniqueId().equals(player.getUniqueId());
    }

    private void deny(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        resyncLater(player);
    }

    /** Remet le vrai sélecteur et renvoie l'inventaire au client au tick suivant. */
    private void resyncLater(Player player) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) return;
            manager.updateSwitcherItem(online);
            online.updateInventory();
        });
    }

    private void clearCursorLater(Player player) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) return;
            if (manager.isSwitcherItem(online.getItemOnCursor())) online.setItemOnCursor(null);
            manager.updateSwitcherItem(online);
            online.updateInventory();
        });
    }
}
