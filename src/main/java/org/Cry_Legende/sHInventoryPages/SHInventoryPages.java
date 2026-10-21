package org.Cry_Legende.sHInventoryPages;

import org.Cry_Legende.sHInventoryPages.api.ISHInventoryPagesAPI; // Assurez-vous que ce package et cette classe existent si vous l'utilisez
import org.Cry_Legende.sHInventoryPages.commands.CommandSHInventoryPages;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.database.DatabaseManager;
import org.Cry_Legende.sHInventoryPages.database.MySQLManager;
import org.Cry_Legende.sHInventoryPages.database.SQLiteManager;
import org.Cry_Legende.sHInventoryPages.inventory.InventoryManager;
import org.Cry_Legende.sHInventoryPages.listeners.InventoryListener;
import org.Cry_Legende.sHInventoryPages.listeners.PlayerListener;
import org.Cry_Legende.sHInventoryPages.placeholders.SHPlaceholders;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable; // Pour l'API getter

import java.util.logging.Level;

public final class SHInventoryPages extends JavaPlugin {

    private static SHInventoryPages instance;
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private InventoryManager inventoryManager;
    private BukkitTask autoSaveTask;

    private boolean isFullyEnabled = false; // NOUVEAU DRAPEAU

    @Override
    public void onEnable() {
        instance = this;
        // Le prefix est initialisé tôt pour les messages d'erreur potentiels
        MessageUtil.setPluginPrefix(getConfig().getString("messages.prefix", "&8[&bSHInvPages&8] &r"));

        // 1. Charger la configuration
        try {
            this.configManager = new ConfigManager(this);
            configManager.loadConfig(); // Cela peut aussi logguer des erreurs mais ne devrait pas être fatal
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "---------------------------------------------");
            getLogger().log(Level.SEVERE, " Critical error loading plugin configuration!");
            getLogger().log(Level.SEVERE, " SHInventoryPages will be disabled.", e);
            getLogger().log(Level.SEVERE, "---------------------------------------------");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Initialiser la base de données
        if (!setupDatabase()) {
            // setupDatabase() loggue déjà les erreurs et appelle disablePlugin si nécessaire
            // mais on s'assure que disablePlugin est bien appelé si setupDatabase retourne false
            if (this.isEnabled()) { // Vérifier si pas déjà désactivé par setupDatabase
                getLogger().severe("---------------------------------------------");
                getLogger().severe(" Database initialization failed!");
                getLogger().severe(" SHInventoryPages will be disabled.");
                getLogger().severe("---------------------------------------------");
                getServer().getPluginManager().disablePlugin(this);
            }
            return;
        }

        // 3. Initialiser le gestionnaire d'inventaire
        try {
            this.inventoryManager = new InventoryManager(this);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "---------------------------------------------");
            getLogger().log(Level.SEVERE, " Critical error initializing InventoryManager!");
            getLogger().log(Level.SEVERE, " SHInventoryPages will be disabled.", e);
            getLogger().log(Level.SEVERE, "---------------------------------------------");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 4. Enregistrer les listeners
        try {
            registerListeners();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "---------------------------------------------");
            getLogger().log(Level.SEVERE, " Critical error registering listeners!");
            getLogger().log(Level.SEVERE, " SHInventoryPages will be disabled.", e);
            getLogger().log(Level.SEVERE, "---------------------------------------------");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 5. Enregistrer les commandes
        try {
            registerCommands();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "---------------------------------------------");
            getLogger().log(Level.SEVERE, " Critical error registering commands!");
            getLogger().log(Level.SEVERE, " SHInventoryPages will be disabled.", e);
            getLogger().log(Level.SEVERE, "---------------------------------------------");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Si tout s'est bien passé jusqu'ici, on peut considérer le plugin comme fonctionnel
        isFullyEnabled = true; // METTRE LE DRAPEAU À TRUE ICI
        getLogger().info("Core systems initialized. Proceeding with post-initialization tasks.");


        // Charger les données pour les joueurs déjà en ligne (seulement si isFullyEnabled)
        if (isFullyEnabled) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                inventoryManager.loadPlayerData(player);
            }
        }


        // Démarrer l'auto-save (seulement si isFullyEnabled)
        if (isFullyEnabled) {
            startAutoSaveTask();
        }

        // Hook PlaceholderAPI (seulement si isFullyEnabled)
        if (isFullyEnabled && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null && configManager.isPapiEnabled()) {
            try {
                new SHPlaceholders(this).register();
                getLogger().info("Successfully hooked into PlaceholderAPI.");
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error hooking into PlaceholderAPI.", e);
                // Ce n'est pas fatal, le plugin peut continuer
            }
        } else {
            if (isFullyEnabled && configManager.isPapiEnabled()) { // PAPI est activé dans la config mais non trouvé
                getLogger().warning("PlaceholderAPI not found, placeholders will not work.");
            }
        }

        getLogger().info("SHInventoryPages v" + getDescription().getVersion() + (isFullyEnabled ? " enabled successfully!" : " partially enabled (errors occurred, functionality may be limited)."));
        if (!isFullyEnabled) {
            getLogger().severe("SHInventoryPages did not fully enable due to errors. Please check logs. Plugin will be disabled to prevent data issues.");
            getServer().getPluginManager().disablePlugin(this); // Redondant si déjà fait, mais assure la désactivation
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling SHInventoryPages...");

        if (autoSaveTask != null && !autoSaveTask.isCancelled()) {
            autoSaveTask.cancel();
        }

        // Sauvegarder les données SEULEMENT si le plugin était complètement activé
        if (isFullyEnabled && inventoryManager != null) {
            getLogger().info("Saving all player data...");
            inventoryManager.saveAllPlayerData(false); // false pour synchrone
        } else if (!isFullyEnabled) {
            getLogger().warning("Plugin was not fully enabled. Skipping player data save to prevent potential data corruption.");
        } else if (inventoryManager == null && isFullyEnabled) { // Cas où invManager est null mais plugin était enabled (ne devrait pas arriver)
            getLogger().warning("Inventory Manager was null during disable, but plugin was marked as fully enabled. Data might not be saved!");
        }


        if (databaseManager != null) {
            databaseManager.disconnect();
        }

        getLogger().info("SHInventoryPages disabled.");
        isFullyEnabled = false; // Réinitialiser le drapeau
        instance = null;
    }

    private boolean setupDatabase() {
        String dbType = configManager.getDatabaseType().toLowerCase();
        getLogger().info("Initializing database type: " + dbType);
        try {
            if (dbType.equals("mysql") || dbType.equals("mariadb")) {
                this.databaseManager = new MySQLManager(this);
            } else { // Par défaut SQLite
                if (!getDataFolder().exists()) {
                    if (!getDataFolder().mkdirs()) {
                        getLogger().severe("Could not create plugin data folder!");
                        return false;
                    }
                }
                this.databaseManager = new SQLiteManager(this);
            }

            if (!this.databaseManager.connect()) {
                getLogger().severe("Failed to connect to the database.");
                return false;
            }
            if (!this.databaseManager.createTables()) {
                getLogger().severe("Failed to create necessary database tables.");
                // Si la création de table échoue, déconnecter pour libérer les ressources
                this.databaseManager.disconnect();
                return false;
            }
            getLogger().info("Database connection and table setup successful.");
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not initialize database connection!", e);
            if (this.databaseManager != null) { // Tenter de déconnecter si l'objet existe
                this.databaseManager.disconnect();
            }
            return false;
        }
    }

    private void registerListeners() {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new InventoryListener(this), this);
    }

    private void registerCommands() {
        getCommand("shinventorypages").setExecutor(new CommandSHInventoryPages(this));
        getCommand("shinventorypages").setTabCompleter(new CommandSHInventoryPages(this));
    }

    public void startAutoSaveTask() {
        if (!isFullyEnabled) { // Vérification supplémentaire
            getLogger().warning("Auto-save task not started because plugin is not fully enabled.");
            return;
        }
        if (autoSaveTask != null && !autoSaveTask.isCancelled()) {
            autoSaveTask.cancel();
        }
        long intervalTicks = configManager.getAutoSaveInterval() * 20L;
        if (intervalTicks <= 0) {
            getLogger().info("Periodic auto-saving is disabled (interval <= 0).");
            return;
        }
        // Thread principal : saveAllPlayerData lit les inventaires Bukkit vivants ; l'écriture en base
        // reste asynchrone (savePlayerData(..., true) envoie une copie au thread de la base).
        autoSaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!isFullyEnabled) { // Vérifier aussi dans la tâche elle-même
                getLogger().fine("Auto-save skipped: plugin not fully enabled.");
                if (autoSaveTask != null)
                    autoSaveTask.cancel(); // Annuler la tâche si le plugin n'est plus opérationnel
                return;
            }
            getLogger().fine("Running periodic auto-save...");
            inventoryManager.saveAllPlayerData(true);
        }, intervalTicks, intervalTicks);
        getLogger().info("Periodic auto-saving scheduled every " + configManager.getAutoSaveInterval() + " seconds.");
    }

    public void reloadPluginConfig() {
        if (autoSaveTask != null && !autoSaveTask.isCancelled()) {
            autoSaveTask.cancel();
        }

        // Sauvegarder les données actuelles avant de recharger, si le plugin était fonctionnel
        if (isFullyEnabled && inventoryManager != null) {
            getLogger().info("Saving player data before config reload...");
            inventoryManager.saveAllPlayerData(false); // Synchrone pour s'assurer que c'est fait
        }

        // Recharger la config
        configManager.loadConfig(); // Cela mettra à jour l'instance interne de config
        MessageUtil.setPluginPrefix(configManager.getPluginPrefix()); // Mettre à jour le préfixe avec la nouvelle config

        if (inventoryManager != null) {
            inventoryManager.updateSettingsFromConfig();
            if (isFullyEnabled)
                inventoryManager.updateAllOnlineSwitcherItems(); // Mettre à jour seulement si tout va bien
        }

        // Redémarrer l'auto-save si le plugin est toujours considéré comme pleinement fonctionnel
        if (isFullyEnabled) {
            startAutoSaveTask();
            getLogger().info("Configuration reloaded and auto-save task restarted.");
        } else {
            getLogger().warning("Configuration reloaded, but plugin is not fully enabled. Auto-save not restarted.");
        }
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

    // NOUVEAU GETTER pour le drapeau
    public boolean isFullyEnabled() {
        return isFullyEnabled;
    }

    @Nullable // NOUVEAU: Getter pour l'API, retourne null si pas prêt
    public ISHInventoryPagesAPI getApi() {
        if (!isFullyEnabled || this.inventoryManager == null) {
            getLogger().warning("Attempted to access SHInventoryPages API while plugin is not fully enabled or InventoryManager is null.");
            return null;
        }
        return this.inventoryManager; // Assumant qu'InventoryManager implémente ISHInventoryPagesAPI
    }
}
