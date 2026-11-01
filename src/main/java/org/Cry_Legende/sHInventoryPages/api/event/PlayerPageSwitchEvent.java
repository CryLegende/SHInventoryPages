package org.Cry_Legende.sHInventoryPages.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Un joueur va changer de page. Appelé avant toute modification de l'inventaire, sur le thread principal. Ne pas
 * modifier l'inventaire du joueur depuis un écouteur : annuler suffit. Un changement forcé (page devenue
 * inaccessible, {@link #isForced()}) ne peut pas être annulé.
 */
public class PlayerPageSwitchEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final int fromPage;
    private final int toPage;
    private final boolean forced;
    private boolean cancelled;

    public PlayerPageSwitchEvent(@NotNull Player player, int fromPage, int toPage, boolean forced) {
        super(player);
        this.fromPage = fromPage;
        this.toPage = toPage;
        this.forced = forced;
    }

    public boolean isForced() {
        return forced;
    }

    public int getFromPage() {
        return fromPage;
    }

    public int getToPage() {
        return toPage;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel && !forced;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
