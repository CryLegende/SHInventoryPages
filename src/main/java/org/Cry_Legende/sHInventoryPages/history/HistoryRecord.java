package org.Cry_Legende.sHInventoryPages.history;

import java.util.UUID;

/**
 * Copie des pages d'un joueur à un moment donné, telle qu'elle est en base.
 *
 * @param id          identifiant (0 avant l'écriture)
 * @param created     date de la copie (ms)
 * @param reason      moment de la copie (clé de {@link PageHistory.Reason})
 * @param currentPage page portée à ce moment
 * @param slots       emplacements occupés, toutes pages comprises
 * @param items       objets, toutes pages comprises
 * @param hash        empreinte du contenu (SHA-256), pour ne pas garder deux fois la même copie
 * @param data        pages encodées, une par ligne « numéro=page » ; null dans une liste de copies
 */
public record HistoryRecord(long id, UUID uuid, long created, String reason, int currentPage, int maxPages,
                            int slots, int items, String hash, String data) {
}
