package org.Cry_Legende.sHInventoryPages;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import net.kyori.adventure.key.Key;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aveugle le joueur (effet Cécité) tant qu'un dialogue est affiché : le texte se lit sur fond noir au lieu du décor.
 * <p>
 * Fermeture détectée par un clic : tout bouton à action serveur (custom) prévient le serveur. Un bouton qui ferme simplement
 * le dialogue porte {@link #closeAction()} : la vue est rendue tout de suite. Mis sur le bouton du bas (exit_action), il sert
 * aussi à Échap, que le client traduit par l'action de ce bouton. Après un autre bouton, la vue n'est rendue que si aucun
 * nouveau dialogue ne s'est ouvert dans les 4 ticks (un bouton peut enchaîner sur un autre dialogue).
 * <p>
 * Sinon (bouton de lien, Échap sans bouton du bas, bouton sans action), le client ne dit rien : le dialogue est considéré comme
 * fermé dès que le joueur tourne la caméra, appuie sur une touche de déplacement ou ouvre un inventaire, ce qui est impossible
 * tant qu'un dialogue est à l'écran. Les mouvements reçus juste après l'ouverture (paquets déjà en route, 400 ms plus deux
 * fois le ping) sont ignorés.
 * <p>
 * Les plugins SkyHand qui affichent des dialogues ont chacun cette classe. L'heure du dernier dialogue est gardée sur le
 * joueur, sous la même clé pour tous (skyhand:dialogue_aveugle, enregistrée avec ses données) : un plugin ne retire jamais
 * l'effet pendant le dialogue ouvert par un autre, et un effet resté après un arrêt brutal est retiré à la connexion suivante.
 * Un joueur déjà aveugle pour une autre raison (potion, mob) n'est pas touché.
 */
public final class DialogBlindness implements Listener {

    private static final NamespacedKey KEY = Objects.requireNonNull(NamespacedKey.fromString("skyhand:dialogue_aveugle"));
    private static final long GRACE_MS = 400;
    private static final long CHAIN_TICKS = 4;

    /**
     * Clé de clic des boutons qui ferment simplement le dialogue (Fermer, Annuler, C'est parti...), commune à tous les plugins.
     */
    public static final Key CLOSE_KEY = Key.key("skyhand", "dialogue_ferme");

    private final Plugin plugin;
    private final Set<UUID> tracked = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> clicks = new ConcurrentHashMap<>();
    private volatile boolean enabled;

    public DialogBlindness(Plugin plugin, boolean enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Active ou coupe l'aveuglement (rechargement de la configuration). Coupé, les joueurs aveuglés par ce plugin retrouvent la vue.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            sync(this::clearAll);
        }
    }

    /**
     * Action à donner à un bouton qui ferme simplement le dialogue, et au bouton du bas (exit_action, utilisé aussi par Échap) :
     * le serveur est prévenu et rend la vue tout de suite.
     */
    public static DialogAction closeAction() {
        return DialogAction.customClick(CLOSE_KEY, null);
    }

    /**
     * Affiche le dialogue et aveugle le joueur.
     */
    public void show(Player player, Dialog dialog) {
        blind(player);
        try {
            player.showDialog(dialog);
        } catch (RuntimeException e) {
            sync(() -> clear(player));
            throw e;
        }
    }

    /**
     * Ferme le dialogue affiché et rend la vue au joueur.
     */
    public void close(Player player) {
        player.closeDialog();
        sync(() -> clear(player));
    }

    /**
     * À appeler dans onDisable : rend la vue aux joueurs aveuglés par ce plugin.
     */
    public void shutdown() {
        HandlerList.unregisterAll(this);
        clearAll();
    }

    private void blind(Player player) {
        if (!enabled) {
            return;
        }
        sync(() -> {
            if (!player.isOnline()) {
                return;
            }
            PersistentDataContainer data = player.getPersistentDataContainer();
            boolean ours = data.has(KEY, PersistentDataType.LONG);
            boolean blind = player.hasPotionEffect(PotionEffectType.BLINDNESS);
            if (blind && !ours) {
                return;
            }
            data.set(KEY, PersistentDataType.LONG, System.currentTimeMillis());
            tracked.add(player.getUniqueId());
            if (!blind) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));
            }
        });
    }

    private void clear(Player player) {
        tracked.remove(player.getUniqueId());
        clicks.remove(player.getUniqueId());
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (data.has(KEY, PersistentDataType.LONG)) {
            data.remove(KEY);
            player.removePotionEffect(PotionEffectType.BLINDNESS);
        }
    }

    private void clearAll() {
        clicks.clear();
        for (UUID id : Set.copyOf(tracked)) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                clear(player);
            }
        }
        tracked.clear();
    }

    /**
     * Le joueur a bougé : le dialogue est fermé, sauf si l'ouverture est trop récente pour que le mouvement en tienne compte.
     */
    private void moved(Player player) {
        if (tracked.isEmpty() || !tracked.contains(player.getUniqueId())) {
            return;
        }
        Long shownAt = player.getPersistentDataContainer().get(KEY, PersistentDataType.LONG);
        if (shownAt == null) {
            tracked.remove(player.getUniqueId());
            return;
        }
        if (System.currentTimeMillis() - shownAt >= GRACE_MS + 2L * Math.max(0, player.getPing())) {
            clear(player);
        }
    }

    private void sync(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    private static Player clicker(PlayerCustomClickEvent event) {
        return event.getCommonConnection() instanceof PlayerGameConnection connection ? connection.getPlayer() : null;
    }

    /**
     * Heure du clic, notée avant que les plugins ne le traitent : un dialogue qu'ils ouvrent en réponse est donc plus récent.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClickStart(PlayerCustomClickEvent event) {
        Player player = clicker(event);
        if (player != null && tracked.contains(player.getUniqueId())) {
            clicks.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    /**
     * Un bouton à action serveur a été cliqué : le client a refermé le dialogue.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(PlayerCustomClickEvent event) {
        Player player = clicker(event);
        Long clickAt = player == null ? null : clicks.remove(player.getUniqueId());
        if (clickAt == null || !plugin.isEnabled()) {
            return;
        }
        if (CLOSE_KEY.equals(event.getIdentifier())) {
            sync(() -> clear(player));
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Long shownAt = player.getPersistentDataContainer().get(KEY, PersistentDataType.LONG);
            if (shownAt == null) {
                tracked.remove(player.getUniqueId());
            } else if (shownAt < clickAt) {
                clear(player);
            }
        }, CHAIN_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (tracked.isEmpty()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) {
            moved(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) {
        if (tracked.isEmpty()) {
            return;
        }
        Input input = event.getInput();
        if (input.isForward() || input.isBackward() || input.isLeft() || input.isRight() || input.isJump() || input.isSneak()
                || input.isSprint()) {
            moved(event.getPlayer());
        }
    }

    /**
     * Un inventaire ouvert remplace le dialogue à l'écran.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!tracked.isEmpty() && event.getPlayer() instanceof Player player && tracked.contains(player.getUniqueId())) {
            clear(player);
        }
    }

    /**
     * L'effet ne doit pas être enregistré avec les données du joueur.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    /**
     * Effet resté après un arrêt brutal du serveur.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        clear(event.getPlayer());
    }
}
