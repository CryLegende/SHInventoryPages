package org.Cry_Legende.sHInventoryPages;

import org.Cry_Legende.sHInventoryPages.history.HistoryMenus;
import org.Cry_Legende.sHInventoryPages.history.PageHistory;
import org.Cry_Legende.sHInventoryPages.protection.WorldGuardPickupRules;
import org.Cry_Legende.sHInventoryPages.api.ISHInventoryPagesAPI;
import org.Cry_Legende.sHInventoryPages.commands.CommandSHInventoryPages;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.database.DatabaseManager;
import org.Cry_Legende.sHInventoryPages.database.MySQLManager;
import org.Cry_Legende.sHInventoryPages.database.SQLiteManager;
import org.Cry_Legende.sHInventoryPages.inventory.InventoryManager;
import org.Cry_Legende.sHInventoryPages.listeners.InventoryListener;
import org.Cry_Legende.sHInventoryPages.listeners.OverflowListener;
import org.Cry_Legende.sHInventoryPages.listeners.PlayerListener;
import org.Cry_Legende.sHInventoryPages.network.NetworkLink;
import org.Cry_Legende.sHInventoryPages.network.NetworkSync;
import org.Cry_Legende.sHInventoryPages.network.ShNetworkLink;
import org.Cry_Legende.sHInventoryPages.placeholders.SHPlaceholders;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class SHInventoryPages extends JavaPlugin {


    private static SHInventoryPages instance;
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private InventoryManager inventoryManager;
    private ExecutorService databaseExecutor;
    private BukkitTask autoSaveTask;
    private BukkitTask permissionTask;
    private BukkitTask captureTask;
    private BukkitTask historyTask;
    private BukkitTask historyPurgeTask;
    private PageHistory pageHistory;
    private HistoryMenus historyMenus;
    private SHPlaceholders placeholders;
    private boolean fullyEnabled;

    @Override
    public void onEnable() {
        instance = this;
        try {
            this.configManager = new ConfigManager(this);
            configManager.loadConfig();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Critical error loading the configuration: SHInventoryPages is disabled.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!setupDatabase()) {
            getLogger().severe("Database initialization failed: SHInventoryPages is disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.databaseExecutor = Executors.newFixedThreadPool(configManager.getDatabaseThreads(), daemonThreads());
        try {
            this.inventoryManager = new InventoryManager(this, databaseExecutor);
            this.pageHistory = new PageHistory(this);
            pageHistory.start();
            inventoryManager.setHistory(pageHistory);
            enableNetwork();
            hookProtections();
            registerListeners();
            registerCommands();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Critical error during initialization: SHInventoryPages is disabled.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getServicesManager().register(ISHInventoryPagesAPI.class, inventoryManager, this, ServicePriority.Normal);
        fullyEnabled = true;

        for (Player player : Bukkit.getOnlinePlayers()) {
            inventoryManager.loadPlayerData(player);
        }
        startTasks();

        syncPlaceholders();
        getLogger().info("SHInventoryPages v" + getPluginMeta().getVersion() + " enabled successfully!");
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et que 'reseau.actif' est vrai : les pages sont alors partagées
     * avec les autres serveurs par la base (version de session, sauvegarde de départ attendue par l'arrivée). Les
     * classes qui touchent à SHNetwork ne sont chargées qu'ici : sans lui, le plugin se comporte exactement comme avant.
     */
    private void enableNetwork() {
        if (!configManager.isNetworkEnabled()) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork")) return;
        try {
            NetworkLink link = ShNetworkLink.open();
            if (!databaseManager.enableNetwork(link.serverName())) {
                getLogger().severe("SHNetwork présent mais cette base ne peut pas être partagée entre serveurs (type "
                    + configManager.getDatabaseType() + ") : les pages restent locales à ce serveur.");
                return;
            }
            NetworkSync sync = new NetworkSync(this, link,
                Duration.ofSeconds(configManager.getNetworkWaitSeconds()), configManager.getNetworkSyncWaitSeconds());
            inventoryManager.setNetwork(sync);
            sync.start(inventoryManager::departureFuture);
            getLogger().info("SHNetwork détecté : pages partagées avec les autres serveurs (serveur " + link.serverName() + ").");
        } catch (Exception | LinkageError e) {
            getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable, pages locales à ce serveur : " + e);
        }
    }

    /**
     * Ramassage vers les pages : le drapeau item-pickup des régions WorldGuard est respecté, comme le fait le
     * ramassage vanilla. La classe qui touche à WorldGuard n'est chargée que s'il est installé.
     */
    private void hookProtections() {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) return;
        try {
            inventoryManager.setPickupRules(WorldGuardPickupRules.open());
            getLogger().info("WorldGuard détecté : le ramassage vers les pages respecte le drapeau item-pickup des régions.");
        } catch (Exception | LinkageError e) {
            getLogger().log(Level.WARNING, "WorldGuard présent mais illisible : ramassage vers les pages sans règles de région : " + e);
        }
    }

    /** Enregistre ou retire l'extension PlaceholderAPI selon placeholders.enabled (démarrage et /shinventorypages reload). */
    private void syncPlaceholders() {
        boolean wanted = configManager.isPapiEnabled();
        if (wanted && placeholders == null) {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
                getLogger().warning("PlaceholderAPI not found, placeholders will not work.");
                return;
            }
            try {
                SHPlaceholders expansion = new SHPlaceholders(this);
                if (expansion.register()) placeholders = expansion;
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error hooking into PlaceholderAPI.", e);
            }
        } else if (!wanted && placeholders != null) {
            placeholders.unregister();
            placeholders = null;
        }
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "SHInventoryPages-DB-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void onDisable() {
        cancelTasks();
        if (fullyEnabled && inventoryManager != null) {
            // Joueurs encore connectés (arrêt du serveur, rechargement) : capture et écriture avant de fermer la base
            inventoryManager.shutdown(configManager.getShutdownSaveTimeout());
            // Copies de déconnexion de l'arrêt écrites avant la fermeture de la base
            pageHistory.shutdown(10);
        } else {
            getLogger().warning("Plugin was not fully enabled: player data save skipped to protect stored pages.");
        }
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
            try {
                if (!databaseExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    getLogger().severe("Database writes still running after 10 s; closing the database anyway.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (databaseManager != null) {
            databaseManager.disconnect();
        }
        if (placeholders != null) {
            placeholders.unregister();
            placeholders = null;
        }
        getServer().getServicesManager().unregisterAll(this);
        fullyEnabled = false;
        instance = null;
    }

    private boolean setupDatabase() {
        String type = configManager.getDatabaseType().toLowerCase();
        getLogger().info("Initializing database type: " + type);
        try {
            if (type.equals("mysql") || type.equals("mariadb")) {
                this.databaseManager = new MySQLManager(this);
            } else {
                if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                    getLogger().severe("Could not create plugin data folder!");
                    return false;
                }
                this.databaseManager = new SQLiteManager(this);
            }
            if (!databaseManager.connect()) {
                return false;
            }
            if (!databaseManager.createTables()) {
                databaseManager.disconnect();
                return false;
            }
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not initialize database connection!", e);
            if (databaseManager != null) databaseManager.disconnect();
            return false;
        }
    }

    private void registerListeners() {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new InventoryListener(this), this);
        pm.registerEvents(new OverflowListener(this), this);
        this.historyMenus = new HistoryMenus(this);
        pm.registerEvents(historyMenus, this);
    }

    private void registerCommands() {
        PluginCommand command = getCommand("shinventorypages");
        if (command == null) throw new IllegalStateException("Command 'shinventorypages' missing from plugin.yml");
        CommandSHInventoryPages executor = new CommandSHInventoryPages(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void startTasks() {
        cancelTasks();
        long autoSave = configManager.getAutoSaveInterval() * 20L;
        if (autoSave > 0) {
            // Thread principal : la capture lit les inventaires vivants ; l'écriture part d'une copie, en asynchrone
            autoSaveTask = Bukkit.getScheduler().runTaskTimer(this, inventoryManager::autoSaveAll, autoSave, autoSave);
        } else {
            getLogger().info("Periodic auto-saving is disabled (interval <= 0).");
        }
        long permissions = configManager.getPermissionCheckInterval() * 20L;
        if (permissions > 0) {
            permissionTask = Bukkit.getScheduler().runTaskTimer(this, inventoryManager::refreshAllMaxPages, permissions, permissions);
        }
        long capture = configManager.getCaptureInterval() * 20L;
        if (capture > 0) {
            captureTask = Bukkit.getScheduler().runTaskTimer(this, inventoryManager::captureAllLive, capture, capture);
        }
        long periodic = configManager.getHistoryPeriodicMinutes() * 60L * 20L;
        if (periodic > 0 && pageHistory.isEnabled()) {
            historyTask = Bukkit.getScheduler().runTaskTimer(this, inventoryManager::recordPeriodicHistory, periodic, periodic);
        }
        if (pageHistory.isEnabled()) {
            // Une heure entre deux nettoyages ; le premier peu après le démarrage
            historyPurgeTask = Bukkit.getScheduler().runTaskTimer(this, pageHistory::purgeOld, 20L * 60L, 20L * 60L * 60L);
        }
    }

    private void cancelTasks() {
        if (autoSaveTask != null) autoSaveTask.cancel();
        if (permissionTask != null) permissionTask.cancel();
        if (captureTask != null) captureTask.cancel();
        if (historyTask != null) historyTask.cancel();
        if (historyPurgeTask != null) historyPurgeTask.cancel();
        autoSaveTask = null;
        permissionTask = null;
        captureTask = null;
        historyTask = null;
        historyPurgeTask = null;
    }

    /**
     * /shinventorypages reload : réglages, messages, objets et tâches. Un fichier invalide annule tout (exception).
     * Le type et l'accès à la base, et la section reseau, demandent un redémarrage.
     */
    public void reloadPluginConfig() throws ConfigManager.ConfigException {
        if (!fullyEnabled) return;
        int oldSlot = inventoryManager.getSwitcherSlot();
        configManager.reloadStrict();
        inventoryManager.saveAllNow();
        inventoryManager.updateSettingsFromConfig();
        pageHistory.reload();
        if (inventoryManager.getSwitcherSlot() != oldSlot) {
            inventoryManager.moveSwitcherSlot(oldSlot);
        } else {
            inventoryManager.updateAllOnlineSwitcherItems();
        }
        startTasks();
        syncPlaceholders();
    }

    public static SHInventoryPages getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public InventoryManager getInventoryManager() {
        return inventoryManager;
    }

    public PageHistory getPageHistory() {
        return pageHistory;
    }

    public HistoryMenus getHistoryMenus() {
        return historyMenus;
    }

    public boolean isFullyEnabled() {
        return fullyEnabled;
    }

    /** API, ou null tant que le plugin n'est pas prêt. Aussi enregistrée dans le ServicesManager de Bukkit. */
    @Nullable
    public ISHInventoryPagesAPI getApi() {
        if (!fullyEnabled || inventoryManager == null) {
            getLogger().warning("Attempted to access SHInventoryPages API while plugin is not fully enabled.");
            return null;
        }
        return inventoryManager;
    }
}
