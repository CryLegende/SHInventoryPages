package org.Cry_Legende.sHInventoryPages.config;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList; // AJOUT
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.stream.Collectors; // AJOUT

public class ConfigManager {

    private final SHInventoryPages plugin;
    private FileConfiguration config;

    public ConfigManager(SHInventoryPages plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.saveDefaultConfig(); // Creates config.yml if it doesn't exist
        plugin.reloadConfig();      // Reloads the config from disk
        this.config = plugin.getConfig();
        MessageUtil.setPluginPrefix(getPluginPrefix()); // Ensure prefix is set on load/reload
    }

    // --- Database ---
    public String getDatabaseType() {
        return config.getString("database.type", "sqlite");
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

    // --- Inventory ---
    public int getSwitcherSlot() {
        int slot = config.getInt("inventory.switcher_slot", 8);
        if (slot < 0 || slot > 35) {
            plugin.getLogger().warning("Invalid switcher_slot value (" + slot + ") in config.yml. Defaulting to 8.");
            return 8;
        }
        return slot;
    }

    public int getMaxPossiblePages() {
        return config.getInt("inventory.max_possible_pages", 10);
    }

    public int getDefaultPages() {
        return config.getInt("inventory.default_pages", 1);
    }

    public boolean preventSwitcherDrop() {
        return config.getBoolean("inventory.prevent_switcher_drop", true);
    }

    public boolean preventSwitcherMove() {
        return config.getBoolean("inventory.prevent_switcher_move", true);
    }

    // --- Switcher Item ---
    public Material getSwitcherItemMaterial() {
        try {
            return Material.valueOf(config.getString("switcher_item.material", "PAPER").toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid switcher_item material in config.yml. Defaulting to PAPER.");
            return Material.PAPER;
        }
    }

    public String getSwitcherItemName() {
        return config.getString("switcher_item.name", "&bPage Switcher (&e%current_page%&b/&e%max_pages%&b)");
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
        return config.getBoolean("switcher_item.enchant_glow", false);
    }

    // --- Blacklist ---
    public List<String> getBlacklistedWorlds() {
        return config.getStringList("blacklist.worlds");
    }

    // --- Barrier Item ---
    public Material getBarrierItemMaterial() {
        // Note: Le matériel est fixe (BARRIER), pas configurable ici
        return Material.BARRIER;
    }

    public String getBarrierItemName() {
        return config.getString("barrier_item.name", "&cInterdit de changer d'inventaire ici");
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
    public String getDeathDropMode() {
        return config.getString("death.drop_mode", "CURRENT").toUpperCase();
    }
    // public boolean combineAllPagesDrop() { return config.getBoolean("death.combine_all_pages_drop", true); } // Non utilisée apparemment

    // --- Performance ---
    public int getAutoSaveInterval() {
        return config.getInt("performance.auto_save_interval", 300);
    }

    public boolean saveOnPageSwitch() {
        return config.getBoolean("performance.save_on_page_switch", true);
    }

    // --- Messages ---
    public String getPluginPrefix() {
        return config.getString("messages.prefix", "&8[&bSHInvPages&8] &r");
    }

    /**
     * Gets a message string from the config, colorizes it, and replaces placeholders.
     * Placeholders must be provided in pairs: placeholder, replacement.
     * Example: getMessage("my_message", "%player%", player.getName(), "%value%", "123")
     */
    public String getMessage(String key, String... replacements) {
        String message = config.getString("messages." + key);
        if (message == null) {
            plugin.getLogger().warning("Missing message key in config.yml: messages." + key);
            return "Missing message: " + key;
        }
        if (replacements.length > 0 && replacements.length % 2 == 0) {
            for (int i = 0; i < replacements.length; i += 2) {
                if (replacements[i] != null && replacements[i + 1] != null) {
                    message = message.replace(replacements[i], replacements[i + 1]);
                }
            }
        }
        return MessageUtil.colorize(message);
    }

    // --- NOUVELLE MÉTHODE ---

    /**
     * Gets a list of strings from the config for the specified key.
     * Returns an empty list if the key is not found or not a list.
     * Automatically colorizes each line.
     */
    public List<String> getMessageList(String key) {
        List<String> list = config.getStringList("messages." + key);
        if (list == null || list.isEmpty()) {
            // Ne pas logguer d'avertissement ici, car une liste vide est valide (par exemple pour un lore vide)
            // plugin.getLogger().warning("Missing or empty message list key in config.yml: messages." + key);
            return new ArrayList<>(); // Retourner une liste vide mutable
        }
        // Colorize each line
        return list.stream()
            .map(MessageUtil::colorize)
            .collect(Collectors.toList());
    }
    // ------------------------

    // --- PlaceholderAPI ---
    public boolean isPapiEnabled() {
        return config.getBoolean("placeholders.enabled", true);
    }
}
