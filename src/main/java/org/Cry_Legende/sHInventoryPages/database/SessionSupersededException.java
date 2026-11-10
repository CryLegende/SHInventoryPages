package org.Cry_Legende.sHInventoryPages.database;

import java.sql.SQLException;

/**
 * Une écriture de pages a été refusée parce qu'un autre serveur a repris le joueur depuis le chargement de sa
 * session (version de session dépassée). Jamais réessayée : elle écraserait des pages plus récentes.
 */
public class SessionSupersededException extends SQLException {

    public SessionSupersededException(String message) {
        super(message);
    }
}
