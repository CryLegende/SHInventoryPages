package org.Cry_Legende.sHInventoryPages.inventory;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlayerInventoryData {

    /** Au-delà, ItemStack.serialize lève une exception (limite du codec de composants). */
    static final int SERIALIZABLE_MAX_AMOUNT = 99;
    private static final int PAGE_SIZE = 36;

    private final UUID uuid;
    private int currentPage;
    private int maxPages;
    private final Map<Integer, ItemStack[]> pages;
    private ItemStack helmet;
    private ItemStack chestplate;
    private ItemStack leggings;
    private ItemStack boots;
    private ItemStack offHand;

    public PlayerInventoryData(UUID uuid, int maxPages) {
        this.uuid = uuid;
        this.currentPage = 1;
        this.maxPages = maxPages;
        this.pages = new HashMap<>();
        this.helmet = null;
        this.chestplate = null;
        this.leggings = null;
        this.boots = null;
        this.offHand = null;
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(int currentPage) {
        if (currentPage >= 1 && currentPage <= this.maxPages) {
            this.currentPage = currentPage;
        }
    }

    public int getMaxPages() {
        return maxPages;
    }

    /**
     * Les pages au-delà du nouveau maximum ne sont plus supprimées (leur contenu partait à la sauvegarde suivante) :
     * elles restent en base, hors d'atteinte. Si la page courante dépasse le maximum, elle prend le numéro de la
     * dernière page autorisée et le contenu de celle-ci part à l'ancien numéro : l'inventaire porté (qui est la
     * page courante) ne change pas et n'écrase plus la page {@code maxPages} à la capture suivante.
     */
    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
        if (maxPages >= 1 && this.currentPage > maxPages) {
            int hiddenPage = this.currentPage;
            ItemStack[] displaced = pages.remove(maxPages);
            ItemStack[] carried = pages.remove(hiddenPage);
            if (displaced != null) pages.put(hiddenPage, displaced);
            if (carried != null) pages.put(maxPages, carried);
            this.currentPage = maxPages;
        }
        if (this.currentPage < 1) this.currentPage = 1;
    }

    public ItemStack[] getPage(int pageNumber) {
        return pages.get(pageNumber);
    }

    public void setPage(int pageNumber, ItemStack[] contents) {
        if (pageNumber >= 1 && pageNumber <= this.maxPages) {
            pages.put(pageNumber, contents);
        }
    }

    public Map<Integer, ItemStack[]> getAllPages() {
        return pages;
    }

    /**
     * Un stack de plus de 99 objets ne se sérialise pas ("Value must be within range [1;99]") et fait
     * échouer la sauvegarde de toutes les pages du joueur. Chaque stack trop gros des pages enregistrées est
     * ramené à la taille normale de son objet et l'excédent est replacé dans les emplacements libres des
     * autres pages. La page courante n'est jamais touchée : elle reflète l'inventaire réel, corrigé à chaque
     * capture par InventoryManager.saveCurrentInventoryToData ; la recouper ici sans corriger l'inventaire
     * réel recréait l'excédent à chaque sauvegarde (duplication).
     *
     * @param reservedSlot emplacement du sélecteur de pages, vidé à chaque sauvegarde : jamais utilisé pour l'excédent
     * @return les stacks qui n'ont trouvé aucune place, vide dans le cas normal
     */
    public List<ItemStack> splitOversizedStacks(int reservedSlot) {
        List<ItemStack> extra = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack[]> entry : pages.entrySet()) {
            ItemStack[] page = entry.getValue();
            if (page == null || entry.getKey() == currentPage) continue;
            for (int i = 0; i < page.length; i++) {
                ItemStack item = page[i];
                if (item == null) continue;
                int chunkSize = serializableChunk(item);
                if (item.getAmount() <= chunkSize) continue;
                extra.addAll(excessParts(item, chunkSize));
                ItemStack first = item.clone();
                first.setAmount(chunkSize);
                page[i] = first;
            }
        }
        List<ItemStack> unplaced = new ArrayList<>();
        for (ItemStack part : extra) {
            if (!placeInFreeSlot(part, reservedSlot)) {
                unplaced.add(part);
            }
        }
        return unplaced;
    }

    /** Taille de stack sauvegardable pour cet objet : sa taille normale, 99 au plus. */
    static int serializableChunk(ItemStack item) {
        return Math.max(1, Math.min(item.getMaxStackSize(), SERIALIZABLE_MAX_AMOUNT));
    }

    /** Ce qui dépasse {@code chunkSize} dans {@code item}, en stacks de {@code chunkSize} objets au plus. */
    static List<ItemStack> excessParts(ItemStack item, int chunkSize) {
        List<ItemStack> parts = new ArrayList<>();
        int remaining = item.getAmount() - chunkSize;
        while (remaining > 0) {
            ItemStack part = item.clone();
            part.setAmount(Math.min(chunkSize, remaining));
            parts.add(part);
            remaining -= part.getAmount();
        }
        return parts;
    }

    /** Place {@code part} dans un emplacement libre d'une autre page que la page courante. */
    boolean placeInFreeSlot(ItemStack part, int reservedSlot) {
        for (int pageNumber = 1; pageNumber <= maxPages; pageNumber++) {
            if (pageNumber == currentPage) continue;
            ItemStack[] page = pages.computeIfAbsent(pageNumber, n -> new ItemStack[PAGE_SIZE]);
            for (int i = 0; i < page.length; i++) {
                if (i == reservedSlot) continue;
                if (page[i] == null || page[i].getType() == Material.AIR) {
                    page[i] = part;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Copie indépendante pour une sauvegarde asynchrone : le thread d'écriture ne doit jamais
     * parcourir la map {@code pages} pendant que le thread principal la modifie.
     */
    public PlayerInventoryData snapshot() {
        PlayerInventoryData copy = new PlayerInventoryData(uuid, maxPages);
        copy.currentPage = currentPage;
        for (Map.Entry<Integer, ItemStack[]> entry : pages.entrySet()) {
            ItemStack[] source = entry.getValue();
            if (source == null) {
                copy.pages.put(entry.getKey(), null);
                continue;
            }
            ItemStack[] items = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) {
                items[i] = source[i] != null ? source[i].clone() : null;
            }
            copy.pages.put(entry.getKey(), items);
        }
        copy.helmet = helmet != null ? helmet.clone() : null;
        copy.chestplate = chestplate != null ? chestplate.clone() : null;
        copy.leggings = leggings != null ? leggings.clone() : null;
        copy.boots = boots != null ? boots.clone() : null;
        copy.offHand = offHand != null ? offHand.clone() : null;
        return copy;
    }

    public ItemStack getHelmet() {
        return helmet;
    }

    public void setHelmet(ItemStack helmet) {
        this.helmet = helmet;
    }

    public ItemStack getChestplate() {
        return chestplate;
    }

    public void setChestplate(ItemStack chestplate) {
        this.chestplate = chestplate;
    }

    public ItemStack getLeggings() {
        return leggings;
    }

    public void setLeggings(ItemStack leggings) {
        this.leggings = leggings;
    }

    public ItemStack getBoots() {
        return boots;
    }

    public void setBoots(ItemStack boots) {
        this.boots = boots;
    }

    public ItemStack getOffHand() {
        return offHand;
    }

    public void setOffHand(ItemStack offHand) {
        this.offHand = offHand;
    }
}