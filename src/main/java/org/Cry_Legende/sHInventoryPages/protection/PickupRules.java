package org.Cry_Legende.sHInventoryPages.protection;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Règles de ramassage d'un plugin de protection, consultées avant de ranger dans les pages un objet que le joueur
 * touche. Le ramassage vanilla les applique de son côté (EntityPickupItemEvent) ; le ramassage vers les pages ne passe
 * pas par lui et doit donc les lire lui-même.
 */
public interface PickupRules {

    /** Aucune protection installée : tout objet que le serveur laisse ramasser peut l'être. */
    PickupRules NONE = (player, location) -> true;

    /** Vrai si le joueur peut ramasser un objet à cet endroit. */
    boolean canPickUp(Player player, Location location);
}
