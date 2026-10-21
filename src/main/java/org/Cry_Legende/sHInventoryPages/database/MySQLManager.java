package org.Cry_Legende.sHInventoryPages.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.Cry_Legende.sHInventoryPages.util.ItemSerializer;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

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

public class MySQLManager implements DatabaseManager {

    private final SHInventoryPages plugin;
    private HikariDataSource dataSource;

    public MySQLManager(SHInventoryPages plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean connect() {
        try {
            var cfg = plugin.getConfigManager();
            HikariConfig config = new HikariConfig();
            String jdbcUrl;
            String driverClass;
            try {
                Class.forName("org.mariadb.jdbc.Driver");
                driverClass = "org.mariadb.jdbc.Driver";
                jdbcUrl = "jdbc:mariadb://" + cfg.getMysqlHost() + ":" + cfg.getMysqlPort() + "/" + cfg.getMysqlDatabase() + "?" + cfg.getMysqlProperties();
                plugin.getLogger().info("Using MariaDB JDBC driver.");
            } catch (ClassNotFoundException ex) {
                Class.forName("com.mysql.cj.jdbc.Driver");
                driverClass = "com.mysql.cj.jdbc.Driver";
                jdbcUrl = "jdbc:mysql://" + cfg.getMysqlHost() + ":" + cfg.getMysqlPort() + "/" + cfg.getMysqlDatabase() + "?" + cfg.getMysqlProperties();
                plugin.getLogger().info("Using MySQL JDBC driver.");
            }
            config.setDriverClassName(driverClass);
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(cfg.getMysqlUsername());
            config.setPassword(cfg.getMysqlPassword());
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            config.setIdleTimeout(300_000);
            config.setConnectionTimeout(30_000);
            config.setMaxLifetime(1_800_000);
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            this.dataSource = new HikariDataSource(config);
            plugin.getLogger().info("MySQL/MariaDB connection pool established.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not create MySQL/MariaDB connection pool!", e);
            return false;
        }
    }

    @Override
    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("MySQL/MariaDB connection pool closed.");
        }
    }

    private Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            plugin.getLogger().warning("DataSource was null or closed. Attempting to reconnect...");
            if (!connect()) {
                throw new SQLException("DataSource not initialized or connection failed.");
            }
            if (dataSource == null) {
                throw new SQLException("DataSource remains null after attempting reconnection.");
            }
        }
        return dataSource.getConnection();
    }


    @Override
    public boolean createTables() {
        String invTable = "CREATE TABLE IF NOT EXISTS `player_inventory_pages` ("
            + "`uuid` VARCHAR(36) NOT NULL,"
            + "`page_number` INT NOT NULL,"
            + "`inventory_data` LONGTEXT,"
            + "PRIMARY KEY(`uuid`,`page_number`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        String equipTable = "CREATE TABLE IF NOT EXISTS `player_equipment` ("
            + "`uuid` VARCHAR(36) NOT NULL PRIMARY KEY,"
            + "`helmet` TEXT, `chestplate` TEXT, `leggings` TEXT, `boots` TEXT, `off_hand` TEXT"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(invTable);
            stmt.execute(equipTable);
            plugin.getLogger().info("Base tables 'player_inventory_pages' and 'player_equipment' checked/created.");

            if (!columnExists(conn, plugin.getConfigManager().getMysqlDatabase(), "player_equipment", "currentPage")) {
                plugin.getLogger().info("Column 'currentPage' not found in 'player_equipment'. Adding it now...");
                String addCurrentPageColumn = "ALTER TABLE `player_equipment` ADD COLUMN `currentPage` INT DEFAULT 1;";
                stmt.executeUpdate(addCurrentPageColumn);
                plugin.getLogger().info("Column 'currentPage' added successfully to 'player_equipment' with default value 1.");
            } else {
                plugin.getLogger().fine("Column 'currentPage' already exists in 'player_equipment'.");
            }
            plugin.getLogger().info("MySQL/MariaDB tables schema verified/updated successfully.");
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not create/update MySQL/MariaDB tables schema!", e);
            return false;
        }
    }

    private boolean columnExists(Connection connection, String catalog, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getColumns(catalog, null, tableName, columnName)) {
            return rs.next();
        }
    }

    private Executor getExecutor() {
        if (plugin.isEnabled()) {
            return r -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, r);
        } else {
            // Ce cas est pour les CompletableFuture qui pourraient être créés par d'autres moyens
            // en dehors de onDisable. Pour onDisable, on utilise les méthodes "...Blocking".
            plugin.getLogger().fine("Plugin is disabled, using ForkJoinPool.commonPool() for async task execution if not handled by a Blocking method.");
            return ForkJoinPool.commonPool();
        }
    }

    private ItemStack deserialize(String base64) throws IOException { // Rendre IOException explicite
        if (base64 == null || base64.isEmpty()) return null;
        return ItemSerializer.itemStackFromBase64(base64);
    }

    private String serialize(ItemStack item) {
        if (item == null) return null;
        try {
            return ItemSerializer.itemStackToBase64(item);
        } catch (IllegalStateException e) {
            plugin.getLogger().log(Level.SEVERE, "-----------------------------------------------------");
            plugin.getLogger().log(Level.SEVERE, "SKIPPING DANGEROUS ITEM SERIALIZATION!");
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
        Map<Integer, ItemStack[]> invs = new HashMap<>();
        String sql = "SELECT page_number, inventory_data FROM player_inventory_pages WHERE uuid=?;";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int page = rs.getInt("page_number");
                String data = rs.getString("inventory_data");
                if (data != null && !data.isEmpty()) {
                    invs.put(page, ItemSerializer.itemStackArrayFromBase64(data));
                } else {
                    invs.put(page, plugin.getInventoryManager().createEmptyInventory());
                }
            }
        }
        return invs;
    }

    private PlayerInventoryData doLoadPlayerInventoriesAndEquipment(UUID uuid) throws SQLException, IOException {
        int initialMaxPages = plugin.getConfigManager().getMaxPossiblePages();
        PlayerInventoryData data = new PlayerInventoryData(uuid, initialMaxPages);

        Map<Integer, ItemStack[]> pages = doLoadPlayerInventories(uuid);
        pages.forEach(data::setPage);

        String sql = "SELECT helmet, chestplate, leggings, boots, off_hand, currentPage FROM player_equipment WHERE uuid=?;";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                data.setHelmet(deserialize(rs.getString("helmet")));
                data.setChestplate(deserialize(rs.getString("chestplate")));
                data.setLeggings(deserialize(rs.getString("leggings")));
                data.setBoots(deserialize(rs.getString("boots")));
                data.setOffHand(deserialize(rs.getString("off_hand")));
                int loadedPage = rs.getInt("currentPage");
                data.setCurrentPage(loadedPage > 0 ? loadedPage : 1);
            } else {
                data.setCurrentPage(1);
            }
        }
        return data;
    }

    private void doSaveFullPlayerInventoryData(PlayerInventoryData data) throws SQLException {
        UUID uuid = data.getUuid();
        try (Connection conn = getConnection()) {
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

                String equipSql = "INSERT INTO player_equipment (uuid, helmet, chestplate, leggings, boots, off_hand, currentPage) VALUES (?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE helmet=VALUES(helmet), chestplate=VALUES(chestplate), leggings=VALUES(leggings), boots=VALUES(boots), off_hand=VALUES(off_hand), currentPage=VALUES(currentPage);";
                try (PreparedStatement pstmt = conn.prepareStatement(equipSql)) {
                    pstmt.setString(1, uuid.toString());
                    pstmt.setString(2, serialize(data.getHelmet()));
                    pstmt.setString(3, serialize(data.getChestplate()));
                    pstmt.setString(4, serialize(data.getLeggings()));
                    pstmt.setString(5, serialize(data.getBoots()));
                    pstmt.setString(6, serialize(data.getOffHand()));
                    pstmt.setInt(7, data.getCurrentPage());
                    pstmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException | IllegalStateException e) { // Attraper aussi IllegalStateException de ItemSerializer
                plugin.getLogger().log(Level.WARNING, "Rolling back transaction for " + uuid + " due to error: " + e.getMessage(), e);
                if (!conn.getAutoCommit()) conn.rollback();
                throw new SQLException("Failed during player data save operation for " + uuid, e); // Renvoyer comme SQLException
            } finally {
                if (!conn.getAutoCommit()) conn.setAutoCommit(true);
            }
        }
    }

    private void doSavePlayerInventoryPage(UUID uuid, int pageNumber, ItemStack[] contents) throws SQLException {
        String dataStr = ItemSerializer.itemStackArrayToBase64(contents); // Peut lever IllegalStateException
        String sql = "INSERT INTO player_inventory_pages(uuid,page_number,inventory_data) VALUES(?,?,?) "
            + "ON DUPLICATE KEY UPDATE inventory_data=VALUES(inventory_data);";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, pageNumber);
            ps.setString(3, dataStr);
            ps.executeUpdate();
        }
    }

    private void doDeletePlayerInventoryPage(UUID uuid, int pageNumber) throws SQLException {
        String sql = "DELETE FROM player_inventory_pages WHERE uuid=? AND page_number=?;";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, pageNumber);
            ps.executeUpdate();
        }
    }

    private void doDeleteAllPlayerData(UUID uuid) throws SQLException {
        String invSql = "DELETE FROM player_inventory_pages WHERE uuid=?;";
        String equipSql = "DELETE FROM player_equipment WHERE uuid=?;";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps1 = conn.prepareStatement(invSql);
                 PreparedStatement ps2 = conn.prepareStatement(equipSql)) {
                ps1.setString(1, uuid.toString());
                ps1.executeUpdate();
                ps2.setString(1, uuid.toString());
                ps2.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                if (!conn.getAutoCommit()) conn.rollback();
                throw e;
            } finally {
                if (!conn.getAutoCommit()) conn.setAutoCommit(true);
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
                plugin.getLogger().log(Level.SEVERE, "Async load of inventories failed for " + uuid, e);
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
                plugin.getLogger().log(Level.SEVERE, "Async load of full data failed for " + uuid, e);
                throw new CompletionException(e);
            }
        }, getExecutor());
    }

    @Override
    public CompletableFuture<Void> saveFullPlayerInventoryData(PlayerInventoryData data) {
        return CompletableFuture.runAsync(() -> {
            try {
                //plugin.getLogger().fine("[Async] Saving full player data for " + data.getUuid()); // Loggué par InventoryManager
                doSaveFullPlayerInventoryData(data);
            } catch (Exception e) {
                // Erreur déjà loguée par doSaveFullPlayerInventoryData si SQLException, ou par InventoryManager
                // plugin.getLogger().log(Level.SEVERE, "Async save of full data failed for " + data.getUuid(), e);
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
                plugin.getLogger().log(Level.SEVERE, "Async save of page " + pageNumber + " for " + uuid + " failed.", e);
                throw new CompletionException(e);
            }
        }, getExecutor());
    }

    @Override
    public CompletableFuture<Void> saveAllPlayerPages(UUID uuid, Map<Integer, ItemStack[]> pageData) {
        return CompletableFuture.runAsync(() -> {
            // Cette implémentation de saveAllPlayerPages est simple et appelle savePlayerInventoryPage en boucle.
            // Pour de meilleures performances avec MySQL, une vraie opération de batch dans doSaveAllPlayerPages serait préférable.
            // Mais pour l'instant, cela utilise la logique existante.
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    for (Map.Entry<Integer, ItemStack[]> entry : pageData.entrySet()) {
                        // Appel direct à la logique interne de doSavePlayerInventoryPage pour batching
                        String dataStr = ItemSerializer.itemStackArrayToBase64(entry.getValue());
                        String sql = "INSERT INTO player_inventory_pages(uuid,page_number,inventory_data) VALUES(?,?,?) "
                            + "ON DUPLICATE KEY UPDATE inventory_data=VALUES(inventory_data);";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setString(1, uuid.toString());
                            ps.setInt(2, entry.getKey());
                            ps.setString(3, dataStr);
                            // Idéalement, ce serait ps.addBatch() et un executeBatch() à la fin
                            ps.executeUpdate();
                        }
                    }
                    conn.commit();
                } catch (SQLException | IllegalStateException e) {
                    if (!conn.getAutoCommit()) conn.rollback();
                    throw new CompletionException(e);
                } finally {
                    if (!conn.getAutoCommit()) conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to manage transaction for saveAllPlayerPages for " + uuid, e);
                throw new CompletionException(e);
            }
        }, getExecutor());
    }

    @Override
    public CompletableFuture<Void> deletePlayerInventoryPage(UUID uuid, int pageNumber) {
        return CompletableFuture.runAsync(() -> {
            try {
                doDeletePlayerInventoryPage(uuid, pageNumber);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Async delete of page " + pageNumber + " for " + uuid + " failed.", e);
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
                plugin.getLogger().log(Level.SEVERE, "Async delete of all data for " + uuid + " failed.", e);
                throw new CompletionException(e);
            }
        }, getExecutor());
    }

    // Méthodes ...Blocking (pour usage synchrone, typiquement onDisable)
    @Override
    public void saveFullPlayerInventoryDataBlocking(PlayerInventoryData data) throws SQLException {
        //plugin.getLogger().fine("[Blocking] Saving full player data for " + data.getUuid()); // Loggué par InventoryManager
        doSaveFullPlayerInventoryData(data);
    }
}
