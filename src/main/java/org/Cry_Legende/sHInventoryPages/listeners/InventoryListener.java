package org.Cry_Legende.sHInventoryPages.listeners;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.inventory.InventoryManager;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil; // Gardé au cas où pour d'autres messages
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound; // Gardé pour les sons de succès
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class InventoryListener implements Listener {

    private final SHInventoryPages plugin;
    private final InventoryManager inventoryManager;
    private final ConfigManager configManager;

    public InventoryListener(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.inventoryManager = plugin.getInventoryManager();
        this.configManager = plugin.getConfigManager();
    }

    // Gère les interactions spécifiques au mode créatif pour éviter la duplication.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;

        // Lu à chaque événement : après un /ship reload qui change l'emplacement, l'ancien restait protégé
        int switcherSlot = inventoryManager.getSwitcherSlot();
        int clickedSlot = event.getSlot();

        // Si le joueur clique sur le slot du switcher avec le clic molette (CLONE_STACK)
        if (event.getClickedInventory() instanceof PlayerInventory &&
            clickedSlot == switcherSlot &&
            inventoryManager.isSwitcherItem(event.getCurrentItem(), player) &&
            event.getAction() == InventoryAction.CLONE_STACK) {

            event.setCancelled(true); // Empêche la duplication
            event.setCursor(null);    // S'assure que le curseur est vide
            // Le clic molette ne doit pas déclencher de switch de page.
            return;
        }

        // Si le joueur a un switcher sur son curseur (peu importe comment) et essaie de le placer
        if (inventoryManager.isSwitcherItem(event.getCursor(), player)) {
            event.setCancelled(true);
            event.setCursor(null); // Empêche de placer un switcher dupliqué
            return;
        }

        // Si le joueur essaie de placer un item normal sur le slot du switcher
        if (event.getClickedInventory() instanceof PlayerInventory &&
            clickedSlot == switcherSlot &&
            (event.getAction() == InventoryAction.PLACE_ALL ||
                event.getAction() == InventoryAction.PLACE_SOME ||
                event.getAction() == InventoryAction.PLACE_ONE ||
                event.getAction() == InventoryAction.SWAP_WITH_CURSOR)) {
            if (event.getCursor() != null && event.getCursor().getType() != Material.AIR && !inventoryManager.isSwitcherItem(event.getCursor(), player)) {
                event.setCancelled(true); // Empêche de remplacer le switcher
                // Pas de son d'échec, juste annuler
                return;
            }
        }
    }


    @EventHandler(priority = EventPriority.HIGH) // Peut être HIGH ou NORMAL
    public void onPlayerInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int switcherSlot = inventoryManager.getSwitcherSlot();

        // Clic dans l'inventaire du joueur
        if (event.getClickedInventory() instanceof PlayerInventory) {
            int clickedRawSlot = event.getRawSlot(); // Slot brut dans la fenêtre d'inventaire
            int clickedSlotInPlayerInv = event.getSlot(); // Slot dans l'inventaire PlayerInventory (0-40)

            // Si le clic est sur le slot du switcher
            if (clickedSlotInPlayerInv == switcherSlot) {
                event.setCancelled(true); // Toujours annuler l'action par défaut du clic sur ce slot.

                ItemStack currentItemInSlot = player.getInventory().getItem(switcherSlot); // Vérifier l'item réel
                if (!inventoryManager.isSwitcherItem(currentItemInSlot, player)) {
                    // Situation anormale: un item non-switcher est dans le slot.
                    // Le remplacer par le vrai switcher/barrière.
                    Bukkit.getScheduler().runTask(plugin, () -> inventoryManager.updateSwitcherItem(player));
                    if (player.getGameMode() == GameMode.CREATIVE) {
                        player.setItemOnCursor(null); // Nettoyer le curseur par sécurité
                        Bukkit.getScheduler().runTaskLater(plugin, player::updateInventory, 1L);
                    }
                    return;
                }

                // Si le monde est blacklisté ou bloqué par API, afficher message et barrière
                if (inventoryManager.isBlacklistedWorld(player.getWorld().getName()) || inventoryManager.isPageSwitchingApiBlocked(player)) {
                    Sound failSound = configManager.getSwitchFailSound(); // Obtenir le son d'échec ici
                    if (failSound != null && configManager.soundsEnabled()) {
                        player.playSound(player.getLocation(), failSound, configManager.getSoundVolume(), configManager.getSoundPitch());
                    }
                    MessageUtil.sendMessage(player, configManager.getMessage("cant_switch_here"));
                    // Assurer que la barrière est affichée (updateSwitcherItem le fera)
                    // Et nettoyer le curseur en créatif
                    if (player.getGameMode() == GameMode.CREATIVE) {
                        player.setItemOnCursor(null);
                        Bukkit.getScheduler().runTaskLater(plugin, player::updateInventory, 1L);
                    }
                    return;
                }

                // Gérer le changement de page
                ClickType click = event.getClick();

                // En créatif, le clic molette est pour la duplication, géré par onInventoryCreative.
                // Ici on s'assure qu'il ne fait rien d'autre.
                if (player.getGameMode() == GameMode.CREATIVE && click == ClickType.MIDDLE) {
                    player.setItemOnCursor(null); // Assurer que le curseur est vide
                    Bukkit.getScheduler().runTaskLater(plugin, player::updateInventory, 1L);
                    return; // Ne pas switcher sur clic molette
                }

                // Logique de switch pour clic gauche/droit
                if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
                    inventoryManager.switchPage(player, true);
                } else if (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT) {
                    inventoryManager.switchPage(player, false);
                }
                // Pour les autres types de clics (ex: touches numériques), l'event est déjà annulé,
                // donc l'item ne sera pas pris.
                // En mode créatif, il faut explicitement nettoyer le curseur si le client l'a pris.
                else if (player.getGameMode() == GameMode.CREATIVE) {
                    player.setItemOnCursor(null);
                    Bukkit.getScheduler().runTaskLater(plugin, player::updateInventory, 1L);
                }
                return; // Fin du traitement pour le slot du switcher
            }

            // Si preventSwitcherMove est activé et que ce n'est PAS le slot du switcher cliqué
            if (configManager.preventSwitcherMove()) {
                // Empêcher de placer un item (sur le curseur) sur le slot du switcher
                // (déjà couvert par le bloc if précédent si clickedSlotInPlayerInv == switcherSlot, mais redondance pour clarté)

                // Empêcher de déplacer le switcher via hotbar key (SHIFT + numéro)
                // (HOTBAR_MOVE_AND_READD n'est plus émis et sera retiré : HOTBAR_SWAP couvre les deux cas)
                if (event.getAction() == InventoryAction.HOTBAR_SWAP) {
                    int hotbarButtonSlot = event.getHotbarButton(); // Slot de la hotbar (0-8)

                    // ### CORRECTION ICI ###
                    // On vérifie que le slot de la hotbar est valide avant de l'utiliser.
                    if (hotbarButtonSlot == -1) {
                        return;
                    }
                    // ### FIN DE LA CORRECTION ###

                    ItemStack itemInHotbar = player.getInventory().getItem(hotbarButtonSlot);

                    // Si on tente de déplacer le switcher (qui est dans le hotbarButtonSlot) vers un autre slot (clickedSlotInPlayerInv)
                    if (hotbarButtonSlot == switcherSlot && inventoryManager.isSwitcherItem(itemInHotbar, player)) {
                        event.setCancelled(true);
                        if (player.getGameMode() == GameMode.CREATIVE) {
                            Bukkit.getScheduler().runTaskLater(plugin, player::updateInventory, 1L);
                        }
                        return;
                    }
                    // Si on tente de mettre un item (depuis itemInHotbar) sur le switcherSlot (via clickedSlotInPlayerInv)
                    if (clickedSlotInPlayerInv == switcherSlot && itemInHotbar != null && itemInHotbar.getType() != Material.AIR) {
                        event.setCancelled(true);
                        if (player.getGameMode() == GameMode.CREATIVE) {
                            Bukkit.getScheduler().runTaskLater(plugin, player::updateInventory, 1L);
                        }
                        return;
                    }
                }
            }
        }
        // Gérer le cas où le joueur a le switcher sur son curseur (suite à un bug/duplication)
        // et essaie de le placer dans un AUTRE inventaire (ex: coffre)
        else if (event.getClickedInventory() != null &&
            event.getClickedInventory().getType() != InventoryType.PLAYER && /* Ce n'est pas l'inventaire du joueur */
            inventoryManager.isSwitcherItem(event.getCursor(), player)) { /* Et il a le switcher sur le curseur */

            event.setCancelled(true);
            player.setItemOnCursor(null); // Vider le curseur
            // Pas besoin de updateInventory() ici car l'interaction est avec un autre inventaire.
            // Le serveur refuse simplement le placement.
        }
    }


    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (configManager.preventSwitcherMove() && event.getInventory().equals(player.getInventory())) {
            int switcherSlot = inventoryManager.getSwitcherSlot();
            for (int affectedPlayerSlot : event.getInventorySlots()) {
                if (affectedPlayerSlot == switcherSlot) {
                    event.setCancelled(true);
                    // En créatif, le client peut toujours afficher le "fantôme" de l'item dragué.
                    // Forcer une mise à jour peut aider.
                    if (player.getGameMode() == GameMode.CREATIVE) {
                        Bukkit.getScheduler().runTaskLater(plugin, player::updateInventory, 1L);
                    }
                    return;
                }
            }
        }
    }
}
