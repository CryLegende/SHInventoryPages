package org.Cry_Legende.sHInventoryPages.database;

import org.Cry_Legende.sHInventoryPages.history.HistoryRecord;

import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stockage des pages. Toutes les méthodes de lecture et d'écriture sont bloquantes : elles tournent sur le
 * thread de la base de données du plugin, jamais sur le thread principal, et les opérations d'un même joueur
 * sont exécutées dans l'ordre (InventoryManager).
 */
public interface DatabaseManager {

    boolean connect();

    void disconnect();

    boolean createTables();

    /**
     * Pages enregistrées et page courante d'un joueur, ou null s'il n'a encore rien en base.
     *
     * @param maxPossiblePages nombre de pages sous lequel aucune page lue n'est jamais écartée
     */
    PlayerInventoryData load(UUID uuid, int maxPossiblePages) throws Exception;

    /** Écrit les pages modifiées depuis la dernière lecture ou écriture, et la page courante si elle a changé. */
    void save(PlayerInventoryData snapshot) throws Exception;

    /** Oublie ce que la base contient pour ce joueur (après sa dernière sauvegarde de session). */
    void forget(UUID uuid);

    // ------------------------------------------------------------------ mode réseau (base partagée par plusieurs serveurs)

    /**
     * Passe en mode réseau : chaque chargement prend une version de session, chaque écriture la vérifie et les
     * copies de secours sont gardées en base. Faux si ce stockage ne peut pas être partagé (SQLite).
     *
     * @param server nom de ce serveur sur le réseau
     */
    default boolean enableNetwork(String server) {
        return false;
    }

    /** Version de session prise au chargement du joueur, ou -1 (hors mode réseau, ou joueur non chargé). */
    default long sessionEpoch(UUID uuid) {
        return -1L;
    }

    /** Ce que le dernier chargement du joueur a trouvé de propre au mode réseau (lu une seule fois), ou null. */
    default NetworkLoad takeNetworkLoad(UUID uuid) {
        return null;
    }

    /** Garde une copie de secours en base (mode réseau) ; rend l'identifiant de la ligne. */
    default long storeDump(UUID uuid, long epoch, String yaml) throws Exception {
        throw new UnsupportedOperationException("Copies de secours en base : mode réseau seulement");
    }

    /** Supprime une copie de secours de la base une fois ses pages couvertes par une écriture validée. */
    default void deleteDump(long id) throws Exception {
        throw new UnsupportedOperationException("Copies de secours en base : mode réseau seulement");
    }

    // ------------------------------------------------------------------ réglages des pages (icône, page réservée)

    /**
     * Crée la table des réglages de pages si elle n'existe pas. Faux si elle est introuvable et impossible à créer :
     * les réglages ne sont alors ni lus ni écrits, le reste du plugin n'en dépend pas.
     */
    boolean createSettingsTable();

    /** Réglages des pages du joueur lus par son dernier chargement (repris une seule fois), par numéro de page. */
    Map<Integer, PageSettingsRow> takePageSettings(UUID uuid);

    /** Écrit les réglages d'une page ; une page sans réglage n'a pas de ligne. */
    void savePageSettings(UUID uuid, int page, PageSettingsRow row) throws Exception;

    // ------------------------------------------------------------------ historique des pages

    /** Crée la table de l'historique si elle n'existe pas. */
    boolean createHistoryTable();

    /**
     * Ajoute une copie des pages d'un joueur, puis ne garde que les {@code keep} plus récentes de ce moment.
     *
     * @return identifiant de la copie
     */
    long insertHistory(HistoryRecord record, int keep) throws Exception;

    /** Empreinte de la dernière copie du joueur, ou null s'il n'en a aucune. */
    String lastHistoryHash(UUID uuid) throws Exception;

    /** Copies d'un joueur, de la plus récente à la plus ancienne, sans leurs objets. */
    List<HistoryRecord> listHistory(UUID uuid, int offset, int limit) throws Exception;

    /** Une copie complète, ou null si elle n'existe plus. */
    HistoryRecord loadHistory(long id) throws Exception;

    /** Supprime les copies créées avant {@code before} (ms). */
    int purgeHistory(long before) throws Exception;
}
