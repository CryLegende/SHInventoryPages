package org.Cry_Legende.sHInventoryPages.network;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Mode réseau : ce qui sépare l'arrivée et le départ d'un joueur entre deux serveurs qui partagent la même base de pages.
 *
 * <p>Deux mécanismes indépendants garantissent que le joueur retrouve ses objets, et les pages attendent les deux :</p>
 * <ul>
 *   <li>la barrière de SHNetwork : le serveur de départ confirme que ses pages sont validées en base
 *       ({@link NetworkLink#registerSaveTask}, {@link NetworkLink#awaitSaved}) ;</li>
 *   <li>HuskSync, s'il est installé : il verrouille le joueur à son arrivée et lui applique l'inventaire porté (la page
 *       portée et son sélecteur y sont) une fois que le serveur de départ l'a enregistré. L'événement de fin de
 *       synchronisation est écouté par réflexion : le plugin n'en dépend pas à la compilation.</li>
 * </ul>
 *
 * <p>Les pages ne se chargent qu'après les deux : lire l'inventaire porté avant HuskSync ferait capturer l'inventaire
 * local périmé comme page portée, et lire la base avant la barrière ferait charger des pages pas encore validées.</p>
 */
public final class NetworkSync {

    /** Issue de l'attente d'arrivée. */
    public enum Arrival {
        /** Départ confirmé par l'autre serveur et inventaire synchronisé. */
        OK,
        /** Départ non confirmé (autre serveur arrêté brutalement, ou sauvegarde incomplète) : on charge quand même. */
        UNCONFIRMED,
        /** HuskSync n'a pas fini d'appliquer l'inventaire dans le délai : le joueur n'est pas chargé. */
        SYNC_FAILED
    }

    private final SHInventoryPages plugin;
    private final NetworkLink link;
    private final Duration barrierWait;
    private final long syncWaitSeconds;
    /** Fin de synchronisation HuskSync de chaque joueur arrivé ou en train d'arriver. */
    private final Map<UUID, CompletableFuture<Boolean>> syncGates = new ConcurrentHashMap<>();
    private volatile boolean syncAware;

    public NetworkSync(SHInventoryPages plugin, NetworkLink link, Duration barrierWait, long syncWaitSeconds) {
        this.plugin = plugin;
        this.link = link;
        this.barrierWait = barrierWait;
        this.syncWaitSeconds = Math.max(1L, syncWaitSeconds);
    }

    /** Inscrit la sauvegarde de départ auprès de SHNetwork et se met à l'écoute de HuskSync. */
    public void start(Function<Player, CompletableFuture<Void>> departure) {
        link.registerSaveTask(departure);
        listenHuskSync();
    }

    /** Vrai si l'arrivée attend la synchronisation de HuskSync. */
    public boolean isSyncAware() {
        return syncAware;
    }

    /**
     * Barrière de SHNetwork et fin de synchronisation de HuskSync (si présent) du joueur qui arrive. Le futur se
     * termine sur un autre fil que le fil principal.
     */
    public CompletableFuture<Arrival> awaitArrival(Player player) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<Boolean> saved = link.awaitSaved(uuid, barrierWait)
            .exceptionally(error -> {
                plugin.getLogger().log(Level.WARNING, "Départ de " + player.getName() + " impossible à confirmer : " + error);
                return false;
            });
        CompletableFuture<Boolean> synced;
        if (syncAware) {
            CompletableFuture<Boolean> gate = syncGates.computeIfAbsent(uuid, k -> new CompletableFuture<>());
            synced = gate.copy().completeOnTimeout(false, syncWaitSeconds, TimeUnit.SECONDS);
        } else {
            synced = CompletableFuture.completedFuture(true);
        }
        return saved.thenCombine(synced, (barrierOk, syncOk) -> {
            syncGates.remove(uuid);
            if (!syncOk) return Arrival.SYNC_FAILED;
            return Boolean.TRUE.equals(barrierOk) ? Arrival.OK : Arrival.UNCONFIRMED;
        });
    }

    /** Le joueur est parti : plus rien à attendre pour lui. */
    public void forget(UUID uuid) {
        syncGates.remove(uuid);
    }

    /**
     * Écoute la fin de synchronisation de HuskSync (classe lue par réflexion : sans HuskSync rien n'est chargé).
     */
    private void listenHuskSync() {
        Plugin husk = plugin.getServer().getPluginManager().getPlugin("HuskSync");
        if (husk == null || !husk.isEnabled()) return;
        try {
            Class<? extends Event> type = Class.forName("net.william278.husksync.event.BukkitSyncCompleteEvent", false,
                husk.getClass().getClassLoader()).asSubclass(Event.class);
            Method getUser = type.getMethod("getUser");
            Method getUuid = getUser.getReturnType().getMethod("getUuid");
            plugin.getServer().getPluginManager().registerEvent(type, new Listener() {
            }, EventPriority.MONITOR, (listener, event) -> {
                try {
                    UUID uuid = (UUID) getUuid.invoke(getUser.invoke(event));
                    syncGates.computeIfAbsent(uuid, k -> new CompletableFuture<>()).complete(true);
                } catch (ReflectiveOperationException e) {
                    plugin.getLogger().log(Level.WARNING, "Événement de HuskSync illisible : " + e);
                }
            }, plugin);
            syncAware = true;
            plugin.getLogger().info("HuskSync détecté : les pages d'un joueur qui arrive attendent la fin de sa synchronisation.");
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "HuskSync présent mais sa fin de synchronisation est inécoutable : " + e);
        }
    }
}
