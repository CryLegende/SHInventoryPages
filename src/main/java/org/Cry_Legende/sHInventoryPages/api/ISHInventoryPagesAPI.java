package org.Cry_Legende.sHInventoryPages.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public interface ISHInventoryPagesAPI {

    /**
     * Blocks page switching for the specified player due to a given reason/identifier.
     * If switching is blocked, the barrier item will be shown, and page switching attempts will fail.
     * Multiple identifiers can block the same player; switching will be unblocked only when all identifiers are removed.
     *
     * @param player          The player for whom to block page switching.
     * @param blockIdentifier A unique string identifying the source or reason for the block (e.g., "myplugin_combat_lock").
     *                        It's recommended to prefix this with your plugin's name.
     */
    void blockPageSwitching(@NotNull Player player, @NotNull String blockIdentifier);

    /**
     * Unblocks page switching for the specified player for a given reason/identifier.
     * If this was the last active block identifier for the player, page switching will be re-enabled
     * (unless blacklisted by world or other permanent restrictions).
     *
     * @param player          The player for whom to unblock page switching.
     * @param blockIdentifier The unique string identifier that was used to block switching.
     */
    void unblockPageSwitching(@NotNull Player player, @NotNull String blockIdentifier);

    /**
     * Checks if page switching is currently blocked for the player by any API-driven block.
     * This does not consider world blacklists.
     *
     * @param player The player to check.
     * @return True if page switching is blocked by at least one identifier, false otherwise.
     */
    boolean isPageSwitchingApiBlocked(@NotNull Player player);

    /**
     * Checks if page switching is currently blocked for the player by a specific API-driven block identifier.
     *
     * @param player          The player to check.
     * @param blockIdentifier The specific identifier to check for.
     * @return True if the specified identifier is currently blocking the player, false otherwise.
     */
    boolean isPageSwitchingBlockedBy(@NotNull Player player, @NotNull String blockIdentifier);
}
