package org.Cry_Legende.sHInventoryPages.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

/** MySQL / MariaDB par HikariCP. */
public class MySQLManager extends SqlDatabase {

    private volatile HikariDataSource dataSource;

    public MySQLManager(SHInventoryPages plugin) {
        super(plugin);
    }

    @Override
    public boolean connect() {
        ConfigManager cfg = plugin.getConfigManager();
        try {
            // Pilote MySQL fourni par Paper en priorité ; celui de MariaDB vient des libraries: du plugin.yml,
            // chargé dans le classloader du plugin
            String driver;
            String scheme;
            if (classExists("com.mysql.cj.jdbc.Driver")) {
                driver = "com.mysql.cj.jdbc.Driver";
                scheme = "mysql";
            } else {
                driver = "org.mariadb.jdbc.Driver";
                scheme = "mariadb";
            }
            HikariConfig config = new HikariConfig();
            config.setPoolName("SHInventoryPages");
            config.setDriverClassName(driver);
            config.setJdbcUrl("jdbc:" + scheme + "://" + cfg.getMysqlHost() + ":" + cfg.getMysqlPort() + "/"
                + cfg.getMysqlDatabase() + "?" + cfg.getMysqlProperties());
            config.setUsername(cfg.getMysqlUsername());
            config.setPassword(cfg.getMysqlPassword());
            config.setMaximumPoolSize(cfg.getMysqlPoolSize());
            config.setMinimumIdle(1);
            config.setIdleTimeout(300_000);
            config.setConnectionTimeout(5_000);
            config.setMaxLifetime(1_800_000);
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("rewriteBatchedStatements", "true");
            this.dataSource = new HikariDataSource(config);
            plugin.getLogger().info("MySQL/MariaDB connection pool established (" + scheme + " driver).");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not create MySQL/MariaDB connection pool!", e);
            return false;
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public void disconnect() {
        HikariDataSource ds = dataSource;
        if (ds != null && !ds.isClosed()) {
            ds.close();
            plugin.getLogger().info("MySQL/MariaDB connection pool closed.");
        }
    }

    @Override
    protected <T> T withConnection(SqlWork<T> work) throws SQLException {
        HikariDataSource ds = dataSource;
        if (ds == null || ds.isClosed()) {
            throw new SQLException("MySQL/MariaDB connection pool is closed.");
        }
        try (Connection connection = ds.getConnection()) {
            return work.run(connection);
        }
    }

    @Override
    protected String upsertPageSql() {
        return "INSERT INTO player_inventory_pages (uuid, page_number, inventory_data) VALUES (?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE inventory_data = VALUES(inventory_data)";
    }

    @Override
    protected String upsertCurrentPageSql() {
        return "INSERT INTO player_equipment (uuid, currentPage) VALUES (?, ?) "
            + "ON DUPLICATE KEY UPDATE currentPage = VALUES(currentPage)";
    }

    @Override
    protected String createSettingsTableSql() {
        return "CREATE TABLE IF NOT EXISTS `shinventorypages_page_settings` ("
            + "`uuid` VARCHAR(36) NOT NULL,"
            + "`page_number` INT NOT NULL,"
            + "`icon` TEXT,"
            + "`reserved` TINYINT NOT NULL DEFAULT 0,"
            + "PRIMARY KEY(`uuid`,`page_number`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
    }

    @Override
    protected String upsertSettingsSql() {
        return "INSERT INTO shinventorypages_page_settings (uuid, page_number, icon, reserved) VALUES (?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE icon = VALUES(icon), reserved = VALUES(reserved)";
    }

    @Override
    protected String createHistoryTableSql() {
        return "CREATE TABLE IF NOT EXISTS `shinventorypages_history` ("
            + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
            + "`uuid` VARCHAR(36) NOT NULL,"
            + "`created` BIGINT NOT NULL,"
            + "`reason` VARCHAR(16) NOT NULL,"
            + "`current_page` INT NOT NULL,"
            + "`max_pages` INT NOT NULL,"
            + "`slots` INT NOT NULL,"
            + "`items` INT NOT NULL,"
            + "`hash` CHAR(64) NOT NULL,"
            + "`data` LONGTEXT NOT NULL,"
            + "PRIMARY KEY(`id`),"
            + "KEY `idx_uuid_reason` (`uuid`, `reason`, `id`),"
            + "KEY `idx_created` (`created`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
    }

    @Override
    public boolean createTables() {
        String pages = "CREATE TABLE IF NOT EXISTS `player_inventory_pages` ("
            + "`uuid` VARCHAR(36) NOT NULL,"
            + "`page_number` INT NOT NULL,"
            + "`inventory_data` LONGTEXT,"
            + "PRIMARY KEY(`uuid`,`page_number`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        String equipment = "CREATE TABLE IF NOT EXISTS `player_equipment` ("
            + "`uuid` VARCHAR(36) NOT NULL PRIMARY KEY,"
            + "`helmet` TEXT, `chestplate` TEXT, `leggings` TEXT, `boots` TEXT, `off_hand` TEXT"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        try {
            return withConnection(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(pages);
                    statement.execute(equipment);
                    if (!columnExists(connection, plugin.getConfigManager().getMysqlDatabase(), "player_equipment", "currentPage")) {
                        statement.executeUpdate("ALTER TABLE `player_equipment` ADD COLUMN `currentPage` INT DEFAULT 1;");
                        plugin.getLogger().info("Column 'currentPage' added to 'player_equipment'.");
                    }
                }
                return true;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not create/update MySQL/MariaDB tables schema!", e);
            return false;
        }
    }

    /**
     * Tables du mode réseau, dans la même base que les pages : la version de session et les pages sont écrites dans
     * la même transaction. Voir sql/schema.sql pour les créer à la main.
     */
    @Override
    public boolean enableNetwork(String server) {
        String sync = "CREATE TABLE IF NOT EXISTS `shinventorypages_sync` ("
            + "`uuid` VARCHAR(36) NOT NULL,"
            + "`version` BIGINT NOT NULL,"
            + "`server` VARCHAR(64) NOT NULL,"
            + "`updated` BIGINT NOT NULL,"
            + "PRIMARY KEY(`uuid`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        String unsaved = "CREATE TABLE IF NOT EXISTS `shinventorypages_unsaved` ("
            + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
            + "`uuid` VARCHAR(36) NOT NULL,"
            + "`epoch` BIGINT NOT NULL,"
            + "`server` VARCHAR(64) NOT NULL,"
            + "`created` BIGINT NOT NULL,"
            + "`superseded` TINYINT NOT NULL DEFAULT 0,"
            + "`data` LONGTEXT NOT NULL,"
            + "PRIMARY KEY(`id`),"
            + "KEY `idx_uuid` (`uuid`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        try {
            withConnection(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sync);
                    statement.execute(unsaved);
                }
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Tables du mode réseau introuvables et impossibles à créer (voir sql/schema.sql)", e);
            return false;
        }
        this.serverName = server;
        this.network = true;
        return true;
    }

    private boolean columnExists(Connection connection, String catalog, String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getColumns(catalog, null, table, column)) {
            return rs.next();
        }
    }
}
