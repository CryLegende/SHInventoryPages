package org.Cry_Legende.sHInventoryPages.database;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.Cry_Legende.sHInventoryPages.util.ItemSerializer;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Level;

public class SQLiteManager implements DatabaseManager {

    private final SHInventoryPages plugin;
    private Connection connection;
    private final String dbPath;
    private final Object DBLOCK = new Object(); // Pour synchroniser les opérations DB

    public SQLiteManager(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.dbPath = new File(plugin.getDataFolder(), plugin.getConfigManager().getSqliteFile()).getAbsolutePath();
    }

    @Override
    public boolean connect() {
        synchronized (DBLOCK) {
            try {
                if (connection != null && !connection.isClosed()) return true;
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                // Mettre PRAGMA journal_mode=WAL; pour de meilleures performances concurrentes si nécessaire,
                // mais nécessite une gestion attentive. Pour l'instant, on garde simple.
                // try (Statement stmt = connection.createStatement()) {
                //     stmt.execute("PRAGMA foreign_keys = ON;");
                //     stmt.execute("PRAGMA journal_mode = WAL;"); // Write-Ahead Logging
                // }
                connection.setAutoCommit(true); // Default
                plugin.getLogger().info("SQLite connection established.");
                return true;
            } catch (SQLException | ClassNotFoundException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not connect to SQLite database!", e);
                return false;
            }
        }
    }

    @Override
    public void disconnect() {
        synchronized (DBLOCK) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                    plugin.getLogger().info("SQLite connection closed.");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Error closing SQLite connection!", e);
            }
        }
    }

    private Connection getConnection() throws SQLException {
        // Pas besoin de DBLOCK ici, car les méthodes 'do...' qui l'utilisent sont déjà synchronisées sur DBLOCK.
        // Cependant, pour la cohérence et la sécurité si cette méthode est appelée ailleurs :
        synchronized (DBLOCK) {
            if (connection == null || connection.isClosed()) {
                if (!connect()) { // 'connect' est synchronisé et gère la connexion
                    throw new SQLException("SQLite connection is closed and could not be re-established.");
                }
            }
            return connection;
        }
    }

    @Override
    public boolean createTables() {
        String createInventoryTable = "CREATE TABLE IF NOT EXISTS player_inventory_pages (" +
            "uuid VARCHAR(36) NOT NULL, page_number INTEGER NOT NULL, inventory_data TEXT, " +
            "PRIMARY KEY (uuid, page_number));";
        String createEquipmentTable = "CREATE TABLE IF NOT EXISTS player_equipment (" +
            "uuid VARCHAR(36) PRIMARY KEY, helmet TEXT, chestplate TEXT, leggings TEXT, boots TEXT, off_hand TEXT);";

        synchronized (DBLOCK) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute(createInventoryTable);
                stmt.execute(createEquipmentTable);
                plugin.getLogger().info("Base tables 'player_inventory_pages' and 'player_equipment' checked/created.");

                if (!columnExistsSQLite(conn, "player_equipment", "currentPage")) {
                    plugin.getLogger().info("Column 'currentPage' not found in 'player_equipment'. Adding it now...");
                    String addCurrentPageColumn = "ALTER TABLE player_equipment ADD COLUMN currentPage INTEGER DEFAULT 1;";
                    stmt.executeUpdate(addCurrentPageColumn);
                    plugin.getLogger().info("Column 'currentPage' added successfully to 'player_equipment' with default value 1.");
                } else {
                    plugin.getLogger().fine("Column 'currentPage' already exists in 'player_equipment'.");
                }
                plugin.getLogger().info("SQLite tables schema verified/updated successfully.");
                return true;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create/update SQLite tables schema!", e);
                return false;
            }
        }
    }

    private boolean columnExistsSQLite(Connection connection, String tableName, String columnName) throws SQLException {
        // Pas besoin de DBLOCK ici car appelé depuis createTables qui est synchronisé
        String sql = "PRAGMA table_info(\"" + tableName + "\");"; // Table name might need quotes
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                if (rs.getString("name").equalsIgnoreCase(columnName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Executor getExecutor() {
        if (plugin.isEnabled()) {
            return r -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, r);
        } else {
            plugin.getLogger().fine("Plugin is disabled, using ForkJoinPool.commonPool() for async task execution if not handled by a Blocking method.");
            return ForkJoinPool.commonPool();
        }
    }

    private ItemStack deserializeItem(String base64) throws IOException {
        if (base64 == null || base64.isEmpty()) return null;
        return ItemSerializer.itemStackFromBase64(base64);
    }

    private String serializeItem(ItemStack item) {
        if (item == null) return null;
        try {
            return ItemSerializer.itemStackToBase64(item);
        } catch (IllegalStateException e) {
            plugin.getLogger().log(Level.SEVERE, "-----------------------------------------------------");
            plugin.getLogger().log(Level.SEVERE, "SKIPPING DANGEROUS ITEM SERIALIZATION (SQLite)!");
            plugin.getLogger().log(Level.SEVERE, "Item Type: " + item.getType());
            if (item.hasItemMeta()) {
                plugin.getLogger().log(Level.SEVERE, "Item Name: " + item.getItemMeta().getDisplayName());
            }
            plugin.getLogger().log(Level.SEVERE, "This item will NOT be saved to the database to prevent data corruption.", e);
            plugin.getLogger().log(Level.SEVERE, "-----------------------------------------------------");
            return null; // On retourne null, l'item ne sera pas sauvegardé mais le reste des données le sera.
        }
    }

    // --- Méthodes privées 'do...' pour la logique de DB (bloquantes) ---

    private Map<Integer, ItemStack[]> doLoadPlayerInventories(UUID uuid) throws SQLException, IOException {
        Map<Integer, ItemStack[]> inventories = new HashMap<>();
        String sql = "SELECT page_number, inventory_data FROM player_inventory_pages WHERE uuid = ?;";
        synchronized (DBLOCK) {
            try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    int page = rs.getInt("page_number");
                    String base64Data = rs.getString("inventory_data");
                    if (base64Data != null && !base64Data.isEmpty()) {
                        inventories.put(page, ItemSerializer.itemStackArrayFromBase64(base64Data));
                    } else {
                        inventories.put(page, plugin.getInventoryManager().createEmptyInventory());
                    }
                }
            }
        }
        return inventories;
    }

    private PlayerInventoryData doLoadPlayerInventoriesAndEquipment(UUID uuid) throws SQLException, IOException {
        int initialMaxPages = plugin.getConfigManager().getMaxPossiblePages();
        PlayerInventoryData playerData = new PlayerInventoryData(uuid, initialMaxPages);

        Map<Integer, ItemStack[]> pages = doLoadPlayerInventories(uuid);
        pages.forEach(playerData::setPage);

        String equipSql = "SELECT helmet, chestplate, leggings, boots, off_hand, currentPage FROM player_equipment WHERE uuid = ?;";
        synchronized (DBLOCK) {
            try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(equipSql)) {
                pstmt.setString(1, uuid.toString());
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    playerData.setHelmet(deserializeItem(rs.getString("helmet")));
                    playerData.setChestplate(deserializeItem(rs.getString("chestplate")));
                    playerData.setLeggings(deserializeItem(rs.getString("leggings")));
                    playerData.setBoots(deserializeItem(rs.getString("boots")));
                    playerData.setOffHand(deserializeItem(rs.getString("off_hand")));
                    int loadedPage = rs.getInt("currentPage");
                    playerData.setCurrentPage(loadedPage > 0 ? loadedPage : 1);
                } else {
                    playerData.setCurrentPage(1);
                }
            }
        }
        return playerData;
    }

    private void doSaveFullPlayerInventoryData(PlayerInventoryData data) throws SQLException {
        UUID uuid = data.getUuid();
        synchronized (DBLOCK) {
            try (Connection conn = getConnection()) {
                boolean autoCommitOriginal = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    String deletePagesSql = "DELETE FROM player_inventory_pages WHERE uuid = ?;";
                    try (PreparedStatement pstmt = conn.prepareStatement(deletePagesSql)) {
                        pstmt.setString(1, uuid.toString());
                        pstmt.executeUpdate();
                    }

                    String insertPageSql = "INSERT INTO player_inventory_pages (uuid, page_number, inventory_data) VALUES (?, ?, ?);";
                    try (PreparedStatement pstmt = conn.prepareStatement(insertPageSql)) {
                        for (Map.Entry<Integer, ItemStack[]> entry : data.getAllPages().entrySet()) {
                            int pageNumber = entry.getKey();
                            ItemStack[] contents = entry.getValue();
                            if (contents == null) continue;
                            String base64Data = ItemSerializer.itemStackArrayToBase64(contents); // Peut lever IllegalStateException
                            pstmt.setString(1, uuid.toString());
                            pstmt.setInt(2, pageNumber);
                            pstmt.setString(3, base64Data);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                    }

                    String equipSql = "INSERT OR REPLACE INTO player_equipment (uuid, helmet, chestplate, leggings, boots, off_hand, currentPage) VALUES (?, ?, ?, ?, ?, ?, ?);";
                    try (PreparedStatement pstmt = conn.prepareStatement(equipSql)) {
                        pstmt.setString(1, uuid.toString());
                        pstmt.setString(2, serializeItem(data.getHelmet()));
                        pstmt.setString(3, serializeItem(data.getChestplate()));
                        pstmt.setString(4, serializeItem(data.getLeggings()));
                        pstmt.setString(5, serializeItem(data.getBoots()));
                        pstmt.setString(6, serializeItem(data.getOffHand()));
                        pstmt.setInt(7, data.getCurrentPage());
                        pstmt.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException | IllegalStateException e) {
                    plugin.getLogger().log(Level.WARNING, "Rolling back SQLite transaction for " + uuid + " due to error: " + e.getMessage(), e);
                    if (!conn.getAutoCommit()) conn.rollback();
                    throw new SQLException("Failed during SQLite player data save operation for " + uuid, e);
                } finally {
                    conn.setAutoCommit(autoCommitOriginal);
                }
            }
        }
    }

    private void doSavePlayerInventoryPage(UUID uuid, int pageNumber, ItemStack[] contents) throws SQLException {
        String base64Data = ItemSerializer.itemStackArrayToBase64(contents); // Peut lever IllegalStateException
        String sql = "INSERT OR REPLACE INTO player_inventory_pages (uuid, page_number, inventory_data) VALUES (?, ?, ?);";
        synchronized (DBLOCK) {
            try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setInt(2, pageNumber);
                pstmt.setString(3, base64Data);
                pstmt.executeUpdate();
            }
        }
    }

    private void doDeletePlayerInventoryPage(UUID uuid, int pageNumber) throws SQLException {
        String sql = "DELETE FROM player_inventory_pages WHERE uuid = ? AND page_number = ?;";
        synchronized (DBLOCK) {
            try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setInt(2, pageNumber);
                pstmt.executeUpdate();
            }
        }
    }

    private void doDeleteAllPlayerData(UUID uuid) throws SQLException {
        String invSql = "DELETE FROM player_inventory_pages WHERE uuid = ?;";
        String equipSql = "DELETE FROM player_equipment WHERE uuid = ?;";
        synchronized (DBLOCK) {
            try (Connection conn = getConnection()) {
                boolean autoCommitOriginal = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (PreparedStatement invStmt = conn.prepareStatement(invSql);
                     PreparedStatement equipStmt = conn.prepareStatement(equipSql)) {
                    invStmt.setString(1, uuid.toString());
                    equipStmt.setString(1, uuid.toString());
                    invStmt.executeUpdate();
                    equipStmt.executeUpdate();
                    conn.commit();
                } catch (SQLException e) {
                    if (!conn.getAutoCommit()) conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(autoCommitOriginal);
                }
            }
        }
    }

    // --- Méthodes publiques de l'interface ---

    // Méthodes CompletableFuture (pour usage asynchrone normal)
    @Override
    public CompletableFuture<Map<Integer, ItemStack[]>> loadPlayerInventories(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doLoadPlayerInventories(uuid);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Async SQLite load of inventories failed for " + uuid, e);
                throw new CompletionException(e);
            }
        }, getExecutor());
    }

    @Override
    public CompletableFuture<PlayerInventoryData> loadPlayerInventoriesAndEquipment(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doLoadPlayerInventoriesAndEquipment(uuid);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Async SQLite load of full data failed for " + uuid, e);
                throw new CompletionException(e);
            }
        }, getExecutor());
    }

    @Override
    public CompletableFuture<Void> saveFullPlayerInventoryData(PlayerInventoryData data) {
        return CompletableFuture.runAsync(() -> {
            try {
                //plugin.getLogger().fine("[Async SQLite] Saving full player data for " + data.getUuid());
                doSaveFullPlayerInventoryData(data);
            } catch (Exception e) {
                //plugin.getLogger().log(Level.SEVERE, "Async SQLite save of full data failed for " + data.getUuid(), e);
                throw new CompletionException(e);
            }
        }, getExecutor());
    }

    @Override
    public CompletableFuture<Void> savePlayerInventoryPage(UUID uuid, int pageNumber, ItemStack[] contents) {
        return CompletableFuture.runAsync(() -> {
            try {
                doSavePlayerInventoryPage(uuid, pageNumber, contents);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Async SQLite save of page " + pageNumber + " for " + uuid + " failed.", e);
                throw new CompletionException(e);
            }
        }, getExecutor());
    }

    @Override
    public CompletableFuture<Void> saveAllPlayerPages(UUID uuid, Map<Integer, ItemStack[]> pageData) {
        return CompletableFuture.runAsync(() -> {
            synchronized (DBLOCK) {
                try (Connection conn = getConnection()) {
                    boolean autoCommitOriginal = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try {
                        for (Map.Entry<Integer, ItemStack[]> entry : pageData.entrySet()) {
                            String base64Data = ItemSerializer.itemStackArrayToBase64(entry.getValue());
                            String sql = "INSERT OR REPLACE INTO player_inventory_pages (uuid, page_number, inventory_data) VALUES (?, ?, ?);";
                            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                                pstmt.setString(1, uuid.toString());
                                pstmt.setInt(2, entry.getKey());
                                pstmt.setString(3, base64Data);
                                pstmt.executeUpdate(); // Pourrait être addBatch
                            }
                        }
                        conn.commit();
                    } catch (SQLException | IllegalStateException e) {
                        if (!conn.getAutoCommit()) conn.rollback();
                        throw new CompletionException(e);
                    } finally {
                        conn.setAutoCommit(autoCommitOriginal);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to manage SQLite transaction for saveAllPlayerPages for " + uuid, e);
                    throw new CompletionException(e);
                }
            }
        }, getExecutor());
    }

    @Override
    public CompletableFuture<Void> deletePlayerInventoryPage(UUID uuid, int pageNumber) {
        return CompletableFuture.runAsync(() -> {
            try {
                doDeletePlayerInventoryPage(uuid, pageNumber);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Async SQLite delete of page " + pageNumber + " for " + uuid + " failed.", e);
                throw new CompletionException(e);
            }
        }, getExecutor());
    }

    @Override
    public CompletableFuture<Void> deleteAllPlayerData(UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            try {
                doDeleteAllPlayerData(uuid);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Async SQLite delete of all data for " + uuid + " failed.", e);
                throw new CompletionException(e);
            }
        }, getExecutor());
    }

    // Méthodes ...Blocking (pour usage synchrone, typiquement onDisable)
    @Override
    public void saveFullPlayerInventoryDataBlocking(PlayerInventoryData data) throws SQLException {
        //plugin.getLogger().fine("[Blocking SQLite] Saving full player data for " + data.getUuid());
        doSaveFullPlayerInventoryData(data);
    }
}
