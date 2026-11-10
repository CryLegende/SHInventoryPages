package org.Cry_Legende.sHInventoryPages.protection;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Drapeau item-pickup des régions WorldGuard. Cette classe n'est chargée que si WorldGuard est installé. */
public final class WorldGuardPickupRules implements PickupRules {

    private final RegionQuery query;

    private WorldGuardPickupRules() {
        this.query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
    }

    /** Point d'entrée : le plugin ne résout cette classe qu'à cet appel, donc jamais sans WorldGuard. */
    public static PickupRules open() {
        return new WorldGuardPickupRules();
    }

    @Override
    public boolean canPickUp(Player player, Location location) {
        LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(player);
        if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(local, local.getWorld())) return true;
        return query.testState(BukkitAdapter.adapt(location), local, Flags.ITEM_PICKUP);
    }
}
