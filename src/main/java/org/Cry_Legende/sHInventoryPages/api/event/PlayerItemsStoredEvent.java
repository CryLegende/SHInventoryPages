package org.Cry_Legende.sHInventoryPages.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Des objets viennent d'être rangés dans une page enregistrée (autre que la page portée) : débordement d'un objet
 * donné au joueur, ou {@code addItems} de l'API. Informatif, sur le thread principal, après le rangement.
 */
public class PlayerItemsStoredEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();
    private final ItemStack item;
    private final int amount;
    private final int firstPage;

    public PlayerItemsStoredEvent(@NotNull Player player, @NotNull ItemStack item, int amount, int firstPage) {
        super(player);
        this.item = item;
        this.amount = amount;
        this.firstPage = firstPage;
    }

    /** Copie d'un exemplaire de l'objet rangé. */
    public @NotNull ItemStack getItem() {
        return item.clone();
    }

    /** Nombre d'exemplaires rangés dans les pages enregistrées. */
    public int getAmount() {
        return amount;
    }

    /** Première page qui a reçu des objets. */
    public int getFirstPage() {
        return firstPage;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
