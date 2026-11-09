package org.Cry_Legende.sHInventoryPages.inventory;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.api.ISHInventoryPagesAPI;
import org.Cry_Legende.sHInventoryPages.api.event.PlayerItemsStoredEvent;
import org.Cry_Legende.sHInventoryPages.api.event.PlayerPageSwitchEvent;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.util.ItemCodec;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import static org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData.PAGE_SIZE;

/**
 * Cœur du plugin : chargement et sauvegarde des pages, changement de page, rangement des objets (inventaire
 * porté puis pages suivantes), sélecteur de pages et API.
 *
 * <p>Tout ce qui touche aux inventaires et au cache s'exécute sur le thread principal. La base de données tourne
 * sur l'exécuteur du plugin, avec une file par joueur : ses lectures et écritures passent dans l'ordre où elles ont
 * été demandées, et chaque écriture part d'une copie prise sur le thread principal.</p>
 *
 * <p>L'inventaire porté fait foi pour la page courante. Le sélecteur porte le numéro de la page que représente
 * l'inventaire porté ; il est enregistré par Minecraft avec cet inventaire, si bien qu'après un arrêt brutal, ou
 * après qu'un autre plugin a remis un ancien inventaire complet, on sait à quelle page correspond l'inventaire
 * retrouvé. Une page n'est jamais renumérotée.</p>
 */
public class InventoryManager implements ISHInventoryPagesAPI {

    private static final String PAGE_PERMISSION = "shinventorypages.pages.";
    private static final int MAX_LOAD_ATTEMPTS = 3;
    /** Délai de ramassage d'un objet que personne ne peut ramasser (ItemEntity#setNeverPickUp). */
    public static final int NEVER_PICKUP_DELAY = Short.MAX_VALUE;
    /** Objets différents réservés à la fois (chacun regroupe ses parts) ; au-delà, le plus ancien est oublié. */
    private static final int MAX_RESERVATION_ITEMS = 64;

    private final SHInventoryPages plugin;
    private final ConfigManager config;
    private final Executor dbExecutor;
    private final NamespacedKey switcherKey;
    private final NamespacedKey pageKey;

    private final Map<UUID, PlayerInventoryData> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> apiBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<?>> dbQueues = new ConcurrentHashMap<>();
    private final Map<UUID, Object> loadTokens = new ConcurrentHashMap<>();

    // Concurrentes : avec les ticks de mondes en parallèle (DivineMC), ramassages et objets posés au sol arrivent sur le
    // fil du monde du joueur. Les entrées d'un joueur ne sont touchées que par le fil de son monde.
    private final Set<UUID> deferredSaves = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> lastSwitchTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastStoredNotice = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastFullNotice = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Reservation>> reservations = new ConcurrentHashMap<>();
    /** Joueur dont l'inventaire porté est en train d'être remplacé par une autre page. */
    private UUID switching;

    private volatile int switcherSlot;
    private Material switcherMaterial;
    private String switcherName;
    private List<String> switcherLore;
    private boolean amountEqualsPage;
    private int fixedAmount;
    private boolean enchantGlow;
    private Material barrierMaterial;
    private String barrierName;
    private List<String> barrierLore;
    private Set<String> blacklistedWorlds;
    private boolean saveOnPageSwitch;
    private boolean savePlayerFileOnSwitch;
    private int switchCooldownTicks;
    private Sound switchSound;
    private Sound failSound;
    private float soundVolume;
    private float soundPitch;
    private Sound overflowSound;
    private float overflowVolume;
    private float overflowPitch;
    private int noticeCooldownTicks;
    private int reservationTicks;
    private long deferredSaveTicks;
    private long endOfSessionRetryMillis;
    private DeathMode deathMode;
    private volatile boolean overflowEnabled;
    private volatile double overflowRadius;
    private volatile boolean captureBrokenBlocks;
    private volatile double lootRadius;
    private volatile double explosionRadius;
    private volatile double feetRadius;
    private volatile Set<String> ignoredPlugins = Set.of();
    private volatile List<String> ignoredClasses = List.of();

    /** Arrêt en cours : les nouveaux essais d'écriture cessent et ce qui n'est pas écrit part sur disque. */
    private volatile boolean shuttingDown;
    private final java.util.concurrent.CountDownLatch shutdownSignal = new java.util.concurrent.CountDownLatch(1);
    /** Copies déjà gardées sur disque (jamais deux fichiers pour la même écriture). */
    private final Set<PlayerInventoryData> dumped = ConcurrentHashMap.newKeySet();
    /** Joueurs dont un enregistrement aligné (fichier joueur + base) est programmé. */
    private final Set<UUID> alignedPending = ConcurrentHashMap.newKeySet();
    /** Tick du dernier enregistrement aligné de chaque joueur. */
    private final Map<UUID, Integer> lastAlignedSave = new ConcurrentHashMap<>();
    private static final int ALIGNED_SAVE_SPACING_TICKS = 20;
    /** Fichiers de secours repris à la connexion, retirés quand une écriture validée couvre leurs pages. */
    private final Map<UUID, List<PendingDump>> pendingDumps = new ConcurrentHashMap<>();
    /** Écritures de dernier recours (fin de session, reprise de secours) pas encore terminées : attendues à l'arrêt. */
    private final Set<PendingSave> inFlightCritical = ConcurrentHashMap.newKeySet();
    /** Écritures validées dont le traitement (versions, fichiers de secours) n'a pas encore eu lieu : repris à l'arrêt. */
    private final Set<Commit> unprocessedCommits = ConcurrentHashMap.newKeySet();
    /** Fichier de secours écrit pour une copie (thread de la base vers thread principal). */
    private final Map<PlayerInventoryData, File> dumpFiles = new ConcurrentHashMap<>();

    /** Fichiers de secours écrits pendant une session, à inscrire avant toute validation postérieure. */
    private final java.util.concurrent.ConcurrentLinkedQueue<DumpRegistration> unregisteredDumps =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    private record DumpRegistration(UUID uuid, PendingDump dump) {
    }

    /** Écriture validée en attente de traitement sur le thread principal. */
    private record Commit(PlayerInventoryData data, Map<Integer, Integer> versions) {
    }

    /** Écriture soumise : les données vivantes, la copie écrite et son résultat. */
    private record PendingSave(PlayerInventoryData data, PlayerInventoryData snapshot, CompletableFuture<Void> future) {
    }

    /** Fichier de secours repris, et version de chaque page restaurée qu'une écriture validée doit couvrir. */
    private record PendingDump(File file, Map<Integer, Integer> versions) {
    }

    /** Objets d'une mort : que deviennent les pages. */
    public enum DeathMode {CURRENT, ALL, NONE}

    /** Place gardée dans l'inventaire porté pour des objets laissés au ramassage vanilla. */
    private static final class Reservation {
        final ItemStack item;
        /** Parts {quantité, tick} : chacune expire à son heure, jamais repoussée par les suivantes. */
        final Deque<int[]> parts = new ArrayDeque<>();

        Reservation(ItemStack item) {
            this.item = item;
        }

        int total() {
            int total = 0;
            for (int[] part : parts) total += part[0];
            return total;
        }
    }

    /** Où sont allés les objets d'un rangement. */
    private record Placement(int live, int stored, int firstPage) {
        static final Placement NONE = new Placement(0, 0, -1);

        int total() {
            return live + stored;
        }
    }

    public InventoryManager(SHInventoryPages plugin, Executor dbExecutor) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.dbExecutor = dbExecutor;
        this.switcherKey = new NamespacedKey(plugin, "is_switcher");
        this.pageKey = new NamespacedKey(plugin, "page");
        updateSettingsFromConfig();
    }

    public void updateSettingsFromConfig() {
        this.switcherSlot = config.getSwitcherSlot();
        this.switcherMaterial = config.getSwitcherItemMaterial();
        this.switcherName = config.getSwitcherItemName();
        this.switcherLore = config.getSwitcherItemLore();
        this.amountEqualsPage = config.isAmountEqualsPage();
        this.fixedAmount = config.getFixedAmount();
        this.enchantGlow = config.hasEnchantGlow();
        this.barrierMaterial = config.getBarrierItemMaterial();
        this.barrierName = config.getBarrierItemName();
        this.barrierLore = config.getBarrierItemLore();
        this.blacklistedWorlds = new HashSet<>(config.getBlacklistedWorlds());
        this.saveOnPageSwitch = config.saveOnPageSwitch();
        this.savePlayerFileOnSwitch = config.saveVanillaDataOnPageSwitch();
        this.switchCooldownTicks = config.getSwitchCooldownTicks();
        boolean sounds = config.soundsEnabled();
        this.switchSound = sounds ? config.getSwitchPageSound() : null;
        this.failSound = sounds ? config.getSwitchFailSound() : null;
        this.overflowSound = sounds ? config.getOverflowSound() : null;
        this.soundVolume = config.getSoundVolume();
        this.soundPitch = config.getSoundPitch();
        this.overflowVolume = config.getOverflowSoundVolume();
        this.overflowPitch = config.getOverflowSoundPitch();
        this.noticeCooldownTicks = config.getOverflowNoticeCooldownTicks();
        this.reservationTicks = config.getOverflowReservationTicks();
        this.deferredSaveTicks = config.getDeferredSaveTicks();
        this.endOfSessionRetryMillis = config.getEndOfSessionRetrySeconds() * 1000L;
        this.deathMode = config.getDeathDropMode();
        this.overflowEnabled = config.isOverflowEnabled();
        this.overflowRadius = config.getOverflowCaptureRadius();
        this.captureBrokenBlocks = config.isCaptureBrokenBlockDrops();
        this.lootRadius = config.getOverflowLootRadius();
        this.explosionRadius = config.getOverflowExplosionRadius();
        this.feetRadius = config.getOverflowFeetRadius();
        Set<String> ignored = new HashSet<>();
        List<String> classes = new ArrayList<>();
        for (String entry : config.getOverflowIgnoredPlugins()) {
            // Avec un point : classe ou paquet (une seule mécanique d'un plugin), sinon nom de plugin
            if (entry.indexOf('.') >= 0) classes.add(entry);
            else ignored.add(entry.toLowerCase(java.util.Locale.ROOT));
        }
        this.ignoredPlugins = Set.copyOf(ignored);
        this.ignoredClasses = List.copyOf(classes);
    }

    public double getFeetRadius() {
        return feetRadius;
    }

    /** Plugin dont les objets posés au sol ne sont jamais des dons (configuration overflow.ignored_plugins). */
    public boolean isIgnoredPlugin(String pluginName) {
        return ignoredPlugins.contains(pluginName.toLowerCase(java.util.Locale.ROOT));
    }

    /** Classe (ou préfixe de paquet) d'ignored_plugins : un appel de ce code dans la pile n'est jamais un don. */
    public boolean isIgnoredClass(String className) {
        if (ignoredClasses.isEmpty()) return false;
        for (String prefix : ignoredClasses) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    // =========================================================================
    //  CHARGEMENT
    // =========================================================================

    public void loadPlayerData(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerInventory inv = player.getInventory();
        // Avant de poser la barrière : le sélecteur enregistré avec l'inventaire dit quelle page il représente
        Integer livePage = readPageStamp(inv.getItem(switcherSlot));
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i == switcherSlot || !isSwitcherItem(inv.getItem(i))) continue;
            // Emplacement du sélecteur changé pendant l'absence du joueur
            if (livePage == null) livePage = readPageStamp(inv.getItem(i));
            inv.setItem(i, null);
        }
        placeInSwitcherSlot(player, createBarrierItem(livePage));
        Object token = new Object();
        loadTokens.put(uuid, token);
        attemptLoad(player, 1, token, livePage);
    }

    /**
     * Vrai si ce chargement est toujours celui de la session en cours. Player.isOnline() ne suffit pas : il vaut
     * aussi true pour l'objet d'une session terminée dès que le joueur s'est reconnecté.
     */
    private boolean isCurrentLoad(Player player, Object token) {
        UUID uuid = player.getUniqueId();
        return loadTokens.get(uuid) == token && Bukkit.getPlayer(uuid) == player;
    }

    public boolean isLoading(Player player) {
        return loadTokens.containsKey(player.getUniqueId());
    }

    private void attemptLoad(Player player, int attempt, Object token, Integer livePage) {
        if (!isCurrentLoad(player, token)) return;
        UUID uuid = player.getUniqueId();
        int maxPossible = config.getMaxPossiblePages();
        queueDb(uuid, () -> plugin.getDatabaseManager().load(uuid, maxPossible))
            .whenCompleteAsync((loaded, error) -> {
                if (!isCurrentLoad(player, token)) {
                    // Chargement d'une session abandonnée : ce qu'il a lu n'a pas à rester en mémoire
                    forgetIfInactive(uuid);
                    return;
                }
                if (error == null) {
                    loadTokens.remove(uuid, token);
                    try {
                        applyLoadedData(player, loaded, livePage);
                    } catch (RuntimeException e) {
                        // État incertain : rien n'est gardé en cache (donc rien n'est sauvegardé) et le joueur
                        // est déconnecté ; la base et l'inventaire enregistré par Minecraft restent intacts
                        cache.remove(uuid);
                        plugin.getLogger().log(Level.SEVERE, "Error while applying loaded data for " + player.getName(), e);
                        player.kick(LegacyComponentSerializer.legacySection().deserialize(config.getMessage("load_failed_kick")));
                    }
                    return;
                }
                plugin.getLogger().log(Level.WARNING, "Attempt " + attempt + "/" + MAX_LOAD_ATTEMPTS
                    + " failed to load data for " + player.getName(), error);
                if (attempt < MAX_LOAD_ATTEMPTS && plugin.isEnabled()) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> attemptLoad(player, attempt + 1, token, livePage), 20L * attempt);
                } else {
                    loadTokens.remove(uuid, token);
                    plugin.getLogger().severe("CRITICAL: Could not load inventory pages for " + player.getName()
                        + " after " + attempt + " attempts. Kicking to protect the stored pages.");
                    player.kick(LegacyComponentSerializer.legacySection().deserialize(config.getMessage("load_failed_kick")));
                }
            }, Bukkit.getScheduler().getMainThreadExecutor(plugin));
    }

    /** Oublie l'état connu de la base pour un joueur sans session, dans l'ordre de sa file. */
    private void forgetIfInactive(UUID uuid) {
        queueDb(uuid, () -> {
            if (!cache.containsKey(uuid) && !loadTokens.containsKey(uuid)) plugin.getDatabaseManager().forget(uuid);
            return null;
        });
    }

    private void applyLoadedData(Player player, PlayerInventoryData loaded, Integer livePage) {
        UUID uuid = player.getUniqueId();
        int maxPages = getMaxPagesForPlayer(player);
        PlayerInventoryData data = loaded != null ? loaded : new PlayerInventoryData(uuid, maxPages);
        // La page que représente l'inventaire porté : celle du sélecteur, sinon celle de la base. Sa ligne en base,
        // même illisible, n'en est qu'une copie : l'inventaire vivant la remplace.
        int worn = data.getCurrentPage();
        if (loaded != null && livePage != null && livePage >= 1 && livePage <= data.getMaxPages()) {
            if (livePage != worn) {
                plugin.getLogger().warning("Page courante de " + player.getName() + " reprise du sélecteur ("
                    + livePage + ") et non de la base (" + worn + ") : arrêt brutal après un changement de page");
            }
            worn = livePage;
        }
        data.unlockPage(worn);
        data.setLoadedCurrentPage(worn);
        if (!data.lockedPagesView().isEmpty()) {
            plugin.getLogger().severe("Pages illisibles de " + player.getName() + " (verrouillées, intactes en base) : "
                + data.lockedPagesView());
        }
        data.setMaxPages(maxPages);
        int strayInterface = data.removeStoredItems(this::isSwitcherItem);
        if (strayInterface > 0) {
            plugin.getLogger().info(strayInterface + " sélecteur(s) retiré(s) des pages enregistrées de " + player.getName());
        }

        // Pages plus récentes que la base, gardées sur disque quand son dernier départ n'a pas pu être écrit
        List<File> unsaved = applyUnsavedFiles(player, data);

        cache.put(uuid, data);
        // L'inventaire porté fait foi pour la page courante, même devenue cachée
        captureLive(player, data);
        List<ItemStack> leftovers = data.splitOversizedStacks(switcherSlot);
        leftovers.addAll(data.drainStrayItems());
        boolean leftHidden = data.isCurrentPageHidden() && pagesOpen(player);
        if (leftHidden) leftovers.addAll(leaveHiddenPage(player, data));
        updateSwitcherItem(player);
        storeOrDrop(player, leftovers, "objets rendus au chargement");
        // Changement forcé de page : fichier joueur et base ensemble
        if (leftHidden) alignedSave(player, data);
        // Pages reprises d'un fichier de secours : écrites tout de suite (réessayée comme une fin de session) ; chaque
        // fichier n'est retiré qu'une fois ses pages couvertes par une écriture validée, quelle qu'elle soit
        if (!unsaved.isEmpty()) submitSave(data, false, true);
    }

    /**
     * Fichier joueur de Minecraft et pages en base écrits dans le même tick après un changement de page. Au plus un
     * enregistrement du fichier par seconde et par joueur : une rafale de clics est regroupée en fin de seconde.
     */
    private void alignedSave(Player player, PlayerInventoryData data) {
        UUID uuid = player.getUniqueId();
        int now = Bukkit.getCurrentTick();
        Integer last = lastAlignedSave.get(uuid);
        if (last != null && now - last < ALIGNED_SAVE_SPACING_TICKS) {
            // Minuteur propre, jamais rattaché à une sauvegarde regroupée plus lointaine : écart borné à une seconde
            if (alignedPending.add(uuid) && plugin.isEnabled()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!alignedPending.remove(uuid)) return;
                    PlayerInventoryData current = cache.get(uuid);
                    Player online = Bukkit.getPlayer(uuid);
                    if (current != null && online != null) performAlignedSave(online, current);
                }, ALIGNED_SAVE_SPACING_TICKS - (now - last));
            }
            return;
        }
        performAlignedSave(player, data);
    }

    private void performAlignedSave(Player player, PlayerInventoryData data) {
        lastAlignedSave.put(player.getUniqueId(), Bukkit.getCurrentTick());
        captureLive(player, data);
        savePlayerFile(player);
        data.markAligned();
        saveSnapshot(data, false);
    }

    /** Fermeture d'un inventaire : la page courante est recapturée (copie toujours fraîche en mémoire). */
    public void captureIfLoaded(Player player) {
        PlayerInventoryData data = cache.get(player.getUniqueId());
        if (data != null && switching == null && !player.isDead()) captureLive(player, data);
    }

    /**
     * La page portée n'est plus accessible (permission retirée) : elle reste enregistrée sous son numéro, cachée, et
     * le joueur porte la dernière page accessible. L'inventaire porté doit avoir été capturé juste avant.
     */
    private List<ItemStack> leaveHiddenPage(Player player, PlayerInventoryData data) {
        int hidden = data.getCurrentPage();
        int target = -1;
        for (int n = data.getMaxPages(); n >= 1; n--) {
            if (!data.isLocked(n)) {
                target = n;
                break;
            }
        }
        if (target < 0) return new ArrayList<>();
        Bukkit.getPluginManager().callEvent(new PlayerPageSwitchEvent(player, hidden, target, true));
        switching = player.getUniqueId();
        try {
            data.setCurrentPage(target);
            List<ItemStack> displaced = applyCurrentPage(player, data);
            updateSwitcherItem(player);
            plugin.getLogger().info("Page " + hidden + " de " + player.getName() + " cachée (pages autorisées : "
                + data.getMaxPages() + ") ; page portée : " + target);
            return displaced;
        } finally {
            switching = null;
        }
    }

    // =========================================================================
    //  DÉCONNEXION / SAUVEGARDE
    // =========================================================================

    public void unloadPlayerData(Player player) {
        UUID uuid = player.getUniqueId();
        Player current = Bukkit.getPlayer(uuid);
        if (current != null && current != player) {
            // Objet d'une session terminée : le cache et les blocages appartiennent à la nouvelle session
            return;
        }
        loadTokens.remove(uuid);
        PlayerInventoryData data = cache.get(uuid);
        // Capture avant de quitter le cache : un excédent corrigé peut encore être rangé dans les autres pages
        if (data != null) captureLive(player, data);
        cache.remove(uuid);
        apiBlocks.remove(uuid);
        deferredSaves.remove(uuid);
        lastSwitchTick.remove(uuid);
        lastAlignedSave.remove(uuid);
        alignedPending.remove(uuid);
        lastStoredNotice.remove(uuid);
        lastFullNotice.remove(uuid);
        reservations.remove(uuid);
        if (data == null) {
            // Chargement échoué ou pas encore fini : surtout ne rien écrire par-dessus la base
            forgetIfInactive(uuid);
            return;
        }
        saveSnapshot(data, true);
    }

    /**
     * Copie l'inventaire porté (emplacements 0 à 35, sauf le sélecteur) dans la page courante. Si le sélecteur
     * porté indique une autre page accessible, l'inventaire a été remplacé par un plugin (restauration, parcours) :
     * c'est cette page qu'il représente désormais. Un sélecteur absent ou faux est remis. Un sélecteur trouvé
     * ailleurs est supprimé ; une pile au-delà de sa taille est corrigée dans l'inventaire lui-même et l'excédent est
     * rangé. Réparations silencieuses.
     */
    public void captureLive(Player player, PlayerInventoryData data) {
        if (data == null || player == null || !player.isOnline()) return;
        PlayerInventory inv = player.getInventory();
        Integer stamp = readPageStamp(inv.getItem(switcherSlot));
        if (stamp != null && stamp != data.getCurrentPage() && switching == null
            && stamp >= 1 && stamp <= data.getMaxPages() && !data.isLocked(stamp)) {
            plugin.getLogger().warning("Inventaire de " + player.getName() + " remplacé par un autre plugin : il représente la page "
                + stamp + " (page portée enregistrée : " + data.getCurrentPage() + ")");
            data.setCurrentPage(stamp);
            stamp = data.getCurrentPage();
        }
        boolean needsSwitcher = stamp == null || stamp != data.getCurrentPage();
        List<ItemStack> excess = null;
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i == switcherSlot) continue;
            ItemStack item = inv.getItem(i);
            if (item == null || item.isEmpty()) continue;
            if (isSwitcherItem(item)) {
                inv.setItem(i, null);
                continue;
            }
            int limit = SlotFiller.stackLimit(item);
            if (item.getAmount() > limit) {
                if (excess == null) excess = new ArrayList<>();
                excess.addAll(PlayerInventoryData.excessParts(item, limit));
                ItemStack trimmed = item.clone();
                trimmed.setAmount(limit);
                inv.setItem(i, trimmed);
            }
        }
        data.setPage(data.getCurrentPage(), readLive(inv));
        if (excess != null) {
            storeOrDrop(player, excess, "pile au-delà de sa taille corrigée dans l'inventaire");
            data.setPage(data.getCurrentPage(), readLive(inv));
        }
        if (needsSwitcher && switching == null && !player.isDead()) updateSwitcherItem(player);
    }

    /** Copie des emplacements 0 à 35 de l'inventaire porté ; l'emplacement du sélecteur est toujours vide. */
    private ItemStack[] readLive(PlayerInventory inv) {
        ItemStack[] contents = new ItemStack[PAGE_SIZE];
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i == switcherSlot) continue;
            ItemStack item = inv.getItem(i);
            if (item != null && !item.isEmpty()) contents[i] = item.clone();
        }
        return contents;
    }

    /**
     * Écriture asynchrone des pages modifiées depuis la dernière écriture validée, copiées maintenant. Après
     * validation, leurs versions sont marquées enregistrées ; en cas d'échec elles restent à écrire. L'écriture de
     * fin de session (et celle de l'arrêt) est réessayée, puis copiée sur disque si la base reste injoignable :
     * rien de ce qui n'est qu'en mémoire ne disparaît sans trace (fichier repris automatiquement à la connexion).
     */
    public CompletableFuture<Void> saveSnapshot(PlayerInventoryData data, boolean endOfSession) {
        return submitSave(data, endOfSession, false).future();
    }

    /**
     * @param persistent écriture de dernier recours hors fin de session (pages reprises d'un fichier de secours) :
     *                   réessayée et copiée sur disque comme une fin de session
     */
    private PendingSave submitSave(PlayerInventoryData data, boolean endOfSession, boolean persistent) {
        UUID uuid = data.getUuid();
        // Une pile de plus de 99 ferait échouer l'écriture de toutes les pages du joueur
        List<ItemStack> lost = data.splitOversizedStacks(switcherSlot);
        if (!lost.isEmpty()) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && cache.get(uuid) == data) {
                storeOrDrop(online, lost, "excédent de pile avant sauvegarde");
            } else {
                for (ItemStack item : lost) {
                    plugin.getLogger().severe("Plus de place pour un excédent de pile de " + uuid + " : "
                        + item.getAmount() + "x " + item.getType() + " (non sauvegardé)");
                }
            }
        }
        PlayerInventoryData snapshot = data.snapshot();
        Map<Integer, Integer> versions = snapshot.snapshotVersions();
        boolean critical = endOfSession || persistent;
        long retryMillis = shuttingDown ? 2_000L : critical ? endOfSessionRetryMillis : 0L;
        CompletableFuture<Void> raw = queueDb(uuid, () -> {
            try {
                writeWithRetry(snapshot, critical, retryMillis);
            } finally {
                if (endOfSession) plugin.getDatabaseManager().forget(uuid);
            }
            return (Void) null;
        });
        CompletableFuture<Void> future = raw.whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save inventory pages of " + uuid, error);
                File dump = dumpFiles.remove(snapshot);
                if (dump != null && !endOfSession) {
                    // Copie écrite pendant la session (reprise de secours en échec) : retirée, comme celles reprises à
                    // la connexion, dès qu'une écriture validée couvre ses pages. Conservée jusqu'à son inscription :
                    // un arrêt juste après l'inscrit quand même
                    unregisteredDumps.add(new DumpRegistration(uuid, new PendingDump(dump, versions)));
                    runOnMainThread(this::registerDumps);
                }
            } else if (!versions.isEmpty()) {
                // Conservée jusqu'à son traitement : un arrêt juste après la validation la traite quand même
                Commit commit = new Commit(data, versions);
                unprocessedCommits.add(commit);
                runOnMainThread(() -> processCommit(commit));
            }
        });
        // Suivi sur le futur final : quand l'arrêt le voit terminé, la validation (ou le fichier de secours) est déjà
        // inscrite, jamais encore en cours sur le thread de la base
        PendingSave tracked = new PendingSave(data, snapshot, future);
        if (critical) {
            inFlightCritical.add(tracked);
            future.whenComplete((ignored, error) -> inFlightCritical.remove(tracked));
        }
        return tracked;
    }

    /** Thread principal (ou arrêt) : inscrit les fichiers de secours écrits pendant une session, dans leur ordre. */
    private void registerDumps() {
        DumpRegistration registration;
        while ((registration = unregisteredDumps.poll()) != null) {
            pendingDumps.computeIfAbsent(registration.uuid(), k -> new ArrayList<>()).add(registration.dump());
        }
    }

    private void processCommit(Commit commit) {
        if (unprocessedCommits.remove(commit)) onSaveCommitted(commit.data(), commit.versions());
    }

    /**
     * Thread principal, après une écriture validée (fin de session comprise) : versions marquées enregistrées, et
     * fichiers de secours retirés dès qu'une écriture validée couvre toutes les pages qu'ils ont restaurées.
     */
    private void onSaveCommitted(PlayerInventoryData data, Map<Integer, Integer> versions) {
        data.markSaved(versions);
        List<PendingDump> dumps = pendingDumps.get(data.getUuid());
        if (dumps == null) return;
        dumps.removeIf(dump -> {
            for (Map.Entry<Integer, Integer> page : dump.versions().entrySet()) {
                if (!data.isSavedAtLeast(page.getKey(), page.getValue())) return false;
            }
            retire(dump.file(), ".applied");
            return true;
        });
        if (dumps.isEmpty()) pendingDumps.remove(data.getUuid());
    }

    /** Thread de la base. Les pauses s'interrompent dès l'arrêt du plugin : la copie part alors sur disque. */
    private void writeWithRetry(PlayerInventoryData snapshot, boolean critical, long retryMillis) throws Exception {
        long deadline = System.currentTimeMillis() + retryMillis;
        long pause = 1_000L;
        while (true) {
            try {
                plugin.getDatabaseManager().save(snapshot);
                return;
            } catch (Exception e) {
                boolean outOfTime = System.currentTimeMillis() + pause > deadline;
                if (outOfTime || shuttingDown) {
                    // Seule la dernière écriture d'un joueur est copiée sur disque (fin de session, ou copie finale de
                    // l'arrêt écrite par shutdown) : une écriture plus ancienne, dépassée, ne doit jamais revenir
                    if (critical) writeUnsavedFile(snapshot, e);
                    throw e;
                }
                plugin.getLogger().warning("Écriture des pages de " + snapshot.getUuid() + " échouée ("
                    + e.getMessage() + "), nouvel essai dans " + pause / 1000 + " s");
                // Réveil immédiat à l'arrêt du plugin : la boucle copie alors sur disque au lieu d'attendre
                shutdownSignal.await(pause, TimeUnit.MILLISECONDS);
                pause = Math.min(pause * 2, 30_000L);
            }
        }
    }

    private static File unsavedFolder(SHInventoryPages plugin) {
        return new File(plugin.getDataFolder(), "unsaved");
    }

    /**
     * Dernier recours : les pages qui n'ont pas pu être écrites sont gardées sur disque. Elles sont reprises
     * automatiquement à la prochaine connexion du joueur ({@link #applyUnsavedFiles}), ou par /ship recover.
     */
    private void writeUnsavedFile(PlayerInventoryData snapshot, Exception cause) {
        if (snapshot.pagesView().isEmpty() || !dumped.add(snapshot)) return;
        try {
            File folder = unsavedFolder(plugin);
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("cannot create " + folder);
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
            File file = new File(folder, snapshot.getUuid() + "-" + stamp + ".yml");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("uuid", snapshot.getUuid().toString());
            yaml.set("current_page", snapshot.getCurrentPage());
            for (Map.Entry<Integer, ItemStack[]> page : snapshot.pagesView().entrySet()) {
                yaml.set("pages." + page.getKey(), ItemCodec.encodePage(page.getValue(), plugin.getLogger()));
            }
            // Marque de fin et écriture atomique : un fichier coupé par un arrêt brutal n'est jamais pris pour vide
            yaml.set("complete", true);
            File temp = new File(folder, file.getName() + ".tmp");
            yaml.save(temp);
            java.nio.file.Files.move(temp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dumpFiles.put(snapshot, file);
            plugin.getLogger().log(Level.SEVERE, "Base injoignable : pages de " + snapshot.getUuid()
                + " gardées dans " + file.getPath() + " (reprises à la prochaine connexion)", cause);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "PERTE : pages de " + snapshot.getUuid()
                + " ni écrites en base ni sur disque", e);
        }
    }

    /**
     * Connexion : un fichier de secours de ce joueur contient des pages plus récentes que la base (écriture échouée
     * à son dernier départ). Lecture stricte et tout ou rien : un fichier incomplet ou dont une page est illisible
     * est mis de côté (« .failed ») sans rien appliquer. Sinon ses pages remplacent celles de la base ; la page
     * courante du fichier est ignorée (l'inventaire porté enregistré par Minecraft fait foi). Le fichier n'est renommé
     * « .applied » qu'une fois ses pages couvertes par une écriture validée (onSaveCommitted) : un arrêt avant ne perd rien.
     *
     * @return les fichiers appliqués
     */
    private List<File> applyUnsavedFiles(Player player, PlayerInventoryData data) {
        File[] files = unsavedFolder(plugin).listFiles((dir, name) ->
            name.startsWith(player.getUniqueId() + "-") && name.endsWith(".yml"));
        List<File> applied = new ArrayList<>();
        pendingDumps.remove(player.getUniqueId());
        if (files == null || files.length == 0) return applied;
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        for (File file : files) {
            Map<Integer, ItemStack[]> decoded;
            try {
                decoded = readUnsavedFile(file, true);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Fichier de secours illisible, mis de côté sans rien appliquer : "
                    + file.getPath(), e);
                retire(file, ".failed");
                continue;
            }
            decoded.forEach(data::restoreUnsavedPage);
            Map<Integer, Integer> restoredVersions = new HashMap<>();
            for (Integer page : decoded.keySet()) restoredVersions.put(page, data.versionOf(page));
            pendingDumps.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>())
                .add(new PendingDump(file, restoredVersions));
            applied.add(file);
            plugin.getLogger().warning(decoded.size() + " page(s) de " + player.getName()
                + " reprises du fichier de secours " + file.getName());
        }
        return applied;
    }

    /** Pages d'un fichier de secours complet, sans sa page courante si {@code skipWornPage}. */
    private static Map<Integer, ItemStack[]> readUnsavedFile(File file, boolean skipWornPage) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        if (!yaml.getBoolean("complete", false)) throw new java.io.IOException("fichier incomplet");
        int filePage = yaml.getInt("current_page", -1);
        Map<Integer, ItemStack[]> pages = new java.util.TreeMap<>();
        var section = yaml.getConfigurationSection("pages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                int page = Integer.parseInt(key);
                if (skipWornPage && page == filePage) continue;
                pages.put(page, ItemCodec.decodePage(section.getString(key), PAGE_SIZE));
            }
        }
        return pages;
    }

    /** Renomme un fichier de secours traité ; à défaut le supprime (jamais repris deux fois). */
    private void retire(File file, String suffix) {
        if (file.renameTo(new File(file.getPath() + suffix))) return;
        if (!file.delete()) {
            plugin.getLogger().severe("Fichier de secours ni renommé ni supprimé, à retirer à la main : " + file.getPath());
        }
    }

    /**
     * /ship recover : réécrit en base les pages d'un fichier de secours, pour un joueur hors ligne qui ne s'est pas
     * reconnecté depuis (sinon la base est plus récente : refus, sauf « force »). La page courante du fichier n'est
     * pas écrite : elle vit dans l'inventaire enregistré par Minecraft ; la page courante en base devient celle du
     * fichier, celle que représente cet inventaire.
     */
    public void recoverUnsaved(CommandSender sender, String fileName, boolean force) {
        File file = new File(unsavedFolder(plugin), fileName);
        if (!file.isFile() || fileName.contains("/") || fileName.contains("\\")) {
            MessageUtil.sendMessage(sender, config.getMessage("recover_not_found", "%file%", fileName));
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        UUID uuid;
        try {
            uuid = UUID.fromString(String.valueOf(yaml.getString("uuid")));
        } catch (IllegalArgumentException e) {
            MessageUtil.sendMessage(sender, config.getMessage("recover_not_found", "%file%", fileName));
            return;
        }
        if (Bukkit.getPlayer(uuid) != null || cache.containsKey(uuid) || loadTokens.containsKey(uuid)) {
            MessageUtil.sendMessage(sender, config.getMessage("recover_player_online"));
            return;
        }
        if (!force && Bukkit.getOfflinePlayer(uuid).getLastLogin() > file.lastModified()) {
            MessageUtil.sendMessage(sender, config.getMessage("recover_stale", "%file%", fileName));
            return;
        }
        int maxPossible = config.getMaxPossiblePages();
        queueDb(uuid, () -> {
            // État de la base relu d'abord : seules les pages du fichier sont écrites, sauf sa page courante, et la page
            // courante en base devient celle du fichier (celle que représente l'inventaire enregistré par Minecraft)
            PlayerInventoryData stored = plugin.getDatabaseManager().load(uuid, maxPossible);
            PlayerInventoryData data = stored != null ? stored : new PlayerInventoryData(uuid, maxPossible);
            // La page courante du fichier est celle que l'inventaire porté (fichier joueur) représente : elle seule
            // est sautée ; la page courante enregistrée en base peut être périmée et ne protège rien
            data.setLoadedCurrentPage(yaml.getInt("current_page", data.getCurrentPage()));
            readUnsavedFile(file, true).forEach(data::restoreUnsavedPage);
            plugin.getDatabaseManager().save(data.snapshot());
            plugin.getDatabaseManager().forget(uuid);
            return null;
        }).whenComplete((ignored, error) -> runOnMainThread(() -> {
            if (error == null) {
                retire(file, ".recovered");
                MessageUtil.sendMessage(sender, config.getMessage("recover_done", "%file%", fileName));
            } else {
                plugin.getLogger().log(Level.SEVERE, "Réimport de " + fileName + " échoué", error);
                MessageUtil.sendMessage(sender, config.getMessage("recover_failed", "%file%", fileName));
            }
        }));
    }

    private void runOnMainThread(Runnable task) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (RuntimeException ignored) {
            // Plugin désactivé entre-temps : la sauvegarde finale a tout écrit
        }
    }

    /** Enregistrement Minecraft de l'inventaire porté et du sélecteur, aligné sur l'écriture des pages. */
    private void savePlayerFile(Player player) {
        if (!savePlayerFileOnSwitch) return;
        try {
            player.saveData();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save player file of " + player.getName(), e);
        }
    }

    /**
     * Sauvegarde regroupée : au plus une écriture par joueur sur le délai configuré (un joueur à l'inventaire plein
     * qui mine déclencherait sinon une écriture par objet rangé). L'inventaire porté est capturé juste avant.
     */
    public void scheduleDeferredSave(UUID uuid) {
        // Arrêt en cours : la sauvegarde finale capture et écrit déjà tout
        if (!plugin.isEnabled() || !deferredSaves.add(uuid)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!deferredSaves.remove(uuid)) return;
            PlayerInventoryData data = cache.get(uuid);
            if (data == null) return;
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) captureLive(player, data);
            saveSnapshot(data, false);
        }, Math.max(1L, deferredSaveTicks));
    }

    /** Capture et sauvegarde de tous les joueurs chargés, étalées sur une seconde. */
    public void autoSaveAll() {
        int index = 0;
        for (UUID uuid : new ArrayList<>(cache.keySet())) {
            long delay = index++ % 20;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                PlayerInventoryData data = cache.get(uuid);
                if (data == null) return;
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) captureLive(player, data);
                saveSnapshot(data, false);
            }, delay);
        }
    }

    /** Capture et sauvegarde immédiates de tous les joueurs chargés. */
    public CompletableFuture<Void> saveAllNow() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (PendingSave pending : submitAll()) futures.add(pending.future());
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private List<PendingSave> submitAll() {
        List<PendingSave> pending = new ArrayList<>();
        for (Map.Entry<UUID, PlayerInventoryData> entry : cache.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) captureLive(player, entry.getValue());
            pending.add(submitSave(entry.getValue(), false, false));
        }
        return pending;
    }

    /**
     * Arrêt du serveur ou du plugin : tout est capturé et soumis ; les nouveaux essais en attente cessent. Ce qui
     * n'est pas validé en base dans le délai est copié sur disque depuis ce thread, avant que le serveur ne s'arrête.
     */
    public void shutdown(long timeoutSeconds) {
        shuttingDown = true;
        shutdownSignal.countDown();
        deferredSaves.clear();
        alignedPending.clear();
        List<PendingSave> pending = submitAll();
        // Fins de session encore en file (joueurs partis juste avant l'arrêt) : attendues et copiées de même
        pending.addAll(inFlightCritical);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int dumpedCount = 0;
        for (PendingSave save : pending) {
            long left = Math.max(0, deadline - System.currentTimeMillis());
            try {
                save.future().get(left, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                writeUnsavedFile(save.snapshot(), e);
                dumpedCount++;
            }
        }
        // Le plugin est arrêté (plus de tâche planifiée) : toutes les validations restantes sont traitées ici, celles
        // de l'arrêt comme celles arrivées juste avant, fichiers de secours couverts compris
        registerDumps();
        for (Commit commit : new ArrayList<>(unprocessedCommits)) processCommit(commit);
        if (dumpedCount == 0) {
            plugin.getLogger().info("All inventory pages saved.");
        } else {
            plugin.getLogger().severe(dumpedCount + " joueur(s) : pages non écrites en base à l'arrêt, gardées dans unsaved/");
        }
    }

    /**
     * Ajoute une opération en base à la file du joueur : elle démarre quand la précédente est terminée (même en
     * échec), sur l'exécuteur de la base.
     */
    private <T> CompletableFuture<T> queueDb(UUID uuid, Callable<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<?> tail = dbQueues.compute(uuid, (key, previous) -> {
            CompletableFuture<?> start = previous != null ? previous : CompletableFuture.completedFuture(null);
            return start.handle((ignored, error) -> null)
                .thenRunAsync(() -> {
                    try {
                        result.complete(operation.call());
                    } catch (Throwable t) {
                        result.completeExceptionally(t);
                    }
                }, runnable -> {
                    try {
                        dbExecutor.execute(runnable);
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        // Exécuteur déjà arrêté : l'opération passe sur ce thread plutôt que de ne jamais s'exécuter
                        runnable.run();
                    }
                })
                .whenComplete((ignored, error) -> {
                    if (error != null) result.completeExceptionally(error);
                });
        });
        tail.whenComplete((ignored, error) -> dbQueues.remove(uuid, tail));
        return result;
    }

    // =========================================================================
    //  CHANGEMENT DE PAGE
    // =========================================================================

    public void switchPage(Player player, boolean next) {
        UUID uuid = player.getUniqueId();
        PlayerInventoryData data = cache.get(uuid);
        if (data == null) {
            MessageUtil.sendMessage(player, config.getMessage("data_loading"));
            updateSwitcherItem(player);
            return;
        }
        if (switching != null) return;
        if (!pagesOpen(player)) {
            playSound(player, failSound, soundVolume, soundPitch);
            MessageUtil.sendMessage(player, config.getMessage("cant_switch_here"));
            updateSwitcherItem(player);
            return;
        }
        int now = Bukkit.getCurrentTick();
        Integer last = lastSwitchTick.get(uuid);
        if (last != null && now - last < switchCooldownTicks) return;

        // Capture d'abord : une restauration d'inventaire par un autre plugin change la page portée
        int before = data.getCurrentPage();
        captureLive(player, data);
        if (data.getCurrentPage() != before) {
            updateSwitcherItem(player);
            return;
        }
        int current = data.getCurrentPage();
        int target = data.nextAccessiblePage(current, next);
        if (target < 0 || target == current) {
            updateSwitcherItem(player);
            return;
        }
        lastSwitchTick.put(uuid, now);

        PlayerPageSwitchEvent event = new PlayerPageSwitchEvent(player, current, target, false);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || cache.get(uuid) != data || data.getCurrentPage() != current || !player.isOnline()) {
            updateSwitcherItem(player);
            return;
        }

        List<ItemStack> leftovers;
        switching = uuid;
        try {
            // Un écouteur a pu toucher l'inventaire : dernière capture sous verrou, sans effet de bord
            data.setPage(current, readLive(player.getInventory()));
            leftovers = data.splitOversizedStacks(switcherSlot);
            data.setCurrentPage(target);
            leftovers.addAll(applyCurrentPage(player, data));
            updateSwitcherItem(player);
        } finally {
            switching = null;
        }
        storeOrDrop(player, leftovers, "objets déplacés au changement de page");
        // Base et fichier du joueur écrits ensemble, tout de suite (au plus une fois par seconde, le reste regroupé) :
        // un arrêt brutal, ou un enregistrement du fichier joueur par le serveur juste après, ne peut plus opposer
        // une page déjà écrite en base à un inventaire porté d'un autre moment (objet présent deux fois)
        if (!saveOnPageSwitch) {
            scheduleDeferredSave(uuid);
        } else if (data.changedSinceAligned(current) || alignedPending.contains(uuid)
            || !data.isSavedAtLeast(current, data.versionOf(current))) {
            // Rien n'a pu passer d'une page à l'autre si la page quittée n'a pas changé depuis le dernier alignement et
            // que celui-ci est validé en base : simple feuilletage, rien à écrire
            alignedSave(player, data);
        }
        playSound(player, switchSound, soundVolume, soundPitch);
    }

    /**
     * Écrit la page courante des données dans l'inventaire porté. Un objet enregistré sur l'index du sélecteur
     * (emplacement changé par un /ship reload) ne peut pas y être posé : il est rendu pour être rangé ailleurs.
     */
    private List<ItemStack> applyCurrentPage(Player player, PlayerInventoryData data) {
        int number = data.getCurrentPage();
        ItemStack[] contents = data.page(number);
        List<ItemStack> displaced = new ArrayList<>();
        ItemStack reserved = contents[switcherSlot];
        if (reserved != null) {
            data.clearSlot(number, switcherSlot);
            if (!reserved.isEmpty() && !isSwitcherItem(reserved)) displaced.add(reserved);
        }
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i == switcherSlot) continue;
            ItemStack item = contents[i];
            inv.setItem(i, item != null && !item.isEmpty() ? item.clone() : null);
        }
        return displaced;
    }

    /**
     * Les pages enregistrées sont figées quand le changement de page l'est (monde interdit, parcours...) : un kit
     * d'arène ou de parcours ne doit jamais pouvoir s'y glisser, ni en sortir.
     */
    private boolean pagesOpen(Player player) {
        return !isBlacklistedWorld(player.getWorld().getName()) && !apiBlocked(player);
    }

    private boolean apiBlocked(Player player) {
        Set<String> blocks = apiBlocks.get(player.getUniqueId());
        return blocks != null && !blocks.isEmpty();
    }

    // =========================================================================
    //  RANGEMENT DES OBJETS : inventaire porté puis pages suivantes
    // =========================================================================

    /** Copie des emplacements 0 à 35 de l'inventaire porté, sélecteur compris (il ne se fond avec rien). */
    private static ItemStack[] liveSlots(PlayerInventory inv) {
        // Copie du tableau seulement : SlotFiller ne modifie jamais une pile, il en pose une nouvelle
        return inv.getStorageContents();
    }

    /** Réservations encore valides d'un joueur, ou null. */
    private Deque<Reservation> activeReservations(UUID uuid) {
        Deque<Reservation> pending = reservations.get(uuid);
        if (pending == null) return null;
        int now = Bukkit.getCurrentTick();
        for (Reservation reservation : pending) {
            reservation.parts.removeIf(part -> part[0] <= 0 || now - part[1] > reservationTicks);
        }
        pending.removeIf(reservation -> reservation.parts.isEmpty());
        if (pending.isEmpty()) {
            reservations.remove(uuid);
            return null;
        }
        return pending;
    }

    /**
     * Réserve la place d'objets laissés au ramassage. Regroupée par objet, mais chaque part garde son heure : un flux
     * d'objets jamais ramassés (eau, entonnoir, ramassage interdit) ne réserve que ses dernières secondes.
     */
    private void reserve(UUID uuid, ItemStack stack, int amount) {
        int now = Bukkit.getCurrentTick();
        Deque<Reservation> pending = reservations.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        Reservation target = null;
        for (Reservation reservation : pending) {
            if (reservation.item.isSimilar(stack)) {
                target = reservation;
                break;
            }
        }
        if (target == null) {
            if (pending.size() >= MAX_RESERVATION_ITEMS) pending.pollFirst();
            target = new Reservation(stack.clone());
            pending.addLast(target);
        }
        int[] last = target.parts.peekLast();
        if (last != null && last[1] == now) {
            last[0] += amount;
            return;
        }
        // Une part par tick au plus : le plafond couvre toute la fenêtre, aucune part n'est oubliée avant d'expirer
        if (target.parts.size() > reservationTicks + 1) target.parts.pollFirst();
        target.parts.addLast(new int[]{amount, now});
    }

    /**
     * Place que l'inventaire porté offre encore à {@code template}, comme le ramassage vanilla la verrait (main
     * secondaire comprise), moins celle des objets déjà laissés au ramassage et pas encore ramassés.
     */
    private int liveRoom(Player player, ItemStack template) {
        PlayerInventory inv = player.getInventory();
        Deque<Reservation> pending = activeReservations(player.getUniqueId());
        if (pending == null) {
            return SlotFiller.capacity(inv.getStorageContents(), template, switcherSlot) + offHandRoom(inv, template);
        }
        ItemStack[] virtual = liveSlots(inv);
        for (Reservation reservation : pending) {
            SlotFiller.fill(virtual, reservation.item, reservation.total(), switcherSlot, null);
        }
        // Avec des réservations en cours, la main secondaire n'est pas comptée : prudence
        return SlotFiller.capacity(virtual, template, switcherSlot);
    }

    private static int offHandRoom(PlayerInventory inv, ItemStack template) {
        ItemStack offHand = inv.getItemInOffHand();
        if (offHand.isEmpty() || !offHand.isSimilar(template)) return 0;
        return Math.max(0, SlotFiller.stackLimit(offHand) - offHand.getAmount());
    }

    private static int fillOffHand(PlayerInventory inv, ItemStack template, int amount) {
        int room = offHandRoom(inv, template);
        int put = Math.min(room, amount);
        if (put <= 0) return 0;
        ItemStack grown = inv.getItemInOffHand().clone();
        grown.setAmount(grown.getAmount() + put);
        inv.setItemInOffHand(grown);
        return put;
    }

    /**
     * Range {@code amount} exemplaires de {@code template} : piles partielles puis emplacements libres de
     * l'inventaire porté (au plus {@code liveBudget}, et jamais la place réservée au ramassage), main secondaire si
     * elle porte une pile compatible, puis piles partielles et emplacements libres des autres pages par numéro
     * croissant. Jamais sur l'emplacement du sélecteur, jamais pendant un changement de page, jamais dans des pages
     * figées.
     */
    private Placement place(Player player, ItemStack template, int amount, int liveBudget) {
        if (amount <= 0 || switching != null) return Placement.NONE;
        int live = 0;
        int room = Math.min(liveBudget, liveRoom(player, template));
        if (room > 0) {
            PlayerInventory inv = player.getInventory();
            int toLive = Math.min(amount, room);
            ItemStack[] slots = liveSlots(inv);
            boolean[] changed = new boolean[PAGE_SIZE];
            live = SlotFiller.fill(slots, template, toLive, switcherSlot, changed);
            for (int i = 0; i < PAGE_SIZE; i++) {
                if (changed[i]) inv.setItem(i, slots[i]);
            }
            if (live < toLive) live += fillOffHand(inv, template, toLive - live);
        }
        int stored = 0;
        int firstPage = -1;
        PlayerInventoryData data = cache.get(player.getUniqueId());
        if (amount > live && data != null && pagesOpen(player)) {
            PlayerInventoryData.StoredFill fill = data.fillStoredPages(template, amount - live, switcherSlot);
            stored = fill.placed();
            firstPage = fill.firstPage();
            if (stored > 0) {
                scheduleDeferredSave(player.getUniqueId());
                ItemStack one = template.clone();
                one.setAmount(1);
                Bukkit.getPluginManager().callEvent(new PlayerItemsStoredEvent(player, one, stored, firstPage));
            }
        }
        return new Placement(live, stored, firstPage);
    }

    /** Range des objets rendus par le plugin lui-même ; ce qui ne trouve aucune place tombe aux pieds du joueur. */
    private void storeOrDrop(Player player, Collection<ItemStack> items, String reason) {
        if (items.isEmpty()) return;
        int total = 0;
        int dropped = 0;
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) continue;
            total += item.getAmount();
            Placement placement = place(player, item, item.getAmount(), Integer.MAX_VALUE);
            int left = item.getAmount() - placement.total();
            if (left > 0) {
                ItemStack rest = item.clone();
                rest.setAmount(left);
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
                dropped += left;
            }
        }
        if (total > 0) {
            plugin.getLogger().warning(reason + " (" + player.getName() + ") : " + total + " objet(s) rangé(s)"
                + (dropped > 0 ? ", dont " + dropped + " posé(s) au sol faute de place" : ""));
        }
    }

    /** Joueur qui peut recevoir le débordement d'un objet apparu près de lui. */
    public boolean isEligibleRecipient(Player player, World world) {
        return player != null
            && player.isOnline()
            && !player.isDead()
            && player.getWorld() == world
            && player.getGameMode() != GameMode.SPECTATOR
            && player.getCanPickupItems()
            && cache.containsKey(player.getUniqueId())
            && switching == null
            && pagesOpen(player);
    }

    /** Objet marqué par le plugin qui le fait apparaître (décor, empileur à quantité cachée) : jamais rangé. */
    public boolean isForeign(Item entity) {
        return entity.getThrower() != null
            || !entity.canPlayerPickup()
            || entity.getPickupDelay() >= NEVER_PICKUP_DELAY
            || !entity.canMobPickup()
            || entity.customName() != null;
    }

    /**
     * Objet apparu sur un joueur ({@link org.Cry_Legende.sHInventoryPages.listeners.OverflowListener}), pendant son
     * ItemSpawnEvent en priorité HIGH. S'il tient dans l'inventaire porté, une fois réservée la place des objets
     * déjà laissés au ramassage, il reste au ramassage vanilla. Sinon il est rangé (inventaire porté puis pages) :
     * l'apparition est annulée s'il est rangé en entier, sinon sa pile est réduite au reste. Tout se décide ici,
     * avant WildStacker (HIGHEST) : l'empileur ne lit la quantité qu'après, et n'empile que le reste. Décider plus
     * tard (fin de tick) dupliquait : WildStacker avait déjà fusionné les piles dans sa propre réserve.
     */
    public void captureSpawnedItem(Player player, ItemSpawnEvent event, java.util.function.BooleanSupplier isGift) {
        Item entity = event.getEntity();
        ItemStack stack = entity.getItemStack();
        int amount = stack.getAmount();
        if (amount <= 0) return;
        int room = liveRoom(player, stack);
        if (room >= amount) {
            reserve(player.getUniqueId(), stack, amount);
            return;
        }
        // Vérification la plus coûteuse (pile d'appels) seulement quand il faut vraiment ranger
        if (isGift != null && !isGift.getAsBoolean()) return;
        Placement placement = place(player, stack, amount, room);
        int left = amount - placement.total();
        if (placement.total() > 0) {
            if (left == 0) {
                event.setCancelled(true);
            } else {
                ItemStack rest = stack.clone();
                rest.setAmount(left);
                entity.setItemStack(rest);
            }
            playSound(player, overflowSound, overflowVolume, overflowPitch);
        }
        if (placement.stored() > 0) notifyStored(player, placement.stored(), placement.firstPage());
        PlayerInventoryData data = cache.get(player.getUniqueId());
        if (left > 0 && data != null && data.getMaxPages() > 1) notifyFull(player);
    }

    /** Un joueur a ramassé des objets : la place qui leur était réservée est libérée. */
    public void consumeReservation(Player player, ItemStack picked, int amount) {
        Deque<Reservation> pending = reservations.get(player.getUniqueId());
        if (pending == null || amount <= 0) return;
        int left = amount;
        Iterator<Reservation> iterator = pending.iterator();
        while (iterator.hasNext() && left > 0) {
            Reservation reservation = iterator.next();
            if (!reservation.item.isSimilar(picked)) continue;
            Iterator<int[]> parts = reservation.parts.iterator();
            while (parts.hasNext() && left > 0) {
                int[] part = parts.next();
                int used = Math.min(part[0], left);
                part[0] -= used;
                left -= used;
                if (part[0] <= 0) parts.remove();
            }
            if (reservation.parts.isEmpty()) iterator.remove();
        }
        if (pending.isEmpty()) reservations.remove(player.getUniqueId());
    }

    public boolean isOverflowEnabled() {
        return overflowEnabled;
    }

    public double getOverflowCaptureRadius() {
        return overflowRadius;
    }

    public boolean isCaptureBrokenBlockDrops() {
        return captureBrokenBlocks;
    }

    public double getLootRadius() {
        return lootRadius;
    }

    public double getExplosionRadius() {
        return explosionRadius;
    }

    private void notifyStored(Player player, int amount, int page) {
        if (!cooldownPassed(lastStoredNotice, player.getUniqueId())) return;
        MessageUtil.sendMessage(player, config.getMessage("item_sent_to_next_page",
            "%amount%", String.valueOf(amount), "%page%", String.valueOf(page)));
    }

    private void notifyFull(Player player) {
        if (!cooldownPassed(lastFullNotice, player.getUniqueId())) return;
        MessageUtil.sendMessage(player, config.getMessage("all_pages_full"));
    }

    private boolean cooldownPassed(Map<UUID, Integer> lastTicks, UUID uuid) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastTicks.get(uuid);
        if (last != null && now - last < noticeCooldownTicks) return false;
        lastTicks.put(uuid, now);
        return true;
    }

    // =========================================================================
    //  MORT
    // =========================================================================

    /** Retire les objets d'interface d'une liste de drops. */
    public void stripInterfaceItems(List<ItemStack> drops) {
        drops.removeIf(this::isSwitcherItem);
    }

    /**
     * Mort, en priorité MONITOR : la décision de garder l'inventaire (gamerule, parcours, autre plugin) est prise.
     * CURRENT : la page portée suit les règles du serveur, les autres pages sont gardées. ALL : les autres pages
     * accessibles tombent aussi (sauf pages figées : monde interdit, parcours). NONE : la page portée est gardée
     * (comme keepInventory pour ses 36 emplacements), l'armure et la main secondaire suivent les règles du serveur.
     */
    public void handleDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        stripInterfaceItems(event.getDrops());
        PlayerInventoryData data = cache.get(player.getUniqueId());
        if (data == null || event.getKeepInventory()) return;
        switch (deathMode) {
            case ALL -> {
                if (!pagesOpen(player)) break;
                List<ItemStack> extra = data.drainAccessibleStoredPages();
                if (!extra.isEmpty()) {
                    event.getDrops().addAll(extra);
                    // Pages vidées écrites tout de suite : elles ne doivent pas revenir après un arrêt brutal
                    saveSnapshot(data, false);
                }
            }
            case NONE -> keepWornPage(player, event);
            case CURRENT -> {
            }
        }
        // Après le vidage de l'inventaire par le serveur, la page courante est recapturée et écrite
        scheduleDeferredSave(player.getUniqueId());
    }

    /**
     * Garde les 36 emplacements portés en passant l'inventaire en keepInventory, puis refait à la main ce que le
     * serveur aurait fait du reste. Un objet des 36 emplacements encore dans les drops en est retiré et reste en
     * place. L'armure et la main secondaire encore dans les drops tombent : elles quittent l'inventaire. Un objet
     * qu'un autre plugin a placé dans itemsToKeep reste en place. Tout autre objet (retiré des drops par un plugin
     * qui s'en charge, malédiction de disparition) quitte l'inventaire, comme sans keepInventory.
     */
    private void keepWornPage(Player player, PlayerDeathEvent event) {
        List<ItemStack> drops = event.getDrops();
        List<ItemStack> keptByOthers = new ArrayList<>(event.getItemsToKeep());
        PlayerInventory inv = player.getInventory();
        event.setKeepInventory(true);
        for (int i = 0; i < PAGE_SIZE; i++) {
            // L'emplacement du sélecteur compris : un vrai objet qui s'y trouve tomberait ET resterait sinon
            ItemStack item = inv.getItem(i);
            if (item == null || item.isEmpty()) continue;
            boolean kept = !isSwitcherItem(item) && (removeOne(drops, item) || removeOne(keptByOthers, item));
            if (!kept) inv.setItem(i, null);
        }
        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] != null && !armor[i].isEmpty() && !removeOne(keptByOthers, armor[i])) armor[i] = null;
        }
        inv.setArmorContents(armor);
        ItemStack offHand = inv.getItemInOffHand();
        if (!offHand.isEmpty() && !removeOne(keptByOthers, offHand)) inv.setItemInOffHand(null);
    }

    private static boolean removeOne(List<ItemStack> drops, ItemStack item) {
        Iterator<ItemStack> iterator = drops.iterator();
        while (iterator.hasNext()) {
            ItemStack drop = iterator.next();
            if (drop != null && drop.equals(item)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    public void handleRespawn(Player player) {
        if (cache.get(player.getUniqueId()) == null) {
            if (!isLoading(player)) loadPlayerData(player);
        } else {
            updateSwitcherItem(player);
        }
    }

    // =========================================================================
    //  SÉLECTEUR DE PAGES
    // =========================================================================

    public void updateAllOnlineSwitcherItems() {
        Bukkit.getOnlinePlayers().forEach(this::updateSwitcherItem);
    }

    public void updateSwitcherItem(Player player) {
        if (player == null || !player.isOnline()) return;
        PlayerInventoryData data = cache.get(player.getUniqueId());
        ItemStack item;
        if (data == null) {
            item = createBarrierItem(readPageStamp(player.getInventory().getItem(switcherSlot)));
        } else if (!pagesOpen(player)) {
            item = createBarrierItem(data.getCurrentPage());
        } else {
            item = createSwitcherItem(data.getCurrentPage(), data.getMaxPages());
        }
        placeInSwitcherSlot(player, item);
    }

    /**
     * Pose le sélecteur (ou la barrière) sur son emplacement. Un vrai objet qui s'y trouvait (ramassé pendant la
     * réapparition, posé par un autre plugin, emplacement changé...) est rangé ailleurs. Tant que les pages ne sont
     * pas chargées, il reste en place (l'emplacement est protégé) : il sera rangé avec elles, et il est enregistré
     * par Minecraft avec l'inventaire si le joueur part avant. Réparation silencieuse.
     */
    private void placeInSwitcherSlot(Player player, ItemStack item) {
        PlayerInventory inv = player.getInventory();
        ItemStack displaced = inv.getItem(switcherSlot);
        boolean realItem = displaced != null && !displaced.isEmpty() && !isSwitcherItem(displaced);
        if (realItem && !cache.containsKey(player.getUniqueId())) return;
        inv.setItem(switcherSlot, item);
        if (realItem) storeOrDrop(player, List.of(displaced), "objet trouvé sur l'emplacement du sélecteur");
    }

    /** /ship reload a changé l'emplacement du sélecteur : l'ancien est libéré, le nouveau est posé partout. */
    public void moveSwitcherSlot(int oldSlot) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack old = player.getInventory().getItem(oldSlot);
            if (isSwitcherItem(old)) player.getInventory().setItem(oldSlot, null);
            updateSwitcherItem(player);
        }
    }

    @SuppressWarnings("deprecation") // noms et descriptions à codes couleur legacy, comme le reste de la config
    public ItemStack createSwitcherItem(int currentPage, int maxPages) {
        ItemStack item = new ItemStack(switcherMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MessageUtil.colorize(format(switcherName, currentPage, maxPages)));
            List<String> lore = new ArrayList<>(switcherLore.size());
            for (String line : switcherLore) lore.add(MessageUtil.colorize(format(line, currentPage, maxPages)));
            meta.setLore(lore);
            if (enchantGlow) meta.setEnchantmentGlintOverride(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(switcherKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(pageKey, PersistentDataType.INTEGER, currentPage);
            item.setItemMeta(meta);
        }
        int amount = amountEqualsPage ? currentPage : fixedAmount;
        item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        return item;
    }

    @SuppressWarnings("deprecation")
    public ItemStack createBarrierItem(Integer page) {
        ItemStack item = new ItemStack(barrierMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MessageUtil.colorize(barrierName));
            List<String> lore = new ArrayList<>(barrierLore.size());
            for (String line : barrierLore) lore.add(MessageUtil.colorize(line));
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(switcherKey, PersistentDataType.BYTE, (byte) 1);
            if (page != null) meta.getPersistentDataContainer().set(pageKey, PersistentDataType.INTEGER, page);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String format(String text, int currentPage, int maxPages) {
        return text.replace("%current_page%", String.valueOf(currentPage)).replace("%max_pages%", String.valueOf(maxPages));
    }

    /** Page que représente l'inventaire porté, d'après le sélecteur (ou la barrière) enregistré avec lui. */
    private Integer readPageStamp(ItemStack item) {
        if (!isSwitcherItem(item)) return null;
        return item.getPersistentDataContainer().get(pageKey, PersistentDataType.INTEGER);
    }

    public boolean isSwitcherItem(ItemStack item) {
        return item != null && !item.isEmpty() && item.getPersistentDataContainer().has(switcherKey);
    }

    public boolean isBlacklistedWorld(String worldName) {
        return blacklistedWorlds.contains(worldName);
    }

    public int getSwitcherSlot() {
        return switcherSlot;
    }

    private void playSound(Player player, Sound sound, float volume, float pitch) {
        if (player != null && sound != null) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    // =========================================================================
    //  PERMISSIONS
    // =========================================================================

    /**
     * Nombre de pages autorisé : {@code shinventorypages.pages.*} donne le plafond, sinon le plus grand
     * {@code shinventorypages.pages.<n>} jusqu'au plafond ; default_pages est un minimum. Une dizaine de
     * hasPermission, sans parcourir toutes les permissions effectives.
     */
    public int getMaxPagesForPlayer(Player player) {
        int maxPossible = Math.max(1, config.getMaxPossiblePages());
        int minimum = Math.max(1, Math.min(config.getDefaultPages(), maxPossible));
        if (player == null) return minimum;
        if (player.hasPermission(PAGE_PERMISSION + "*")) return maxPossible;
        for (int pages = maxPossible; pages > minimum; pages--) {
            if (player.hasPermission(PAGE_PERMISSION + pages)) return pages;
        }
        return minimum;
    }

    /**
     * Relit le nombre de pages autorisé d'un joueur chargé (tâche périodique), jamais pendant que ses pages sont
     * figées. Si la page portée disparaît, elle reste enregistrée sous son numéro, cachée, et le joueur porte la
     * dernière page accessible ; tout est écrit immédiatement.
     */
    public void refreshMaxPages(Player player) {
        PlayerInventoryData data = cache.get(player.getUniqueId());
        if (data == null || switching != null || player.isDead() || !pagesOpen(player)) return;
        int newMax = getMaxPagesForPlayer(player);
        if (newMax == data.getMaxPages()) return;
        captureLive(player, data);
        data.setMaxPages(newMax);
        boolean hidden = data.isCurrentPageHidden();
        List<ItemStack> leftovers = hidden ? leaveHiddenPage(player, data) : new ArrayList<>();
        updateSwitcherItem(player);
        storeOrDrop(player, leftovers, "objets déplacés au changement du nombre de pages");
        if (hidden) alignedSave(player, data);
        else scheduleDeferredSave(player.getUniqueId());
        MessageUtil.sendMessage(player, config.getMessage("max_pages_updated", "%max_pages%", String.valueOf(newMax)));
    }

    /** Tâche périodique (inventory.permission_check_interval) : nombre de pages autorisé de chaque joueur. */
    public void refreshAllMaxPages() {
        for (Player player : Bukkit.getOnlinePlayers()) refreshMaxPages(player);
    }

    /** Tâche périodique (inventory.capture_interval) : capture de l'inventaire porté de chaque joueur chargé. */
    public void captureAllLive() {
        for (Player player : Bukkit.getOnlinePlayers()) captureIfLoaded(player);
    }

    public PlayerInventoryData getPlayerData(UUID uuid) {
        return cache.get(uuid);
    }

    // =========================================================================
    //  API
    // =========================================================================

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("SHInventoryPages: inventory changes must be made on the main thread");
        }
    }

    /** Instance gardée par un plugin après un rechargement de SHInventoryPages : plus rien ne doit passer par elle. */
    private boolean retired(String call) {
        if (plugin.isEnabled() && plugin.getInventoryManager() == this) return false;
        plugin.getLogger().warning("API SHInventoryPages appelée sur une instance arrêtée (" + call
            + ") : récupérez l'API à nouveau après un rechargement.");
        return true;
    }

    private boolean usableTarget(Player player) {
        return player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    @Override
    public @NotNull Map<Integer, ItemStack> addItems(@NotNull Player player, @NotNull ItemStack... items) {
        requireMainThread();
        Map<Integer, ItemStack> leftovers = new HashMap<>();
        boolean usable = !retired("addItems") && usableTarget(player);
        int stored = 0;
        int firstPage = -1;
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null || item.isEmpty()) continue;
            ItemStack template = item.clone();
            Placement placement = usable ? place(player, template, template.getAmount(), Integer.MAX_VALUE) : Placement.NONE;
            stored += placement.stored();
            if (firstPage < 0) firstPage = placement.firstPage();
            int left = template.getAmount() - placement.total();
            if (left > 0) {
                ItemStack rest = template.clone();
                rest.setAmount(left);
                leftovers.put(i, rest);
            }
        }
        if (stored > 0) notifyStored(player, stored, firstPage);
        return leftovers;
    }

    @Override
    public int getFreeSpace(@NotNull Player player, @NotNull ItemStack item) {
        requireMainThread();
        if (item.isEmpty() || retired("getFreeSpace") || !usableTarget(player)) return 0;
        int free = liveRoom(player, item);
        PlayerInventoryData data = cache.get(player.getUniqueId());
        if (data != null && pagesOpen(player)) free += data.storedCapacity(item, switcherSlot);
        return free;
    }

    @Override
    public int countItems(@NotNull Player player, @NotNull ItemStack item) {
        requireMainThread();
        if (item.isEmpty() || retired("countItems") || !usableTarget(player)) return 0;
        int count = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < PAGE_SIZE; i++) {
            ItemStack slot = inv.getItem(i);
            if (i != switcherSlot && slot != null && slot.isSimilar(item)) count += slot.getAmount();
        }
        ItemStack offHand = inv.getItemInOffHand();
        if (offHand.isSimilar(item)) count += offHand.getAmount();
        PlayerInventoryData data = cache.get(player.getUniqueId());
        if (data != null && pagesOpen(player)) count += data.countStored(item);
        return count;
    }

    @Override
    public @NotNull Map<Integer, ItemStack> removeItems(@NotNull Player player, @NotNull ItemStack... items) {
        requireMainThread();
        Map<Integer, ItemStack> leftovers = new HashMap<>();
        boolean usable = !retired("removeItems") && usableTarget(player) && switching == null;
        PlayerInventoryData data = usable ? cache.get(player.getUniqueId()) : null;
        boolean pages = data != null && pagesOpen(player);
        PlayerInventory inv = player.getInventory();
        for (int index = 0; index < items.length; index++) {
            ItemStack item = items[index];
            if (item == null || item.isEmpty()) continue;
            int remaining = item.getAmount();
            if (usable) {
                for (int i = 0; i < PAGE_SIZE && remaining > 0; i++) {
                    if (i == switcherSlot) continue;
                    ItemStack slot = inv.getItem(i);
                    if (slot == null || !slot.isSimilar(item)) continue;
                    int taken = Math.min(slot.getAmount(), remaining);
                    remaining -= taken;
                    if (taken == slot.getAmount()) {
                        inv.setItem(i, null);
                    } else {
                        ItemStack rest = slot.clone();
                        rest.setAmount(slot.getAmount() - taken);
                        inv.setItem(i, rest);
                    }
                }
                ItemStack offHand = inv.getItemInOffHand();
                if (remaining > 0 && offHand.isSimilar(item)) {
                    int taken = Math.min(offHand.getAmount(), remaining);
                    remaining -= taken;
                    if (taken == offHand.getAmount()) {
                        inv.setItemInOffHand(null);
                    } else {
                        ItemStack rest = offHand.clone();
                        rest.setAmount(offHand.getAmount() - taken);
                        inv.setItemInOffHand(rest);
                    }
                }
                if (remaining > 0 && pages) {
                    int taken = data.removeStored(item, remaining);
                    if (taken > 0) {
                        remaining -= taken;
                        scheduleDeferredSave(player.getUniqueId());
                    }
                }
            }
            if (remaining > 0) {
                ItemStack rest = item.clone();
                rest.setAmount(remaining);
                leftovers.put(index, rest);
            }
        }
        return leftovers;
    }

    @Override
    public boolean isLoaded(@NotNull Player player) {
        return !retired("isLoaded") && cache.containsKey(player.getUniqueId());
    }

    @Override
    public int getCurrentPage(@NotNull Player player) {
        if (retired("getCurrentPage")) return 0;
        PlayerInventoryData data = cache.get(player.getUniqueId());
        return data != null ? data.getCurrentPage() : 0;
    }

    @Override
    public int getMaxPages(@NotNull Player player) {
        if (retired("getMaxPages")) return 0;
        PlayerInventoryData data = cache.get(player.getUniqueId());
        return data != null ? data.getMaxPages() : 0;
    }

    @Override
    public void blockPageSwitching(@NotNull Player player, @NotNull String blockIdentifier) {
        if (retired("blockPageSwitching")) return;
        // Ensemble concurrent : l'API peut être appelée hors du thread principal
        apiBlocks.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(blockIdentifier);
        refreshSwitcherLater(player);
    }

    @Override
    public void unblockPageSwitching(@NotNull Player player, @NotNull String blockIdentifier) {
        if (retired("unblockPageSwitching")) return;
        boolean[] removed = {false};
        // Retrait atomique : un blocage ajouté en même temps ne peut pas être perdu avec l'ensemble vidé
        apiBlocks.computeIfPresent(player.getUniqueId(), (key, blocks) -> {
            removed[0] = blocks.remove(blockIdentifier);
            return blocks.isEmpty() ? null : blocks;
        });
        if (removed[0]) refreshSwitcherLater(player);
    }

    private void refreshSwitcherLater(Player player) {
        if (Bukkit.isPrimaryThread()) {
            updateSwitcherItem(player);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> updateSwitcherItem(player));
        }
    }

    @Override
    public boolean isPageSwitchingApiBlocked(@NotNull Player player) {
        return !retired("isPageSwitchingApiBlocked") && apiBlocked(player);
    }

    @Override
    public boolean isPageSwitchingBlockedBy(@NotNull Player player, @NotNull String blockIdentifier) {
        if (retired("isPageSwitchingBlockedBy")) return false;
        Set<String> blocks = apiBlocks.get(player.getUniqueId());
        return blocks != null && blocks.contains(blockIdentifier);
    }
}
