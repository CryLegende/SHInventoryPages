package org.Cry_Legende.sHInventoryPages.listeners;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.inventory.InventoryManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.UUID;
import java.util.function.Consumer;

/** Cycle de vie : connexion, départ, mort, réapparition, changements de monde et de mode de jeu. */
public class PlayerListener implements Listener {

    private final SHInventoryPages plugin;
    private final InventoryManager manager;

    public PlayerListener(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.manager = plugin.getInventoryManager();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        manager.loadPlayerData(event.getPlayer());
    }

    /**
     * Tout départ, kick compris, passe par PlayerQuitEvent. MONITOR : après les plugins qui rendent un inventaire
     * au départ (parcours...), pour capturer l'inventaire définitif.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.unloadPlayerData(event.getPlayer());
    }

    /** Tôt : un plugin de tombe ou de butin ne doit jamais récupérer le sélecteur. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDeathEarly(PlayerDeathEvent event) {
        manager.stripInterfaceItems(event.getDrops());
    }

    /** Tard : la décision de garder l'inventaire (gamerule, parcours, autres plugins) est prise. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        manager.handleDeath(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        nextTick(event.getPlayer(), manager::handleRespawn);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        nextTick(event.getPlayer(), manager::updateSwitcherItem);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGamemodeChange(PlayerGameModeChangeEvent event) {
        nextTick(event.getPlayer(), manager::updateSwitcherItem);
    }

    private void nextTick(Player player, Consumer<Player> action) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) action.accept(online);
        });
    }
}
