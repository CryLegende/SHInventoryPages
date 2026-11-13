package org.Cry_Legende.sHInventoryPages.config;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.inventory.InventoryManager;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public class ConfigManager {

    private final SHInventoryPages plugin;
    private FileConfiguration config;
    private YamlConfiguration messages;

    public ConfigManager(SHInventoryPages plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        this.messages = loadMessages();
        MessageUtil.setPluginPrefix(getPluginPrefix());
    }

    /** Erreur de lecture d'un fichier de configuration : nom du fichier et cause. */
    public static final class ConfigException extends Exception {
        private final String file;

        ConfigException(String file, String message) {
            super(message);
            this.file = file;
        }

        public String getFile() {
            return file;
        }
    }

    /**
     * Relit config.yml et messages.yml pour /shinventorypages reload. Rien n'est remplacé tant que les deux fichiers ne
     * sont pas lus sans erreur : sinon l'exception porte le fichier en cause et l'ancienne configuration reste en place.
     */
    public void reloadStrict() throws ConfigException {
        YamlConfiguration newConfig = strict("config.yml");
        newConfig.setDefaults(bundled("config.yml"));
        YamlConfiguration newMessages = strict("messages.yml");
        newMessages.setDefaults(bundled("messages.yml"));
        this.config = newConfig;
        this.messages = newMessages;
        MessageUtil.setPluginPrefix(getPluginPrefix());
    }

    private YamlConfiguration strict(String name) throws ConfigException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(plugin.getDataFolder(), name));
        } catch (IOException | InvalidConfigurationException e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse("");
            throw new ConfigException(name, reason);
        }
        return yaml;
    }

    private YamlConfiguration bundled(String name) {
        try (InputStream in = plugin.getResource(name)) {
            return in == null ? new YamlConfiguration()
                : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    /**
     * messages.yml, complété par les textes du jar qui y manquent. Les messages d'une ancienne config.yml (section
     * messages) y sont repris une fois, à la création du fichier.
     */
    private YamlConfiguration loadMessages() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
            ConfigurationSection old = config.getConfigurationSection("messages");
            if (old != null) {
                YamlConfiguration created = YamlConfiguration.loadConfiguration(file);
                for (String key : old.getKeys(false)) {
                    if (old.isString(key)) created.set(key, old.getString(key));
                }
                try {
                    created.save(file);
                    plugin.getLogger().info("Messages repris de config.yml dans messages.yml (la section messages de config.yml ne sert plus).");
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "messages.yml non écrit : messages de config.yml gardés en mémoire", e);
                    return created;
                }
            }
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "messages.yml du jar illisible", e);
        }
        return loaded;
    }

    // --- Database ---
    public String getDatabaseType() {
        return config.getString("database.type", "mariadb");
    }

    public String getSqliteFile() {
        return config.getString("database.sqlite.file", "player_data.db");
    }

    public String getMysqlHost() {
        return config.getString("database.mysql.host", "localhost");
    }

    public int getMysqlPort() {
        return config.getInt("database.mysql.port", 3306);
    }

    public String getMysqlDatabase() {
        return config.getString("database.mysql.database", "shinventorypages");
    }

    public String getMysqlUsername() {
        return config.getString("database.mysql.username", "root");
    }

    public String getMysqlPassword() {
        return config.getString("database.mysql.password", "password");
    }

    public String getMysqlProperties() {
        return config.getString("database.mysql.properties", "useSSL=false");
    }

    public int getMysqlPoolSize() {
        return Math.max(1, config.getInt("database.mysql.pool_size", 4));
    }

    /** Fils d'exécution de la base (lu au démarrage). */
    public int getDatabaseThreads() {
        return Math.max(1, config.getInt("database.threads", 4));
    }

    // --- Réseau ---
    /** Mode réseau voulu (il ne s'active que si SHNetwork est installé). */
    public boolean isNetworkEnabled() {
        return config.getBoolean("reseau.actif", true);
    }

    /** Attente maximale, en secondes, de la confirmation du serveur de départ. */
    public int getNetworkWaitSeconds() {
        return Math.max(1, config.getInt("reseau.attente-max-secondes", 10));
    }

    /** Attente maximale, en secondes, de la fin de synchronisation de HuskSync. */
    public int getNetworkSyncWaitSeconds() {
        return Math.max(1, config.getInt("reseau.attente-husksync-secondes", 30));
    }

    // --- Inventory ---
    public int getSwitcherSlot() {
        int slot = config.getInt("inventory.switcher_slot", 17);
        if (slot < 0 || slot > 35) {
            plugin.getLogger().warning("Invalid switcher_slot value (" + slot + ") in config.yml. Defaulting to 17.");
            return 17;
        }
        return slot;
    }

    public int getMaxPossiblePages() {
        return Math.max(1, config.getInt("inventory.max_possible_pages", 10));
    }

    public int getDefaultPages() {
        return Math.max(1, config.getInt("inventory.default_pages", 1));
    }

    public int getSwitchCooldownTicks() {
        return Math.max(0, config.getInt("inventory.switch_cooldown_ticks", 4));
    }

    public int getCaptureInterval() {
        return Math.max(0, config.getInt("inventory.capture_interval", 5));
    }

    public int getPermissionCheckInterval() {
        return Math.max(0, config.getInt("inventory.permission_check_interval", 5));
    }

    // --- Overflow ---
    public boolean isOverflowEnabled() {
        return config.getBoolean("overflow.enabled", true);
    }

    public double getOverflowCaptureRadius() {
        return Math.max(0.0, config.getDouble("overflow.capture_radius", 1.5));
    }

    public boolean isCaptureBrokenBlockDrops() {
        return config.getBoolean("overflow.capture_broken_block_drops", true);
    }

    /** Objet sans propriétaire posé par un plugin : distance horizontale maximale aux pieds du joueur. */
    public double getOverflowFeetRadius() {
        return Math.max(0.0, config.getDouble("overflow.feet_radius", 0.75));
    }

    /** Plugins dont les objets posés au sol sont des mécaniques du monde ou des décors, jamais des dons. */
    public List<String> getOverflowIgnoredPlugins() {
        return config.getStringList("overflow.ignored_plugins");
    }

    public double getOverflowLootRadius() {
        return Math.max(0.0, config.getDouble("overflow.loot_radius", 3.0));
    }

    public double getOverflowExplosionRadius() {
        return Math.max(0.0, config.getDouble("overflow.explosion_radius", 10.0));
    }

    public int getOverflowReservationTicks() {
        return Math.max(1, config.getInt("overflow.reservation_ticks", 60));
    }

    public int getDeferredSaveTicks() {
        return Math.max(1, config.getInt("performance.deferred_save_ticks", 40));
    }

    public int getShutdownSaveTimeout() {
        return Math.max(5, config.getInt("performance.shutdown_save_timeout", 30));
    }

    public int getEndOfSessionRetrySeconds() {
        return Math.max(0, config.getInt("performance.end_of_session_retry_seconds", 180));
    }

    public boolean saveVanillaDataOnPageSwitch() {
        return config.getBoolean("performance.save_player_file_on_page_switch", true);
    }

    public int getOverflowNoticeCooldownTicks() {
        return Math.max(0, config.getInt("overflow.notice_cooldown_ticks", 60));
    }

    public Sound getOverflowSound() {
        return getSound("overflow.sound", "ENTITY_ITEM_PICKUP");
    }

    public float getOverflowSoundVolume() {
        return (float) config.getDouble("overflow.sound_volume", 0.5);
    }

    public float getOverflowSoundPitch() {
        return (float) config.getDouble("overflow.sound_pitch", 1.5);
    }

    // --- Switcher Item ---
    public Material getSwitcherItemMaterial() {
        return material("switcher_item.material", Material.PAPER);
    }

    public String getSwitcherItemName() {
        return config.getString("switcher_item.name", "&bPages d'inventaire &7(&b%current_page%&7/&b%max_pages%&7)");
    }

    /** Nom du sélecteur quand la page portée a un nom (%page_name%). */
    public String getSwitcherItemNamedName() {
        return config.getString("switcher_item.name_with_page_name",
            "&bPages d'inventaire &7(&b%current_page%&7/&b%max_pages%&7) &8· &f%page_name%");
    }

    public List<String> getSwitcherItemLore() {
        return config.getStringList("switcher_item.lore");
    }

    public boolean isAmountEqualsPage() {
        return config.getBoolean("switcher_item.amount_equals_page", true);
    }

    public int getFixedAmount() {
        return config.getInt("switcher_item.fixed_amount", 1);
    }

    public boolean hasEnchantGlow() {
        return config.getBoolean("switcher_item.enchant_glow", true);
    }

    /** Effet Cécité tant qu'un dialogue du plugin est affiché. */
    public boolean isBlindDuringDialogs() {
        return config.getBoolean("blind_during_dialogs", true);
    }

    // --- Menu des pages ---
    public boolean isMenuEnabled() {
        return config.getBoolean("menu.enabled", true);
    }

    /** Touches 1 à 9 au survol du sélecteur : page de ce numéro. */
    public boolean isMenuNumberKeys() {
        return config.getBoolean("menu.number_keys", true);
    }

    public boolean isMenuShowLockedPages() {
        return config.getBoolean("menu.show_locked_pages", true);
    }

    /** Icône et page réservée réglables par le joueur. */
    public boolean isMenuCustomization() {
        return config.getBoolean("menu.customization", true);
    }

    /** Objets les plus nombreux d'une page listés dans sa description ; 0 = aucun. */
    public int getMenuPreviewItems() {
        return Math.max(0, config.getInt("menu.preview_items", 3));
    }

    /** Longueur maximale du nom d'une page. */
    public int getMenuNameMaxLength() {
        return Math.max(1, Math.min(48, config.getInt("menu.name_max_length", 24)));
    }

    /** Version de protocole à partir de laquelle un client connaît les dialogues (1.21.6 : 771). */
    public int getMenuDialogMinProtocol() {
        return config.getInt("menu.dialog_min_protocol", 771);
    }

    /** Objet d'un élément du menu (menu.materials.&lt;name&gt;). */
    public Material getMenuMaterial(String name, Material fallback) {
        return material("menu.materials." + name, fallback);
    }

    /** Son d'une action du menu (menu.sounds.&lt;name&gt;), ou null si les sons sont coupés. */
    public Sound getMenuSound(String name, String fallback) {
        return soundsEnabled() ? getSound("menu.sounds." + name, fallback) : null;
    }

    // --- Blacklist ---
    public List<String> getBlacklistedWorlds() {
        return config.getStringList("blacklist.worlds");
    }

    // --- Barrier Item ---
    public Material getBarrierItemMaterial() {
        return material("barrier_item.material", Material.BARRIER);
    }

    private Material material(String path, Material fallback) {
        String name = config.getString(path, fallback.name());
        Material material = name == null ? null : Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            plugin.getLogger().warning("Invalid material '" + name + "' at '" + path + "' in config.yml. Using " + fallback + ".");
            return fallback;
        }
        return material;
    }

    public String getBarrierItemName() {
        return config.getString("barrier_item.name", "&cPages d'inventaire");
    }

    public List<String> getBarrierItemLore() {
        return config.getStringList("barrier_item.lore");
    }

    // --- Sounds ---
    public boolean soundsEnabled() {
        return config.getBoolean("sounds.enabled", true);
    }

    public Sound getSwitchPageSound() {
        return getSound("sounds.switch_page", "UI_BUTTON_CLICK");
    }

    public Sound getSwitchFailSound() {
        return getSound("sounds.switch_fail", "BLOCK_ANVIL_LAND");
    }

    public float getSoundVolume() {
        return (float) config.getDouble("sounds.volume", 0.8);
    }

    public float getSoundPitch() {
        return (float) config.getDouble("sounds.pitch", 1.2);
    }

    private Sound getSound(String path, String defaultSound) {
        String soundName = config.getString(path, defaultSound);
        if (soundName == null || soundName.isEmpty()) {
            return null; // Sound disabled
        }
        Sound sound = sound(soundName);
        if (sound != null) {
            return sound;
        }
        plugin.getLogger().warning("Invalid sound name '" + soundName + "' at path '" + path + "' in config.yml. Using default: " + defaultSound);
        sound = sound(defaultSound);
        if (sound == null) {
            plugin.getLogger().severe("Default sound name '" + defaultSound + "' is also invalid! Disabling sound for " + path);
        }
        return sound; // null si les deux sont invalides
    }

    /** Son lu en config : ancien nom (UI_BUTTON_CLICK) ou clé (minecraft:ui.button.click). Sound.valueOf est retiré en 26.x. */
    static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (Sound) Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    // --- Death ---
    public InventoryManager.DeathMode getDeathDropMode() {
        String raw = config.getString("death.drop_mode", "CURRENT");
        try {
            return InventoryManager.DeathMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid death.drop_mode '" + raw + "' in config.yml. Using CURRENT.");
            return InventoryManager.DeathMode.CURRENT;
        }
    }

    // --- Performance ---
    public int getAutoSaveInterval() {
        return config.getInt("performance.auto_save_interval", 300);
    }

    public boolean saveOnPageSwitch() {
        return config.getBoolean("performance.save_on_page_switch", true);
    }

    // --- Messages ---
    public String getPluginPrefix() {
        return messages.getString("prefix", "");
    }

    /**
     * Message de messages.yml colorisé (codes §), placeholders remplacés. Les remplacements vont par paires :
     * getMessage("cle", "%player%", nom, "%page%", "2").
     */
    public String getMessage(String key, String... replacements) {
        String message = messages.getString(key);
        if (message == null) {
            plugin.getLogger().warning("Message manquant dans messages.yml : " + key);
            return "Missing message: " + key;
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            if (replacements[i] != null && replacements[i + 1] != null) {
                message = message.replace(replacements[i], replacements[i + 1]);
            }
        }
        return MessageUtil.colorize(message);
    }

    /** Ligne de texte de messages.yml telle qu'écrite (codes &), pour un nom ou une description d'objet. */
    public String getRawMessage(String key) {
        // Sans valeur explicite : celle du messages.yml du jar quand le fichier du serveur n'a pas encore la clé
        String text = messages.getString(key);
        return text != null ? text : key;
    }

    public List<String> getRawMessageList(String key) {
        return messages.getStringList(key);
    }

    public boolean isPapiEnabled() {
        return config.getBoolean("placeholders.enabled", true);
    }

    // --- Ramassage vers les pages ---
    public boolean isPickupIntoPages() {
        return config.getBoolean("overflow.pickup_into_pages", true);
    }

    // --- Historique des pages ---
    public boolean isHistoryEnabled() {
        return config.getBoolean("history.enabled", true);
    }

    /** Copies gardées par joueur pour un moment donné (join, quit, death, periodic, operation, restore) ; 0 = aucune. */
    public int getHistoryMaxCopies(String moment) {
        return Math.max(0, config.getInt("history.max_copies." + moment, 18));
    }

    public int getHistoryPeriodicMinutes() {
        return Math.max(0, config.getInt("history.periodic_minutes", 30));
    }

    public int getHistoryRetentionDays() {
        return Math.max(1, config.getInt("history.retention_days", 14));
    }

    /** Objet d'une copie dans la liste de l'historique, selon son moment. */
    public String getHistoryIcon(String moment) {
        // Sans valeur explicite : celle du config.yml du jar quand le fichier du serveur n'a pas encore la clé
        String icon = config.getString("history.icons." + moment);
        return icon != null ? icon : "PAPER";
    }

    public String getHistoryDateFormat() {
        return config.getString("history.date_format", "dd/MM/yyyy HH:mm:ss");
    }
}
