package org.Cry_Legende.sHInventoryPages.inventory;

import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerInventoryData {

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

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
        if (this.currentPage > maxPages) this.currentPage = maxPages;
        if (this.currentPage < 1) this.currentPage = 1;
        pages.keySet().removeIf(pageNumber -> pageNumber > maxPages);
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