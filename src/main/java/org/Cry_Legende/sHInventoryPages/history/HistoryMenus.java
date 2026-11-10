package org.Cry_Legende.sHInventoryPages.history;

import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.inventory.InventoryManager;
import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Menus de l'historique des pages ({@code /ship history <joueur>}) : liste des copies d'un joueur, puis contenu de
 * chaque page d'une copie. Avec {@code shinventorypages.history.restore}, un clic sur un objet en donne une copie au
 * membre du staff, et shift + clic sur le bouton de restauration remplace la page du joueur par celle de la copie.
 */
public final class HistoryMenus implements Listener {

    /** Copies par page de la liste (5 lignes). */
    private static final int PER_PAGE = 45;
    private static final int SLOT_PREVIOUS = 45;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PAGE_PREVIOUS = 47;
    private static final int SLOT_PAGE_NEXT = 51;
    private static final int SLOT_RESTORE = 53;

    private final SHInventoryPages plugin;
    private final ConfigManager config;

    public HistoryMenus(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    /** Menu ouvert : liste des copies d'un joueur, ou contenu d'une copie. */
    private static final class Menu implements InventoryHolder {
        final UUID target;
        final String targetName;
        final int listPage;
        final List<HistoryRecord> records;
        final PageHistory.LoadedCopy copy;
        final List<Integer> pageNumbers;
        final int shown;
        Inventory inventory;

        Menu(UUID target, String targetName, int listPage, List<HistoryRecord> records, PageHistory.LoadedCopy copy,
             List<Integer> pageNumbers, int shown) {
            this.target = target;
            this.targetName = targetName;
            this.listPage = listPage;
            this.records = records;
            this.copy = copy;
            this.pageNumbers = pageNumbers;
            this.shown = shown;
        }

        boolean isList() {
            return copy == null;
        }

        int shownPage() {
            return pageNumbers.get(shown);
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    // =========================================================================
    //  OUVERTURE
    // =========================================================================

    /** Liste des copies de {@code target}, lue en base hors du thread principal puis ouverte au staff. */
    public void openList(Player staff, UUID target, String targetName, int listPage) {
        PageHistory history = plugin.getPageHistory();
        if (history == null || !history.isEnabled()) {
            MessageUtil.sendMessage(staff, config.getMessage("history_disabled"));
            return;
        }
        UUID staffId = staff.getUniqueId();
        history.list(target, listPage * PER_PAGE, PER_PAGE + 1).whenComplete((records, error) -> runForStaff(staffId, viewer -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Lecture de l'historique des pages de " + targetName + " échouée", error);
                MessageUtil.sendMessage(viewer, config.getMessage("history_failed"));
                return;
            }
            if (records.isEmpty() && listPage == 0) {
                MessageUtil.sendMessage(viewer, config.getMessage("history_empty", "%player%", targetName));
                return;
            }
            Menu menu = new Menu(target, targetName, listPage, records, null, List.of(), 0);
            menu.inventory = Bukkit.createInventory(menu, 54, MessageUtil.component(config.getMessage("history_list_title",
                "%player%", targetName, "%page%", String.valueOf(listPage + 1))));
            renderList(menu);
            viewer.openInventory(menu.inventory);
        }));
    }

    private void openCopy(Player staff, Menu from, long id) {
        UUID staffId = staff.getUniqueId();
        plugin.getPageHistory().load(id).whenComplete((copy, error) -> runForStaff(staffId, viewer -> {
            if (error != null || copy == null) {
                if (error != null) plugin.getLogger().log(Level.WARNING, "Lecture de la copie " + id + " de l'historique échouée", error);
                MessageUtil.sendMessage(viewer, config.getMessage("history_failed"));
                return;
            }
            List<Integer> numbers = new ArrayList<>(copy.pages().keySet());
            if (numbers.isEmpty()) {
                MessageUtil.sendMessage(viewer, config.getMessage("history_failed"));
                return;
            }
            // Première page affichée : la première page remplie autre que la page portée (celle qu'on vient chercher)
            int shown = 0;
            for (int i = 0; i < numbers.size(); i++) {
                if (numbers.get(i) != copy.record().currentPage() && !isEmpty(copy.pages().get(numbers.get(i)))) {
                    shown = i;
                    break;
                }
            }
            showCopy(viewer, new Menu(from.target, from.targetName, from.listPage, from.records, copy, numbers, shown));
        }));
    }

    private void showCopy(Player staff, Menu menu) {
        menu.inventory = Bukkit.createInventory(menu, 54, MessageUtil.component(config.getMessage("history_view_title",
            "%player%", menu.targetName, "%page%", String.valueOf(menu.shownPage()), "%date%", date(menu.copy.record().created()))));
        renderCopy(staff, menu);
        staff.openInventory(menu.inventory);
    }

    /** Suite d'une lecture en base, sur le thread principal, si le membre du staff est toujours là. */
    private void runForStaff(UUID staffId, java.util.function.Consumer<Player> action) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player staff = Bukkit.getPlayer(staffId);
            if (staff != null) action.accept(staff);
        });
    }

    // =========================================================================
    //  AFFICHAGE
    // =========================================================================

    private void renderList(Menu menu) {
        Inventory inv = menu.inventory;
        for (int i = 0; i < Math.min(PER_PAGE, menu.records.size()); i++) {
            HistoryRecord record = menu.records.get(i);
            PageHistory.Reason reason = PageHistory.Reason.of(record.reason());
            String reasonLabel = config.getRawMessage("history_reason_" + record.reason());
            List<String> lore = new ArrayList<>();
            for (String line : config.getRawMessageList("history_entry_lore")) {
                lore.add(line.replace("%reason%", reasonLabel)
                    .replace("%worn%", String.valueOf(record.currentPage()))
                    .replace("%max_pages%", String.valueOf(record.maxPages()))
                    .replace("%slots%", String.valueOf(record.slots()))
                    .replace("%items%", String.valueOf(record.items())));
            }
            inv.setItem(i, item(icon(reason), config.getRawMessage("history_entry_name").replace("%date%", date(record.created())), lore));
        }
        if (menu.listPage > 0) inv.setItem(SLOT_PREVIOUS, button("history_button_previous", Material.ARROW));
        if (menu.records.size() > PER_PAGE) inv.setItem(SLOT_NEXT, button("history_button_next", Material.ARROW));
        inv.setItem(SLOT_INFO, item(Material.BOOK, config.getRawMessage("history_info_name").replace("%player%", menu.targetName),
            config.getRawMessageList("history_info_lore")));
    }

    private void renderCopy(Player staff, Menu menu) {
        Inventory inv = menu.inventory;
        ItemStack[] page = menu.copy.pages().get(menu.shownPage());
        // Inventaire du joueur : lignes 9 à 35 en haut, barre d'action (0 à 8) en dessous, comme à l'écran
        for (int slot = 0; slot < PlayerInventoryData.PAGE_SIZE; slot++) {
            ItemStack item = page != null && slot < page.length ? page[slot] : null;
            int display = slot < 9 ? 27 + slot : slot - 9;
            inv.setItem(display, item != null && !item.isEmpty() ? item.clone() : null);
        }
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 36; slot < 45; slot++) inv.setItem(slot, filler);
        inv.setItem(SLOT_BACK, button("history_button_back", Material.ARROW));
        if (menu.shown > 0) inv.setItem(SLOT_PAGE_PREVIOUS, button("history_button_page_previous", Material.SPECTRAL_ARROW));
        if (menu.shown < menu.pageNumbers.size() - 1) inv.setItem(SLOT_PAGE_NEXT, button("history_button_page_next", Material.SPECTRAL_ARROW));
        HistoryRecord record = menu.copy.record();
        List<String> lore = new ArrayList<>();
        for (String line : config.getRawMessageList("history_page_lore")) {
            lore.add(line.replace("%worn%", String.valueOf(record.currentPage()))
                .replace("%date%", date(record.created()))
                .replace("%reason%", config.getRawMessage("history_reason_" + record.reason())));
        }
        String pageName = config.getRawMessage(menu.shownPage() == record.currentPage() ? "history_page_name_worn" : "history_page_name")
            .replace("%page%", String.valueOf(menu.shownPage()));
        inv.setItem(SLOT_INFO, item(Material.BOOK, pageName, lore));
        if (staff.hasPermission("shinventorypages.history.restore")) {
            List<String> restoreLore = new ArrayList<>();
            for (String line : config.getRawMessageList("history_button_restore_lore")) {
                restoreLore.add(line.replace("%page%", String.valueOf(menu.shownPage())).replace("%player%", menu.targetName));
            }
            inv.setItem(SLOT_RESTORE, item(Material.ENDER_CHEST, config.getRawMessage("history_button_restore"), restoreLore));
        }
    }

    // =========================================================================
    //  CLICS
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player staff) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        // Clics simples seulement : un double clic ou une touche ne déclenche jamais deux actions
        if (!event.getClick().isLeftClick() && !event.getClick().isRightClick()) return;
        if (event.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) return;
        int slot = event.getRawSlot();
        if (!staff.hasPermission("shinventorypages.history")) {
            staff.closeInventory();
            return;
        }
        if (menu.isList()) {
            if (slot == SLOT_PREVIOUS && menu.listPage > 0) {
                openList(staff, menu.target, menu.targetName, menu.listPage - 1);
            } else if (slot == SLOT_NEXT && menu.records.size() > PER_PAGE) {
                openList(staff, menu.target, menu.targetName, menu.listPage + 1);
            } else if (slot < PER_PAGE && slot < menu.records.size()) {
                openCopy(staff, menu, menu.records.get(slot).id());
            }
            return;
        }
        if (slot == SLOT_BACK) {
            openList(staff, menu.target, menu.targetName, menu.listPage);
        } else if (slot == SLOT_PAGE_PREVIOUS && menu.shown > 0) {
            showCopy(staff, new Menu(menu.target, menu.targetName, menu.listPage, menu.records, menu.copy, menu.pageNumbers, menu.shown - 1));
        } else if (slot == SLOT_PAGE_NEXT && menu.shown < menu.pageNumbers.size() - 1) {
            showCopy(staff, new Menu(menu.target, menu.targetName, menu.listPage, menu.records, menu.copy, menu.pageNumbers, menu.shown + 1));
        } else if (slot == SLOT_RESTORE) {
            restore(staff, menu, event.isShiftClick());
        } else if (slot < 36) {
            copyItem(staff, menu, slot);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) event.setCancelled(true);
    }

    /** Copie d'un objet de la page affichée dans l'inventaire du membre du staff. */
    private void copyItem(Player staff, Menu menu, int displaySlot) {
        if (!staff.hasPermission("shinventorypages.history.restore")) return;
        int slot = displaySlot >= 27 ? displaySlot - 27 : displaySlot + 9;
        ItemStack[] page = menu.copy.pages().get(menu.shownPage());
        ItemStack item = page != null && slot < page.length ? page[slot] : null;
        if (item == null || item.isEmpty()) return;
        // Une pile entière dans un emplacement vide, ou rien
        if (staff.getInventory().firstEmpty() < 0) {
            MessageUtil.sendMessage(staff, config.getMessage("history_copy_no_room"));
            return;
        }
        staff.getInventory().addItem(item.clone());
        plugin.getLogger().warning(staff.getName() + " copie " + item.getAmount() + "x " + item.getType() + " de la page "
            + menu.shownPage() + " de " + menu.targetName + " (copie " + menu.copy.record().id() + " de l'historique)");
        MessageUtil.sendMessage(staff, config.getMessage("history_copied", "%amount%", String.valueOf(item.getAmount())),
            "%item%", item.effectiveName());
    }

    private void restore(Player staff, Menu menu, boolean confirmed) {
        if (!staff.hasPermission("shinventorypages.history.restore")) return;
        if (!confirmed) {
            MessageUtil.sendMessage(staff, config.getMessage("history_restore_confirm"));
            return;
        }
        int page = menu.shownPage();
        ItemStack[] contents = menu.copy.pages().get(page);
        if (contents == null) contents = new ItemStack[PlayerInventoryData.PAGE_SIZE];
        staff.closeInventory();
        UUID staffId = staff.getUniqueId();
        plugin.getLogger().warning(staff.getName() + " restaure la page " + page + " de " + menu.targetName
            + " depuis la copie " + menu.copy.record().id() + " de l'historique");
        plugin.getInventoryManager().restorePage(menu.target, page, contents).thenAccept(result -> {
            Player viewer = Bukkit.getPlayer(staffId);
            if (viewer == null) return;
            String key = switch (result) {
                case DONE -> "history_restore_done";
                case WORN_PAGE_OFFLINE -> "history_restore_worn_offline";
                case NETWORK_OFFLINE -> "history_restore_network_offline";
                case LOADING, BUSY -> "history_restore_busy";
                case FAILED -> "history_restore_failed";
            };
            MessageUtil.sendMessage(viewer, config.getMessage(key, "%page%", String.valueOf(page), "%player%", menu.targetName));
        });
    }

    // =========================================================================
    //  OBJETS DU MENU
    // =========================================================================

    private Material icon(PageHistory.Reason reason) {
        String name = config.getHistoryIcon(reason == null ? "periodic" : reason.key());
        Material material = name == null ? null : Material.matchMaterial(name);
        return material != null && material.isItem() && !material.isAir() ? material : Material.PAPER;
    }

    private ItemStack button(String key, Material material) {
        return item(material, config.getRawMessage(key), List.of());
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageUtil.itemText(name));
        if (!lore.isEmpty()) meta.lore(MessageUtil.itemLines(lore));
        item.setItemMeta(meta);
        return item;
    }

    private String date(long millis) {
        return new SimpleDateFormat(config.getHistoryDateFormat()).format(new Date(millis));
    }

    private static boolean isEmpty(ItemStack[] page) {
        if (page == null) return true;
        for (ItemStack item : page) {
            if (item != null && !item.isEmpty()) return false;
        }
        return true;
    }
}
