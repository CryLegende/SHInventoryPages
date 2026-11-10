package org.Cry_Legende.sHInventoryPages.database;

import java.util.List;

/**
 * Ce qu'un chargement a trouvé de propre au mode réseau.
 *
 * @param epoch version de session prise par ce chargement
 * @param prior version de la session précédente (0 si le joueur n'avait jamais été chargé en mode réseau) :
 *              une copie de secours n'est reprise que si elle vient de cette session-là
 * @param dumps copies de secours de la base encore valables, de la plus ancienne à la plus récente
 */
public record NetworkLoad(long epoch, long prior, List<NetworkDump> dumps) {
}
