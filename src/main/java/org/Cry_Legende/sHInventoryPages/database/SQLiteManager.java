package org.Cry_Legende.sHInventoryPages.database;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

/** SQLite : une seule connexion, gardée ouverte, utilisée sous verrou. */
public class SQLiteManager extends SqlDatabase {

    private final Object lock = new Object();
    private final String dbPath;
    private Connection connection;

    public SQLiteManager(SHInventoryPages plugin) {
        super(plugin);
        this.dbPath = new File(plugin.getDataFolder(), plugin.getConfigManager().getSqliteFile()).getAbsolutePath();
    }

    @Override
    public boolean connect() {
        synchronized (lock) {
            try {
                openIfNeeded();
                plugin.getLogger().info("SQLite connection established.");
                return true;
            } catch (SQLException | ClassNotFoundException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not connect to SQLite database!", e);
                return false;
            }
        }
    }

    private void openIfNeeded() throws SQLException, ClassNotFoundException {
        if (connection != null && !connection.isClosed()) return;
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL;");
            statement.execute("PRAGMA synchronous=NORMAL;");
        }
    }

    @Override
    public void disconnect() {
        synchronized (lock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                    plugin.getLogger().info("SQLite connection closed.");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Error closing SQLite connection!", e);
            }
            connection = null;
        }
    }

    @Override
    protected <T> T withConnection(SqlWork<T> work) throws SQLException {
        synchronized (lock) {
            try {
                openIfNeeded();
            } catch (ClassNotFoundException e) {
                throw new SQLException("SQLite driver not found", e);
            }
            return work.run(connection);
        }
    }

    @Override
    protected String upsertPageSql() {
        return "INSERT INTO player_inventory_pages (uuid, page_number, inventory_data) VALUES (?, ?, ?) "
            + "ON CONFLICT(uuid, page_number) DO UPDATE SET inventory_data = excluded.inventory_data";
    }

    @Override
    protected String upsertCurrentPageSql() {
        return "INSERT INTO player_equipment (uuid, currentPage) VALUES (?, ?) "
            + "ON CONFLICT(uuid) DO UPDATE SET currentPage = excluded.currentPage";
    }

    @Override
    protected String createSettingsTableSql() {
        return "CREATE TABLE IF NOT EXISTS shinventorypages_page_settings ("
            + "uuid VARCHAR(36) NOT NULL, page_number INTEGER NOT NULL, icon TEXT, reserved INTEGER NOT NULL DEFAULT 0,"
            + " PRIMARY KEY (uuid, page_number));";
    }

    @Override
    protected String upsertSettingsSql() {
        return "INSERT INTO shinventorypages_page_settings (uuid, page_number, icon, reserved) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT(uuid, page_number) DO UPDATE SET icon = excluded.icon, reserved = excluded.reserved";
    }

    @Override
    protected String createHistoryTableSql() {
        return "CREATE TABLE IF NOT EXISTS shinventorypages_history ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, uuid VARCHAR(36) NOT NULL, created BIGINT NOT NULL,"
            + " reason VARCHAR(16) NOT NULL, current_page INTEGER NOT NULL, max_pages INTEGER NOT NULL,"
            + " slots INTEGER NOT NULL, items INTEGER NOT NULL, hash CHAR(64) NOT NULL, data TEXT NOT NULL);";
    }

    @Override
    protected String createHistoryIndexSql() {
        return "CREATE INDEX IF NOT EXISTS idx_history_uuid_reason ON shinventorypages_history (uuid, reason, id);";
    }

    @Override
    public boolean createTables() {
        try {
            return withConnection(conn -> {
                try (Statement statement = conn.createStatement()) {
                    statement.execute("CREATE TABLE IF NOT EXISTS player_inventory_pages ("
                        + "uuid VARCHAR(36) NOT NULL, page_number INTEGER NOT NULL, inventory_data TEXT, "
                        + "PRIMARY KEY (uuid, page_number));");
                    statement.execute("CREATE TABLE IF NOT EXISTS player_equipment ("
                        + "uuid VARCHAR(36) PRIMARY KEY, helmet TEXT, chestplate TEXT, leggings TEXT, boots TEXT, off_hand TEXT);");
                    if (!columnExists(conn, "player_equipment", "currentPage")) {
                        statement.executeUpdate("ALTER TABLE player_equipment ADD COLUMN currentPage INTEGER DEFAULT 1;");
                        plugin.getLogger().info("Column 'currentPage' added to 'player_equipment'.");
                    }
                }
                return true;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not create/update SQLite tables schema!", e);
            return false;
        }
    }

    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(\"" + table + "\");")) {
            while (rs.next()) {
                if (rs.getString("name").equalsIgnoreCase(column)) return true;
            }
        }
        return false;
    }
}
