package org.Cry_Legende.sHInventoryPages.menu;

import com.viaversion.viaversion.api.Via;
import org.bukkit.entity.Player;

/** Seule classe qui touche à ViaVersion : sans lui elle n'est jamais chargée. */
final class ViaSupport {

    private ViaSupport() {
    }

    /** Version de protocole du client, ou 0 si ViaVersion ne la connaît pas. */
    static int protocol(Player player) {
        try {
            return Via.getAPI().getPlayerVersion(player.getUniqueId());
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
