package org.Cry_Legende.sHInventoryPages.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * API de SHInventoryPages. Disponible par {@code SHInventoryPages#getApi()} ou par le ServicesManager de Bukkit
 * ({@code Bukkit.getServicesManager().load(ISHInventoryPagesAPI.class)}).
 */
public interface ISHInventoryPagesAPI {

    /**
     * Donne des objets à un joueur en utilisant toutes ses pages, à la place de {@code Inventory#addItem} :
     * piles partielles puis emplacements libres de l'inventaire porté, puis piles partielles et emplacements libres
     * de ses autres pages débloquées (page 1, 2... sauf la page portée). Les quantités au-delà de la taille de pile
     * sont découpées ({@code max_stack_size} de l'objet respecté) ; jamais sur l'emplacement du sélecteur.
     *
     * <p>Thread principal uniquement. Contrat de {@code Inventory#addItem} : la valeur rendue associe l'index de
     * chaque objet qui n'a pas trouvé de place à ce qui en reste (quantité éventuellement supérieure à une pile).
     * Le joueur est prévenu (message configurable) quand des objets partent dans une autre page. Tant que ses pages
     * ne sont pas chargées, seul l'inventaire porté est utilisé.</p>
     *
     * @return les restes, vide si tout a été rangé
     */
    @NotNull Map<Integer, ItemStack> addItems(@NotNull Player player, @NotNull ItemStack... items);

    /** Nombre d'exemplaires de {@code item} que l'inventaire porté et les autres pages peuvent encore recevoir. */
    int getFreeSpace(@NotNull Player player, @NotNull ItemStack item);

    /**
     * Exemplaires d'un objet ({@code isSimilar}) dans l'inventaire porté (emplacements 0 à 35 et main secondaire,
     * comme {@link #addItems}) et dans les autres pages accessibles : de quoi vérifier qu'un joueur possède des objets
     * rangés en page 2, 3... Thread principal.
     */
    int countItems(@NotNull Player player, @NotNull ItemStack item);

    /**
     * Retire des objets de l'inventaire porté (emplacements 0 à 35, puis main secondaire) puis des autres pages
     * accessibles par numéro croissant, à la place de {@code Inventory#removeItem}. Thread principal ; pages figées
     * (parcours, monde interdit) : inventaire porté seulement.
     *
     * @return ce qui n'a pas pu être retiré, par index (contrat de {@code Inventory#removeItem})
     */
    @NotNull Map<Integer, ItemStack> removeItems(@NotNull Player player, @NotNull ItemStack... items);

    /** Vrai quand les pages du joueur sont chargées. */
    boolean isLoaded(@NotNull Player player);

    /** Page portée, ou 0 si les pages ne sont pas chargées. */
    int getCurrentPage(@NotNull Player player);

    /** Nombre de pages débloquées, ou 0 si les pages ne sont pas chargées. */
    int getMaxPages(@NotNull Player player);

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
     */
    boolean isPageSwitchingApiBlocked(@NotNull Player player);

    /** Checks if page switching is currently blocked for the player by a specific API-driven block identifier. */
    boolean isPageSwitchingBlockedBy(@NotNull Player player, @NotNull String blockIdentifier);
}
