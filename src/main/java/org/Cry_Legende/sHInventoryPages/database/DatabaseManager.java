package org.Cry_Legende.sHInventoryPages.database;

import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;

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
}
