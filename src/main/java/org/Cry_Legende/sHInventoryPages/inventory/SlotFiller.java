package org.Cry_Legende.sHInventoryPages.inventory;

import org.bukkit.inventory.ItemStack;

/**
 * Remplissage d'une grille d'emplacements (inventaire porté ou page enregistrée), dans l'ordre du ramassage
 * vanilla : piles partielles compatibles d'abord, puis emplacements vides. L'emplacement réservé (sélecteur de
 * pages) n'est jamais utilisé. Un emplacement modifié reçoit toujours un nouvel objet : le modèle n'est jamais
 * partagé entre deux emplacements.
 */
final class SlotFiller {

    private SlotFiller() {
    }

    /**
     * Taille de pile d'un objet : son {@code max_stack_size} (composant 26.x compris), bornée à [1;99], la limite
     * du codec d'objets de Minecraft : une pile plus grosse ne s'enregistre pas.
     */
    static int stackLimit(ItemStack item) {
        return Math.max(1, Math.min(item.getMaxStackSize(), PlayerInventoryData.SERIALIZABLE_MAX_AMOUNT));
    }

    /** Nombre d'exemplaires de {@code template} que la grille peut encore recevoir. */
    static int capacity(ItemStack[] slots, ItemStack template, int reservedSlot) {
        int limit = stackLimit(template);
        int free = 0;
        for (int i = 0; i < slots.length; i++) {
            if (i == reservedSlot) continue;
            ItemStack slot = slots[i];
            if (slot == null || slot.isEmpty()) {
                free += limit;
            } else if (slot.getAmount() < limit && slot.isSimilar(template)) {
                free += limit - slot.getAmount();
            }
        }
        return free;
    }

    /**
     * Pose au plus {@code amount} exemplaires de {@code template} dans {@code slots}.
     *
     * @param changed marqué à true pour chaque emplacement modifié (peut être null)
     * @return la quantité posée
     */
    static int fill(ItemStack[] slots, ItemStack template, int amount, int reservedSlot, boolean[] changed) {
        if (amount <= 0 || template == null || template.isEmpty()) return 0;
        int limit = stackLimit(template);
        int remaining = amount;
        for (int i = 0; i < slots.length && remaining > 0; i++) {
            if (i == reservedSlot) continue;
            ItemStack slot = slots[i];
            if (slot == null || slot.isEmpty() || slot.getAmount() >= limit || !slot.isSimilar(template)) continue;
            int added = Math.min(limit - slot.getAmount(), remaining);
            ItemStack grown = slot.clone();
            grown.setAmount(slot.getAmount() + added);
            slots[i] = grown;
            remaining -= added;
            if (changed != null) changed[i] = true;
        }
        for (int i = 0; i < slots.length && remaining > 0; i++) {
            if (i == reservedSlot) continue;
            ItemStack slot = slots[i];
            if (slot != null && !slot.isEmpty()) continue;
            int put = Math.min(limit, remaining);
            ItemStack placed = template.clone();
            placed.setAmount(put);
            slots[i] = placed;
            remaining -= put;
            if (changed != null) changed[i] = true;
        }
        return amount - remaining;
    }
}
