package org.Cry_Legende.sHInventoryPages.history;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.Cry_Legende.sHInventoryPages.util.ItemCodec;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Historique des pages : copies de toutes les pages d'un joueur aux mêmes moments qu'InventoryRollbackPlus pour
 * l'inventaire porté (connexion, déconnexion, mort), toutes les {@code history.periodic_minutes} minutes, et avant
 * toute opération qui touche à toutes les pages (vente de toutes les pages) ou restauration par le staff. Une copie
 * dont la seule page remplie est la page portée n'est pas gardée : InventoryRollbackPlus l'a déjà.
 *
 * <p>Les copies sont prises sur le fil du joueur (objets clonés) puis encodées et écrites sur un fil dédié, dans
 * l'ordre où elles ont été prises.</p>
 */
public final class PageHistory {

    /** Moment d'une copie ; la clé sert dans la config (history.max_copies) et en base. */
    public enum Reason {
        JOIN(true),
        QUIT(true),
        DEATH(false),
        PERIODIC(true),
        OPERATION(false),
        RESTORE(false);

        /** Copie identique à la précédente du joueur : pas gardée une seconde fois. */
        private final boolean skipDuplicate;

        Reason(boolean skipDuplicate) {
            this.skipDuplicate = skipDuplicate;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Reason of(String key) {
            for (Reason reason : values()) {
                if (reason.key().equalsIgnoreCase(key)) return reason;
            }
            return null;
        }
    }

    private final SHInventoryPages plugin;
    private final ConfigManager config;
    private final ExecutorService executor;
    /** Empreinte de la dernière copie de chaque joueur (lue en base à la première copie de la session). */
    private final Map<UUID, String> lastHash = new ConcurrentHashMap<>();
    private volatile boolean enabled;

    public PageHistory(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SHInventoryPages-History");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Crée la table si besoin ; l'historique reste coupé si elle est inutilisable ou si history.enabled vaut false. */
    public void start() {
        enabled = config.isHistoryEnabled() && plugin.getDatabaseManager().createHistoryTable();
    }

    /** /ship reload : history.enabled relu (la table existe déjà ou est créée à ce moment). */
    public void reload() {
        start();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Copie des pages (objets déjà clonés par l'appelant, page portée comprise), écrite plus tard sur le fil de
     * l'historique. Sans effet si l'historique est coupé ou si ce moment ne garde aucune copie.
     */
    public void record(PlayerInventoryData data, Map<Integer, ItemStack[]> pages, Reason reason) {
        if (!enabled) return;
        int keep = config.getHistoryMaxCopies(reason.key());
        if (keep <= 0) return;
        UUID uuid = data.getUuid();
        int current = data.getCurrentPage();
        int maxPages = data.getMaxPages();
        long created = System.currentTimeMillis();
        if (reason != Reason.RESTORE && !hasStoredContent(pages, current)) return;
        try {
            executor.execute(() -> write(uuid, created, reason, current, maxPages, pages, keep));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Plugin arrêté : plus d'écriture possible
        }
    }

    private static boolean hasStoredContent(Map<Integer, ItemStack[]> pages, int current) {
        for (Map.Entry<Integer, ItemStack[]> page : pages.entrySet()) {
            if (page.getKey() == current) continue;
            for (ItemStack item : page.getValue()) {
                if (item != null && !item.isEmpty()) return true;
            }
        }
        return false;
    }

    private void write(UUID uuid, long created, Reason reason, int current, int maxPages, Map<Integer, ItemStack[]> pages, int keep) {
        try {
            StringBuilder data = new StringBuilder();
            int slots = 0;
            int items = 0;
            for (Map.Entry<Integer, ItemStack[]> page : new TreeMap<>(pages).entrySet()) {
                for (ItemStack item : page.getValue()) {
                    if (item == null || item.isEmpty()) continue;
                    slots++;
                    items += item.getAmount();
                }
                data.append(page.getKey()).append('=').append(ItemCodec.encodePage(page.getValue(), plugin.getLogger())).append('\n');
            }
            String hash = sha256(current + "|" + data);
            if (reason.skipDuplicate) {
                String last = lastHash.get(uuid);
                if (last == null) last = plugin.getDatabaseManager().lastHistoryHash(uuid);
                if (hash.equals(last)) {
                    lastHash.put(uuid, hash);
                    return;
                }
            }
            plugin.getDatabaseManager().insertHistory(new HistoryRecord(0L, uuid, created, reason.key(), current, maxPages,
                slots, items, hash, data.toString()), keep);
            lastHash.put(uuid, hash);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Copie de l'historique des pages de " + uuid + " (" + reason.key() + ") non écrite", e);
        }
    }

    /** Fin de session : l'empreinte connue n'est plus utile (relue en base à la prochaine connexion). */
    public void forget(UUID uuid) {
        try {
            executor.execute(() -> lastHash.remove(uuid));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            lastHash.remove(uuid);
        }
    }

    /** Copies plus vieilles que history.retention_days supprimées (tâche horaire). */
    public void purgeOld() {
        if (!enabled) return;
        long before = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(config.getHistoryRetentionDays());
        try {
            executor.execute(() -> {
                try {
                    int removed = plugin.getDatabaseManager().purgeHistory(before);
                    if (removed > 0) plugin.getLogger().info(removed + " copie(s) de l'historique des pages supprimée(s) (trop anciennes)");
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Nettoyage de l'historique des pages échoué", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Plugin arrêté
        }
    }

    /** Liste des copies d'un joueur (fil de l'historique). */
    public CompletableFuture<List<HistoryRecord>> list(UUID uuid, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return plugin.getDatabaseManager().listHistory(uuid, offset, limit);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, executor);
    }

    /** Copie complète, pages décodées (fil de l'historique) ; null si elle n'existe plus. */
    public CompletableFuture<LoadedCopy> load(long id) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HistoryRecord record = plugin.getDatabaseManager().loadHistory(id);
                return record == null ? null : new LoadedCopy(record, decode(record.data()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, executor);
    }

    /** Copie lue en base avec ses pages. */
    public record LoadedCopy(HistoryRecord record, Map<Integer, ItemStack[]> pages) {
    }

    private static Map<Integer, ItemStack[]> decode(String data) throws java.io.IOException {
        Map<Integer, ItemStack[]> pages = new TreeMap<>();
        if (data == null) return pages;
        for (String line : data.split("\n")) {
            int separator = line.indexOf('=');
            if (separator <= 0) continue;
            int page = Integer.parseInt(line.substring(0, separator));
            pages.put(page, ItemCodec.decodePage(line.substring(separator + 1), PlayerInventoryData.PAGE_SIZE));
        }
        return pages;
    }

    /** Arrêt du plugin : les copies en file (déconnexions de l'arrêt) sont écrites avant la fermeture de la base. */
    public void shutdown(long timeoutSeconds) {
        enabled = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Copies de l'historique des pages encore en file à l'arrêt : abandonnées");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
