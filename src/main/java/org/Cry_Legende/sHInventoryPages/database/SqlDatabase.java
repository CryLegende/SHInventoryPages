package org.Cry_Legende.sHInventoryPages.database;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.Cry_Legende.sHInventoryPages.util.ItemCodec;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 */
public abstract class SqlDatabase implements DatabaseManager {

    protected final SHInventoryPages plugin;
    /** Encodage de chaque page tel qu'il est en base (après chargement ou écriture validée). */
    private final Map<UUID, Map<Integer, String>> stored = new ConcurrentHashMap<>();
    /** Page courante telle qu'elle est en base. */
    private final Map<UUID, Integer> storedCurrentPage = new ConcurrentHashMap<>();

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

    @Override
    public PlayerInventoryData load(UUID uuid, int maxPossiblePages) throws Exception {
        Map<Integer, String> rows = new HashMap<>();
        Integer[] currentPage = {null};
        withConnection(connection -> {
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
        });

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
    }
}
