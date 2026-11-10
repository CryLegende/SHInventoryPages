package org.Cry_Legende.sHInventoryPages.database;

/**
 * Copie de secours gardée en base (mode réseau) : le texte est celui d'un fichier de {@code unsaved/}.
 *
 * @param id    ligne de la table shinventorypages_unsaved
 * @param epoch version de session qui l'a écrite
 * @param yaml  contenu
 */
public record NetworkDump(long id, long epoch, String yaml) {
}
