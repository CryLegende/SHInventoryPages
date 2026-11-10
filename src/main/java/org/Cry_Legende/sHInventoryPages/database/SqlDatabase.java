package org.Cry_Legende.sHInventoryPages.database;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.history.HistoryRecord;
import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.Cry_Legende.sHInventoryPages.util.ItemCodec;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Lecture et écriture communes à MySQL/MariaDB et SQLite.
 *
 * <p>Chaque page est une ligne {@code (uuid, page_number, inventory_data)}. Une sauvegarde n'écrit que les pages
 * dont l'encodage diffère de ce que la base contient déjà (connu depuis le chargement ou la dernière écriture
 * validée) : une sauvegarde automatique d'un joueur qui n'a rien rangé ne touche plus la base. La page courante
 * va dans {@code player_equipment.currentPage} ; les anciennes colonnes d'équipement de cette table ne sont plus
 * lues ni écrites.</p>
 *
 * <p>Mode réseau (plusieurs serveurs sur la même base) : chaque chargement prend une version de session
 * ({@code shinventorypages_sync.version}, augmentée dans la même transaction que la lecture des pages) et chaque
 * écriture vérifie, sous verrou de ligne, que cette version est toujours la dernière. Une écriture d'un serveur
 * dépassé, qui arriverait après que l'autre serveur a repris le joueur, est donc refusée au lieu d'écraser les
 * pages les plus récentes.</p>
 */
public abstract class SqlDatabase implements DatabaseManager {

    protected final SHInventoryPages plugin;
    /** Encodage de chaque page tel qu'il est en base (après chargement ou écriture validée). */
    private final Map<UUID, Map<Integer, String>> stored = new ConcurrentHashMap<>();
    /** Page courante telle qu'elle est en base. */
    private final Map<UUID, Integer> storedCurrentPage = new ConcurrentHashMap<>();
    /** Mode réseau : version de session prise au chargement de chaque joueur chargé. */
    private final Map<UUID, Long> epochs = new ConcurrentHashMap<>();
    /** Mode réseau : ce que le dernier chargement a trouvé, repris une seule fois par le gestionnaire. */
    private final Map<UUID, NetworkLoad> networkLoads = new ConcurrentHashMap<>();
    /** Mode réseau (voir {@link #enableNetwork}) : base partagée par plusieurs serveurs. */
    protected volatile boolean network;
    protected volatile String serverName = "";

    protected SqlDatabase(SHInventoryPages plugin) {
        this.plugin = plugin;
    }

    @FunctionalInterface
    protected interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Exécute {@code work} avec une connexion (empruntée au pool, ou la connexion unique verrouillée). */
    protected abstract <T> T withConnection(SqlWork<T> work) throws SQLException;

    /** Insère ou remplace une page : paramètres uuid, page_number, inventory_data. */
    protected abstract String upsertPageSql();

    /** Insère ou met à jour la seule colonne currentPage : paramètres uuid, currentPage. */
    protected abstract String upsertCurrentPageSql();

    /** Création de la table shinventorypages_history, dans le dialecte de la base. */
    protected abstract String createHistoryTableSql();

    /** Création de l'index (uuid, reason, id) de shinventorypages_history, ou null s'il est créé avec la table. */
    protected String createHistoryIndexSql() {
        return null;
    }

    @Override
    public PlayerInventoryData load(UUID uuid, int maxPossiblePages) throws Exception {
        Map<Integer, String> rows = new HashMap<>();
        Integer[] currentPage = {null};
        NetworkLoad[] claimed = {null};
        SqlWork<Void> reads = connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT page_number, inventory_data FROM player_inventory_pages WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String data = rs.getString(2);
                        rows.put(rs.getInt(1), data != null ? data : "");
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT currentPage FROM player_equipment WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int page = rs.getInt(1);
                        if (!rs.wasNull()) currentPage[0] = page;
                    }
                }
            }
            return null;
        };
        if (!network) {
            withConnection(reads);
        } else {
            // La version de session est prise dans la même transaction que la lecture : ce qui est lu est ce que le
            // serveur précédent a validé, et une écriture de celui-ci qui arriverait après serait refusée
            withConnection(connection -> inTransaction(connection, () -> {
                claimed[0] = claim(connection, uuid);
                reads.run(connection);
                return null;
            }));
            epochs.put(uuid, claimed[0].epoch());
            networkLoads.put(uuid, claimed[0]);
        }

        if (rows.isEmpty() && currentPage[0] == null) {
            stored.put(uuid, new ConcurrentHashMap<>());
            storedCurrentPage.remove(uuid);
            return null;
        }
        // Décodage hors connexion. Une page illisible (objet que cette version refuse) est verrouillée : jamais
        // présentée amputée, jamais réécrite, sa ligne reste intacte en base pour un examen manuel
        int highest = rows.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
        PlayerInventoryData data = new PlayerInventoryData(uuid, Math.max(maxPossiblePages, highest));
        for (Map.Entry<Integer, String> row : rows.entrySet()) {
            try {
                data.putStoredPage(row.getKey(), ItemCodec.decodePage(row.getValue(), PlayerInventoryData.PAGE_SIZE));
            } catch (Exception e) {
                data.lockPage(row.getKey());
                plugin.getLogger().log(Level.SEVERE, "Page " + row.getKey() + " de " + uuid
                    + " illisible : verrouillée, laissée intacte en base", e);
            }
        }
        // Page courante gardée même si sa ligne est illisible : l'inventaire porté fait foi pour elle (InventoryManager)
        data.setLoadedCurrentPage(currentPage[0] != null && currentPage[0] > 0 ? currentPage[0] : 1);

        stored.put(uuid, new ConcurrentHashMap<>(rows));
        if (currentPage[0] != null) storedCurrentPage.put(uuid, currentPage[0]);
        else storedCurrentPage.remove(uuid);
        return data;
    }

    @Override
    public void save(PlayerInventoryData snapshot) throws Exception {
        UUID uuid = snapshot.getUuid();
        Map<Integer, String> known = stored.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());

        // Encodage hors connexion
        Map<Integer, String> encoded = new HashMap<>();
        for (Map.Entry<Integer, ItemStack[]> page : snapshot.pagesView().entrySet()) {
            encoded.put(page.getKey(), ItemCodec.encodePage(page.getValue(), plugin.getLogger()));
        }
        // La copie ne contient que les pages modifiées ; une capture identique à ce qui est en base n'écrit rien
        List<Integer> changed = new ArrayList<>();
        for (Map.Entry<Integer, String> page : encoded.entrySet()) {
            if (snapshot.isLocked(page.getKey())) continue;
            if (!page.getValue().equals(known.get(page.getKey()))) changed.add(page.getKey());
        }
        int currentPage = snapshot.getCurrentPage();
        boolean currentChanged = !Objects.equals(storedCurrentPage.get(uuid), currentPage);
        if (changed.isEmpty() && !currentChanged) return;

        withConnection(connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (network) verifySession(connection, uuid, snapshot.getEpoch());
                if (!changed.isEmpty()) {
                    try (PreparedStatement ps = connection.prepareStatement(upsertPageSql())) {
                        for (Integer page : changed) {
                            ps.setString(1, uuid.toString());
                            ps.setInt(2, page);
                            ps.setString(3, encoded.get(page));
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                if (currentChanged) {
                    try (PreparedStatement ps = connection.prepareStatement(upsertCurrentPageSql())) {
                        ps.setString(1, uuid.toString());
                        ps.setInt(2, currentPage);
                        ps.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            return null;
        });

        // État connu mis à jour seulement après validation de la transaction
        for (Integer page : changed) known.put(page, encoded.get(page));
        storedCurrentPage.put(uuid, currentPage);
    }

    @Override
    public void forget(UUID uuid) {
        stored.remove(uuid);
        storedCurrentPage.remove(uuid);
        epochs.remove(uuid);
        networkLoads.remove(uuid);
    }

    // ------------------------------------------------------------------ mode réseau

    @Override
    public long sessionEpoch(UUID uuid) {
        Long epoch = epochs.get(uuid);
        return epoch != null ? epoch : -1L;
    }

    @Override
    public NetworkLoad takeNetworkLoad(UUID uuid) {
        return networkLoads.remove(uuid);
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    /** Transaction : validée si le travail réussit, annulée sinon. */
    private static <T> T inTransaction(Connection connection, SqlSupplier<T> work) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.get();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * Prend la version de session du joueur (créée à 1 s'il n'en avait pas) et relit les copies de secours de la base.
     * Une copie n'est reprise que si elle vient de la session précédente : celle d'une session que d'autres ont
     * dépassée depuis est mise de côté (superseded = 1), jamais reprise ni supprimée.
     */
    private NetworkLoad claim(Connection connection, UUID uuid) throws SQLException {
        long epoch;
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO shinventorypages_sync (uuid, version, server, updated) VALUES (?, 1, ?, ?) "
                + "ON DUPLICATE KEY UPDATE version = version + 1, server = VALUES(server), updated = VALUES(updated)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, serverName);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT version FROM shinventorypages_sync WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Version de session introuvable pour " + uuid);
                epoch = rs.getLong(1);
            }
        }
        long prior = epoch - 1;
        List<NetworkDump> valid = new ArrayList<>();
        List<Long> stale = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT id, epoch, data FROM shinventorypages_unsaved WHERE uuid = ? AND superseded = 0 ORDER BY id")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long dumpEpoch = rs.getLong(2);
                    if (dumpEpoch > 0 && dumpEpoch != prior) stale.add(rs.getLong(1));
                    else valid.add(new NetworkDump(rs.getLong(1), dumpEpoch, rs.getString(3)));
                }
            }
        }
        if (!stale.isEmpty()) {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE shinventorypages_unsaved SET superseded = 1 WHERE id = ?")) {
                for (Long id : stale) {
                    ps.setLong(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            plugin.getLogger().severe(stale.size() + " copie(s) de secours de " + uuid + " en base datent d'une session dépassée par un"
                + " autre serveur : mises de côté (superseded = 1), à examiner à la main");
        }
        return new NetworkLoad(epoch, prior, valid);
    }

    /**
     * Dans la transaction d'écriture : la version de session de la ligne doit être celle prise au chargement. La
     * ligne est verrouillée jusqu'à la validation, si bien qu'un autre serveur qui reprend le joueur attend la fin
     * de cette écriture, et que celle-ci est refusée s'il a déjà repris.
     */
    private void verifySession(Connection connection, UUID uuid, long epoch) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT version FROM shinventorypages_sync WHERE uuid = ? FOR UPDATE")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                long current = rs.next() ? rs.getLong(1) : -1L;
                if (epoch < 0 || current != epoch) {
                    throw new SessionSupersededException("version de session " + epoch + " dépassée (base : " + current + ")");
                }
            }
        }
    }

    @Override
    public long storeDump(UUID uuid, long epoch, String yaml) throws Exception {
        return withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO shinventorypages_unsaved (uuid, epoch, server, created, superseded, data) VALUES (?, ?, ?, ?, 0, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, epoch);
                ps.setString(3, serverName);
                ps.setLong(4, System.currentTimeMillis());
                ps.setString(5, yaml);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Identifiant de la copie de secours non rendu");
                    return keys.getLong(1);
                }
            }
        });
    }

    @Override
    public void deleteDump(long id) throws Exception {
        withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM shinventorypages_unsaved WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ historique des pages

    @Override
    public boolean createHistoryTable() {
        try {
            withConnection(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(createHistoryTableSql());
                    String index = createHistoryIndexSql();
                    if (index != null) statement.execute(index);
                }
                return null;
            });
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Table shinventorypages_history introuvable et impossible à créer : historique désactivé", e);
            return false;
        }
    }

    @Override
    public long insertHistory(HistoryRecord record, int keep) throws Exception {
        return withConnection(connection -> {
            long id;
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO shinventorypages_history (uuid, created, reason, current_page, max_pages, slots, items, hash, data)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, record.uuid().toString());
                ps.setLong(2, record.created());
                ps.setString(3, record.reason());
                ps.setInt(4, record.currentPage());
                ps.setInt(5, record.maxPages());
                ps.setInt(6, record.slots());
                ps.setInt(7, record.items());
                ps.setString(8, record.hash());
                ps.setString(9, record.data());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Identifiant de la copie non rendu");
                    id = keys.getLong(1);
                }
            }
            // Seules les « keep » copies les plus récentes de ce moment restent. Table dérivée : MariaDB refuse de lire
            // directement la table qu'il modifie ; moins de « keep » copies, la sous-requête est vide et rien ne part
            try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM shinventorypages_history WHERE uuid = ? AND reason = ? AND id < (SELECT oldest FROM ("
                    + "SELECT id AS oldest FROM shinventorypages_history WHERE uuid = ? AND reason = ?"
                    + " ORDER BY id DESC LIMIT 1 OFFSET ?) kept)")) {
                ps.setString(1, record.uuid().toString());
                ps.setString(2, record.reason());
                ps.setString(3, record.uuid().toString());
                ps.setString(4, record.reason());
                ps.setInt(5, Math.max(0, keep - 1));
                ps.executeUpdate();
            }
            return id;
        });
    }

    @Override
    public String lastHistoryHash(UUID uuid) throws Exception {
        return withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT hash FROM shinventorypages_history WHERE uuid = ? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    @Override
    public List<HistoryRecord> listHistory(UUID uuid, int offset, int limit) throws Exception {
        return withConnection(connection -> {
            List<HistoryRecord> records = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, created, reason, current_page, max_pages, slots, items, hash FROM shinventorypages_history"
                    + " WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        records.add(new HistoryRecord(rs.getLong(1), uuid, rs.getLong(2), rs.getString(3), rs.getInt(4),
                            rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8), null));
                    }
                }
            }
            return records;
        });
    }

    @Override
    public HistoryRecord loadHistory(long id) throws Exception {
        return withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid, created, reason, current_page, max_pages, slots, items, hash, data"
                    + " FROM shinventorypages_history WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new HistoryRecord(id, UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getString(3), rs.getInt(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8), rs.getString(9));
                }
            }
        });
    }

    @Override
    public int purgeHistory(long before) throws Exception {
        return withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM shinventorypages_history WHERE created < ?")) {
                ps.setLong(1, before);
                return ps.executeUpdate();
            }
        });
    }
}
