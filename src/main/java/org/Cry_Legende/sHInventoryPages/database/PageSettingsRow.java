package org.Cry_Legende.sHInventoryPages.database;

/**
 * Réglages d'une page choisis par le joueur, tels qu'ils sont en base.
 *
 * @param icon     icône de la page (objet encodé par ItemCodec), ou null pour le sélecteur par défaut
 * @param name     nom donné à la page, ou null
 * @param reserved page réservée : jamais remplie par le rangement automatique
 */
public record PageSettingsRow(String icon, String name, boolean reserved) {

    /** Vrai si la page n'a aucun réglage : sa ligne n'a pas à exister. */
    public boolean isDefault() {
        return icon == null && name == null && !reserved;
    }
}
