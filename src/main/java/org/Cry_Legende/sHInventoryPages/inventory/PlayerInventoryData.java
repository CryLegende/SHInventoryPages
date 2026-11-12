package org.Cry_Legende.sHInventoryPages.inventory;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Pages d'un joueur. Thread principal uniquement, sauf {@link #snapshot()} qui fournit une copie indépendante
 * pour l'écriture asynchrone.
 *
 * <p>La page courante est le reflet de l'inventaire porté (emplacements 0 à 35) à la dernière capture : c'est
 * l'inventaire vivant qui fait foi pour elle. Les autres pages n'existent qu'ici et en base. Une page garde son
 * numéro pour toujours : une page au-delà du maximum est cachée, jamais renumérotée.</p>
 *
 * <p>Chaque modification d'une page augmente sa version ; une sauvegarde ne copie et n'écrit que les pages dont la
 * version n'a pas encore été validée en base. Une page illisible en base est verrouillée : jamais lue, jamais
 * réécrite, jamais utilisée pour ranger.</p>
 */
public class PlayerInventoryData {

    public static final int PAGE_SIZE = 36;
    /** Au-delà, le codec d'objets de Minecraft refuse la pile ("Value must be within range [1;99]"). */
    static final int SERIALIZABLE_MAX_AMOUNT = 99;

    private final UUID uuid;
    private int currentPage = 1;
    private int maxPages;
    private final Map<Integer, ItemStack[]> pages = new HashMap<>();
    private final Map<Integer, Integer> versions = new HashMap<>();
    private final Map<Integer, Integer> savedVersions = new HashMap<>();
    private final Set<Integer> lockedPages = new HashSet<>();
    /** Objets lus au-delà des 36 emplacements d'une page enregistrée, à rendre au joueur après le chargement. */
    private final List<ItemStack> strayItems = new ArrayList<>();
    /** Icône choisie par le joueur pour une page : objet d'origine réduit à son apparence. Absente : sélecteur par défaut. */
    private final Map<Integer, ItemStack> icons = new HashMap<>();
    /** Nom donné par le joueur à une page, affiché tel quel. */
    private final Map<Integer, String> names = new HashMap<>();
    /** Pages réservées par le joueur : jamais remplies par le rangement automatique (débordement, ramassage, dons). */
    private final Set<Integer> reservedPages = new HashSet<>();
    /** Copie de sauvegarde uniquement : version de chaque page copiée. */
    private Map<Integer, Integer> snapshotVersions = Map.of();
    /** Mode réseau, copie de sauvegarde uniquement : version de session du chargement qui l'a produite (-1 sinon). */
    private long epoch = -1L;

    public PlayerInventoryData(UUID uuid, int maxPages) {
        this.uuid = uuid;
        this.maxPages = Math.max(1, maxPages);
    }

    public UUID getUuid() {
        return uuid;
    }

    /** Mode réseau : version de session de la copie (voir SqlDatabase), -1 hors mode réseau. */
    public long getEpoch() {
        return epoch;
    }

    public void setEpoch(long epoch) {
        this.epoch = epoch;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    /** Page accessible et lisible uniquement. */
    public void setCurrentPage(int page) {
        if (page >= 1 && page <= maxPages && !lockedPages.contains(page)) {
            this.currentPage = page;
        }
    }

    public int getMaxPages() {
        return maxPages;
    }

    /**
     * Change le nombre de pages accessibles. Les pages au-delà restent en mémoire et en base, cachées. La page
     * courante n'est pas modifiée ici : si elle devient cachée ({@link #isCurrentPageHidden()}), l'appelant capture
     * l'inventaire porté puis porte une page accessible (InventoryManager), sans jamais renuméroter de page.
     */
    public void setMaxPages(int newMax) {
        this.maxPages = Math.max(1, newMax);
    }

    public boolean isCurrentPageHidden() {
        return currentPage > maxPages;
    }

    /** Page que le joueur peut porter : dans son nombre de pages, et lisible. */
    public boolean isAccessible(int pageNumber) {
        return pageNumber >= 1 && pageNumber <= maxPages && !lockedPages.contains(pageNumber);
    }

    // --- Réglages du joueur (jamais dans une copie de sauvegarde : ils ont leur propre écriture) ---

    /** Icône de la page, ou null pour le sélecteur par défaut. */
    public ItemStack getIcon(int pageNumber) {
        return icons.get(pageNumber);
    }

    public void setIcon(int pageNumber, ItemStack icon) {
        if (icon == null || icon.isEmpty()) icons.remove(pageNumber);
        else icons.put(pageNumber, icon);
    }

    /** Nom de la page, ou null. */
    public String getName(int pageNumber) {
        return names.get(pageNumber);
    }

    public void setName(int pageNumber, String name) {
        if (name == null || name.isBlank()) names.remove(pageNumber);
        else names.put(pageNumber, name);
    }

    public boolean isReserved(int pageNumber) {
        return reservedPages.contains(pageNumber);
    }

    public void setReserved(int pageNumber, boolean reserved) {
        if (reserved) reservedPages.add(pageNumber);
        else reservedPages.remove(pageNumber);
    }

    public ItemStack[] getPage(int pageNumber) {
        return pages.get(pageNumber);
    }

    /** Page courante ou accessible, créée vide si elle n'existe pas encore. */
    ItemStack[] page(int pageNumber) {
        ItemStack[] page = pages.get(pageNumber);
        if (page == null) {
            page = new ItemStack[PAGE_SIZE];
            pages.put(pageNumber, page);
            touch(pageNumber);
        }
        return page;
    }

    /**
     * Remplace une page accessible, ou la page courante même cachée (capture de l'inventaire porté). Une capture
     * identique ne compte pas comme une modification : rien à écrire.
     */
    public void setPage(int pageNumber, ItemStack[] contents) {
        if (lockedPages.contains(pageNumber)) return;
        if ((pageNumber >= 1 && pageNumber <= maxPages) || pageNumber == currentPage) {
            ItemStack[] normalized = normalize(contents);
            ItemStack[] previous = pages.put(pageNumber, normalized);
            if (previous == null || !sameContents(previous, normalized)) touch(pageNumber);
        }
    }

    private static boolean sameContents(ItemStack[] a, ItemStack[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            ItemStack x = a[i] == null || a[i].isEmpty() ? null : a[i];
            ItemStack y = b[i] == null || b[i].isEmpty() ? null : b[i];
            if (x == null ? y != null : !x.equals(y)) return false;
        }
        return true;
    }

    /**
     * Page lue en base : gardée même au-delà du maximum actuel, considérée comme déjà enregistrée, sauf si des
     * objets ont dû être retirés au-delà de ses 36 emplacements (la ligne doit alors être réécrite, sinon ils
     * seraient rendus à chaque connexion).
     */
    public void putStoredPage(int pageNumber, ItemStack[] contents) {
        if (pageNumber < 1) return;
        int strayBefore = strayItems.size();
        pages.put(pageNumber, normalize(contents));
        if (strayItems.size() > strayBefore) touch(pageNumber);
    }

    /** Chargement uniquement : page courante enregistrée en base, même cachée ou illisible (l'appelant tranche). */
    public void setLoadedCurrentPage(int page) {
        if (page >= 1) this.currentPage = page;
    }

    /** Vide un emplacement d'une page (objet déplacé ailleurs par l'appelant). */
    void clearSlot(int pageNumber, int slot) {
        ItemStack[] page = pages.get(pageNumber);
        if (page != null && page[slot] != null) {
            page[slot] = null;
            touch(pageNumber);
        }
    }

    /** Vue en lecture seule des pages (toutes ici ; seulement les pages à écrire dans une copie de sauvegarde). */
    public Map<Integer, ItemStack[]> pagesView() {
        return Collections.unmodifiableMap(pages);
    }

    // --- Pages illisibles ---

    public void lockPage(int pageNumber) {
        lockedPages.add(pageNumber);
        pages.remove(pageNumber);
    }

    public void unlockPage(int pageNumber) {
        lockedPages.remove(pageNumber);
    }

    public boolean isLocked(int pageNumber) {
        return lockedPages.contains(pageNumber);
    }

    public Set<Integer> lockedPagesView() {
        return Collections.unmodifiableSet(lockedPages);
    }

    // --- Versions ---

    private void touch(int pageNumber) {
        versions.merge(pageNumber, 1, Integer::sum);
    }

    private boolean isDirty(int pageNumber) {
        return !versions.getOrDefault(pageNumber, 0).equals(savedVersions.getOrDefault(pageNumber, 0));
    }

    /** Versions des pages d'une copie de sauvegarde. */
    public Map<Integer, Integer> snapshotVersions() {
        return snapshotVersions;
    }

    /** Après une écriture validée : ces versions sont en base. */
    public void markSaved(Map<Integer, Integer> written) {
        written.forEach((page, version) -> savedVersions.merge(page, version, Math::max));
    }

    int versionOf(int pageNumber) {
        return versions.getOrDefault(pageNumber, 0);
    }

    /** Vrai si la version {@code version} de la page (ou une plus récente) est validée en base. */
    boolean isSavedAtLeast(int pageNumber, int version) {
        return savedVersions.getOrDefault(pageNumber, 0) >= version;
    }

    /** Versions au dernier enregistrement aligné (fichier joueur + base) après un changement de page. */
    private final Map<Integer, Integer> alignedVersions = new HashMap<>();

    /** La page a changé depuis le dernier enregistrement aligné : un objet a pu en sortir vers une autre page. */
    boolean changedSinceAligned(int pageNumber) {
        return !versions.getOrDefault(pageNumber, 0).equals(alignedVersions.getOrDefault(pageNumber, 0));
    }

    void markAligned() {
        alignedVersions.clear();
        alignedVersions.putAll(versions);
    }

    private ItemStack[] normalize(ItemStack[] contents) {
        if (contents == null) return new ItemStack[PAGE_SIZE];
        if (contents.length == PAGE_SIZE) return contents;
        ItemStack[] page = Arrays.copyOf(contents, PAGE_SIZE);
        for (int i = PAGE_SIZE; i < contents.length; i++) {
            if (contents[i] != null && !contents[i].isEmpty()) strayItems.add(contents[i]);
        }
        return page;
    }

    /** Objets lus hors des 36 emplacements, retirés des données : à rendre au joueur. */
    List<ItemStack> drainStrayItems() {
        List<ItemStack> drained = new ArrayList<>(strayItems);
        strayItems.clear();
        return drained;
    }

    /** Retire des pages enregistrées (pas de la page courante) les objets qui vérifient {@code filter}. */
    int removeStoredItems(Predicate<ItemStack> filter) {
        int removed = 0;
        for (Map.Entry<Integer, ItemStack[]> entry : pages.entrySet()) {
            if (entry.getKey() == currentPage) continue;
            ItemStack[] page = entry.getValue();
            boolean changed = false;
            for (int i = 0; i < page.length; i++) {
                if (page[i] != null && filter.test(page[i])) {
                    page[i] = null;
                    removed++;
                    changed = true;
                }
            }
            if (changed) touch(entry.getKey());
        }
        return removed;
    }

    private boolean isStorable(int pageNumber) {
        return pageNumber != currentPage && !lockedPages.contains(pageNumber);
    }

    /** Page où le rangement automatique peut poser un objet : ni la courante, ni illisible, ni réservée par le joueur. */
    private boolean isFillable(int pageNumber) {
        return isStorable(pageNumber) && !reservedPages.contains(pageNumber);
    }

    /** Place libre pour {@code template} sur les pages accessibles autres que la courante, pages réservées exclues. */
    int storedCapacity(ItemStack template, int reservedSlot) {
        int free = 0;
        for (int n = 1; n <= maxPages; n++) {
            if (!isFillable(n)) continue;
            ItemStack[] page = pages.get(n);
            free += SlotFiller.capacity(page != null ? page : new ItemStack[PAGE_SIZE], template, reservedSlot);
        }
        return free;
    }

    /** Résultat d'un remplissage des pages enregistrées. */
    record StoredFill(int placed, int firstPage) {
    }

    /**
     * Range au plus {@code amount} exemplaires dans les pages accessibles autres que la courante, par numéro
     * croissant : piles partielles puis emplacements vides d'une page, puis de la suivante. Une page réservée par
     * le joueur est sautée.
     */
    StoredFill fillStoredPages(ItemStack template, int amount, int reservedSlot) {
        int remaining = amount;
        int firstPage = -1;
        for (int n = 1; n <= maxPages && remaining > 0; n++) {
            if (!isFillable(n)) continue;
            ItemStack[] page = pages.get(n);
            boolean created = page == null;
            if (created) page = new ItemStack[PAGE_SIZE];
            int put = SlotFiller.fill(page, template, remaining, reservedSlot, null);
            if (put > 0) {
                if (created) pages.put(n, page);
                touch(n);
                if (firstPage < 0) firstPage = n;
                remaining -= put;
            }
        }
        return new StoredFill(amount - remaining, firstPage);
    }

    /**
     * Vide les pages accessibles autres que la courante (mort en mode ALL).
     *
     * @return leurs objets
     */
    List<ItemStack> drainAccessibleStoredPages() {
        List<ItemStack> drained = new ArrayList<>();
        for (int n = 1; n <= maxPages; n++) {
            if (!isStorable(n)) continue;
            ItemStack[] page = pages.get(n);
            if (page == null) continue;
            boolean changed = false;
            for (int i = 0; i < page.length; i++) {
                if (page[i] != null && !page[i].isEmpty()) {
                    drained.add(page[i]);
                    changed = true;
                }
                page[i] = null;
            }
            if (changed) touch(n);
        }
        return drained;
    }

    /** Exemplaires de {@code template} dans les pages accessibles autres que la courante. */
    int countStored(ItemStack template) {
        int count = 0;
        for (int n = 1; n <= maxPages; n++) {
            if (!isStorable(n)) continue;
            ItemStack[] page = pages.get(n);
            if (page == null) continue;
            for (ItemStack item : page) {
                if (item != null && item.isSimilar(template)) count += item.getAmount();
            }
        }
        return count;
    }

    /** Retire au plus {@code amount} exemplaires de {@code template} des pages accessibles autres que la courante. */
    int removeStored(ItemStack template, int amount) {
        int remaining = amount;
        for (int n = 1; n <= maxPages && remaining > 0; n++) {
            if (!isStorable(n)) continue;
            ItemStack[] page = pages.get(n);
            if (page == null) continue;
            boolean changed = false;
            for (int i = 0; i < page.length && remaining > 0; i++) {
                ItemStack item = page[i];
                if (item == null || !item.isSimilar(template)) continue;
                int taken = Math.min(item.getAmount(), remaining);
                remaining -= taken;
                if (taken == item.getAmount()) {
                    page[i] = null;
                } else {
                    ItemStack rest = item.clone();
                    rest.setAmount(item.getAmount() - taken);
                    page[i] = rest;
                }
                changed = true;
            }
            if (changed) touch(n);
        }
        return amount - remaining;
    }

    /** Pages accessibles et lisibles, dans l'ordre de passage du sélecteur. */
    int nextAccessiblePage(int from, boolean forward) {
        for (int step = 1; step <= maxPages; step++) {
            int candidate = forward
                ? (from - 1 + step) % maxPages + 1
                : ((from - 1 - step) % maxPages + maxPages) % maxPages + 1;
            if (!lockedPages.contains(candidate)) return candidate;
        }
        return -1;
    }

    /**
     * Une pile de plus de 99 objets ne s'enregistre pas et ferait échouer la sauvegarde de toutes les pages du
     * joueur. Chaque pile des pages enregistrées qui dépasse la taille de pile de son objet est ramenée à cette
     * taille ; l'excédent est rangé dans les autres pages accessibles. La page courante n'est jamais touchée ici :
     * elle reflète l'inventaire réel, corrigé à chaque capture (la recouper sans corriger l'inventaire réel
     * recréait l'excédent à chaque sauvegarde).
     *
     * @return les piles qui n'ont trouvé aucune place, vide dans le cas normal
     */
    List<ItemStack> splitOversizedStacks(int reservedSlot) {
        List<ItemStack> extra = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack[]> entry : pages.entrySet()) {
            if (entry.getKey() == currentPage) continue;
            ItemStack[] page = entry.getValue();
            boolean changed = false;
            for (int i = 0; i < page.length; i++) {
                ItemStack item = page[i];
                if (item == null) continue;
                int limit = SlotFiller.stackLimit(item);
                if (item.getAmount() <= limit) continue;
                extra.addAll(excessParts(item, limit));
                ItemStack first = item.clone();
                first.setAmount(limit);
                page[i] = first;
                changed = true;
            }
            if (changed) touch(entry.getKey());
        }
        List<ItemStack> unplaced = new ArrayList<>();
        for (ItemStack part : extra) {
            int placed = fillStoredPages(part, part.getAmount(), reservedSlot).placed();
            if (placed < part.getAmount()) {
                ItemStack left = part.clone();
                left.setAmount(part.getAmount() - placed);
                unplaced.add(left);
            }
        }
        return unplaced;
    }

    /** Ce qui dépasse {@code limit} dans {@code item}, en piles de {@code limit} objets au plus. */
    static List<ItemStack> excessParts(ItemStack item, int limit) {
        List<ItemStack> parts = new ArrayList<>();
        int remaining = item.getAmount() - limit;
        while (remaining > 0) {
            ItemStack part = item.clone();
            part.setAmount(Math.min(limit, remaining));
            parts.add(part);
            remaining -= part.getAmount();
        }
        return parts;
    }

    /** Vrai si une page autre que la page portée (cachée comprise) contient un objet. */
    public boolean hasStoredContent() {
        for (Map.Entry<Integer, ItemStack[]> entry : pages.entrySet()) {
            if (entry.getKey() == currentPage) continue;
            for (ItemStack item : entry.getValue()) {
                if (item != null && !item.isEmpty()) return true;
            }
        }
        return false;
    }

    /** Copie indépendante de toutes les pages lisibles (cachées comprises), objets clonés : historique des pages. */
    public Map<Integer, ItemStack[]> copyAllPages() {
        Map<Integer, ItemStack[]> copy = new HashMap<>();
        for (Map.Entry<Integer, ItemStack[]> entry : pages.entrySet()) {
            ItemStack[] source = entry.getValue();
            ItemStack[] items = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) {
                items[i] = source[i] != null && !source[i].isEmpty() ? source[i].clone() : null;
            }
            copy.put(entry.getKey(), items);
        }
        return copy;
    }

    /**
     * Remplace une page autre que la page portée, même cachée ou verrouillée comme illisible : page reprise d'un
     * fichier de secours (plus récente que la base) ou copie de l'historique restaurée. Elle sera écrite à la
     * prochaine sauvegarde.
     */
    public void replaceStoredPage(int pageNumber, ItemStack[] contents) {
        if (pageNumber < 1 || pageNumber == currentPage) return;
        lockedPages.remove(pageNumber);
        pages.put(pageNumber, normalize(contents));
        touch(pageNumber);
    }

    /**
     * Copie indépendante des seules pages à écrire (modifiées depuis la dernière écriture validée), pour une
     * sauvegarde asynchrone : le thread d'écriture ne parcourt jamais les structures du thread principal.
     */
    public PlayerInventoryData snapshot() {
        PlayerInventoryData copy = new PlayerInventoryData(uuid, maxPages);
        copy.currentPage = currentPage;
        copy.lockedPages.addAll(lockedPages);
        Map<Integer, Integer> copied = new HashMap<>();
        for (Map.Entry<Integer, ItemStack[]> entry : pages.entrySet()) {
            int number = entry.getKey();
            if (!isDirty(number)) continue;
            ItemStack[] source = entry.getValue();
            ItemStack[] items = new ItemStack[source.length];
            for (int i = 0; i < source.length; i++) {
                items[i] = source[i] != null ? source[i].clone() : null;
            }
            copy.pages.put(number, items);
            copied.put(number, versions.getOrDefault(number, 0));
        }
        copy.snapshotVersions = copied;
        return copy;
    }
}
