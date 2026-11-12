package org.Cry_Legende.sHInventoryPages.menu;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.api.event.PlayerPageSwitchEvent;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.inventory.InventoryManager;
import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Menu des pages d'un joueur (/pages, shift + clic sur le sélecteur) : une case par page, clic pour la porter. Avec
 * la personnalisation, chaque page a ses réglages : une icône, prise sur un objet de l'inventaire du joueur (jamais
 * consommé), et la réserve, qui la sort du rangement automatique.
 *
 * <p>Le menu ne contient aucun objet du joueur : tous les clics y sont annulés, et l'inventaire du joueur affiché en
 * dessous ne sert qu'à désigner l'objet dont une page prend l'apparence.</p>
 */
public final class PagesMenu implements Listener {

    public static final String PERMISSION = "shinventorypages.menu";
    public static final String PERMISSION_CUSTOMIZE = "shinventorypages.menu.customize";

    /** Quatre lignes de sept cases au plus, dans le cadre. */
    private static final int MAX_SHOWN = 28;
    private static final int SETTINGS_SIZE = 27;
    private static final int SLOT_ICON = 11;
    private static final int SLOT_RESERVED = 13;
    private static final int SLOT_RESET = 15;
    private static final int SLOT_BACK = 22;
    /** Emplacements d'une page utilisables par le joueur : tous sauf celui du sélecteur. */
    private static final int CAPACITY = PlayerInventoryData.PAGE_SIZE - 1;

    private final SHInventoryPages plugin;
    private final ConfigManager config;
    private final InventoryManager manager;

    public PagesMenu(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.manager = plugin.getInventoryManager();
    }

    /** Menu ouvert : la liste des pages, ou les réglages d'une page. */
    private static final class Menu implements InventoryHolder {
        final UUID owner;
        /** 0 : liste des pages ; sinon page dont les réglages sont affichés. */
        final int settingsPage;
        /** Liste en mode personnalisation : un clic ouvre les réglages de la page au lieu de la porter. */
        boolean customizing;
        /** Case de la liste vers numéro de page. */
        final Map<Integer, Integer> pageSlots = new HashMap<>();
        int customizeSlot = -1;
        Inventory inventory;

        Menu(UUID owner, int settingsPage, boolean customizing) {
            this.owner = owner;
            this.settingsPage = settingsPage;
            this.customizing = customizing;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    /** Vrai si la permission n'est pas refusée au joueur : accordée, ou jamais réglée pour lui. */
    public static boolean allowed(Player player, String permission) {
        return !player.isPermissionSet(permission) || player.hasPermission(permission);
    }

    private boolean canCustomize(Player player) {
        return config.isMenuCustomization() && allowed(player, PERMISSION_CUSTOMIZE);
    }

    // =========================================================================
    //  OUVERTURE
    // =========================================================================

    /** Ouvre la liste des pages, si le joueur peut changer de page ici. */
    public void open(Player player) {
        if (!config.isMenuEnabled() || !allowed(player, PERMISSION)) {
            MessageUtil.sendMessage(player, config.getMessage("no_permission_command"));
            return;
        }
        if (manager.getPlayerData(player.getUniqueId()) == null) {
            MessageUtil.sendMessage(player, config.getMessage("data_loading"));
            return;
        }
        if (!manager.canSwitchPages(player)) {
            manager.playFailSound(player);
            MessageUtil.sendMessage(player, config.getMessage("cant_switch_here"));
            return;
        }
        openList(player, false);
        play(player, "open", "ui.toast.in");
    }

    private void openList(Player player, boolean customizing) {
        PlayerInventoryData data = manager.getPlayerData(player.getUniqueId());
        if (data == null) return;
        Menu menu = new Menu(player.getUniqueId(), 0, customizing && canCustomize(player));
        int shown = shownPages(data);
        int columns = columns(shown);
        int rows = (shown + columns - 1) / columns;
        menu.inventory = Bukkit.createInventory(menu, (rows + 2) * 9, MessageUtil.itemText(config.getRawMessage("pages_menu_title")));
        renderList(player, menu, data);
        player.openInventory(menu.inventory);
    }

    private void openSettings(Player player, int page, boolean fromCustomizing) {
        PlayerInventoryData data = manager.getPlayerData(player.getUniqueId());
        if (data == null || page < 1 || page > data.getMaxPages()) return;
        Menu menu = new Menu(player.getUniqueId(), page, fromCustomizing);
        menu.inventory = Bukkit.createInventory(menu, SETTINGS_SIZE, MessageUtil.itemText(
            config.getRawMessage("pages_menu_settings_title").replace("%page%", String.valueOf(page))));
        renderSettings(menu, data);
        player.openInventory(menu.inventory);
    }

    /** Pages affichées : celles du joueur, et les pages pas encore débloquées si menu.show_locked_pages. */
    private int shownPages(PlayerInventoryData data) {
        int pages = config.isMenuShowLockedPages() ? Math.max(data.getMaxPages(), config.getMaxPossiblePages()) : data.getMaxPages();
        return Math.max(1, Math.min(MAX_SHOWN, pages));
    }

    /** Cases par ligne : une seule ligne jusqu'à sept pages, deux lignes de cinq jusqu'à dix, des lignes de sept au-delà. */
    private static int columns(int shown) {
        if (shown <= 7) return shown;
        return shown <= 10 ? 5 : 7;
    }

    // =========================================================================
    //  AFFICHAGE
    // =========================================================================

    private void renderList(Player player, Menu menu, PlayerInventoryData data) {
        // Page portée lue sur l'inventaire vivant : son contenu affiché est celui du moment
        manager.captureIfLoaded(player);
        Inventory inv = menu.inventory;
        frame(inv);
        menu.pageSlots.clear();
        int shown = shownPages(data);
        int columns = columns(shown);
        boolean customize = canCustomize(player);
        for (int index = 0; index < shown; index++) {
            int row = index / columns;
            int inRow = Math.min(columns, shown - row * columns);
            int slot = (row + 1) * 9 + 1 + (7 - inRow) / 2 + index % columns;
            int page = index + 1;
            menu.pageSlots.put(slot, page);
            inv.setItem(slot, pageItem(data, page, menu.customizing, customize));
        }
        menu.customizeSlot = customize ? inv.getSize() - 5 : -1;
        if (customize) {
            String key = menu.customizing ? "pages_menu_customize_on" : "pages_menu_customize_off";
            inv.setItem(menu.customizeSlot, item(config.getMenuMaterial("customize", Material.COMPARATOR),
                config.getRawMessage(key + "_name"), MessageUtil.itemLines(config.getRawMessageList(key + "_lore")), menu.customizing));
        }
    }

    private ItemStack pageItem(PlayerInventoryData data, int page, boolean customizing, boolean customize) {
        String number = String.valueOf(page);
        if (page > data.getMaxPages()) {
            return item(config.getMenuMaterial("locked_page", Material.GRAY_DYE),
                config.getRawMessage("pages_menu_locked_name").replace("%page%", number),
                MessageUtil.itemLines(config.getRawMessageList("pages_menu_locked_lore")), false);
        }
        if (data.isLocked(page)) {
            return item(config.getMenuMaterial("unreadable_page", Material.BARRIER),
                config.getRawMessage("pages_menu_unreadable_name").replace("%page%", number),
                MessageUtil.itemLines(config.getRawMessageList("pages_menu_unreadable_lore")), false);
        }
        boolean worn = page == data.getCurrentPage();
        ItemStack[] contents = data.getPage(page);
        int slots = 0;
        int items = 0;
        if (contents != null) {
            for (int i = 0; i < contents.length; i++) {
                if (!counts(contents, i)) continue;
                slots++;
                items += contents[i].getAmount();
            }
        }
        List<Component> lore = new ArrayList<>();
        for (String line : config.getRawMessageList("pages_menu_page_lore")) {
            String marker = line.trim();
            if (marker.equals("%contents%")) {
                lore.addAll(contentLines(contents));
            } else if (marker.equals("%reserved%")) {
                if (data.isReserved(page)) lore.add(MessageUtil.itemText(config.getRawMessage("pages_menu_page_reserved")));
            } else {
                lore.add(MessageUtil.itemText(line.replace("%slots%", String.valueOf(slots))
                    .replace("%capacity%", String.valueOf(CAPACITY)).replace("%items%", String.valueOf(items))));
            }
        }
        if (customizing) {
            lore.add(MessageUtil.itemText(config.getRawMessage("pages_menu_action_customize_mode")));
        } else {
            if (!worn) lore.add(MessageUtil.itemText(config.getRawMessage("pages_menu_action_wear")));
            if (customize) lore.add(MessageUtil.itemText(config.getRawMessage("pages_menu_action_customize")));
        }
        ItemStack icon = data.getIcon(page);
        ItemStack item = icon != null ? icon.clone() : new ItemStack(config.getSwitcherItemMaterial());
        String name = config.getRawMessage(worn ? "pages_menu_page_name_worn" : "pages_menu_page_name").replace("%page%", number);
        decorate(item, name, lore, worn);
        item.setAmount(Math.max(1, Math.min(page, item.getMaxStackSize())));
        return item;
    }

    /** Vrai si l'emplacement {@code slot} porte un objet du joueur (ni vide, ni le sélecteur). */
    private boolean counts(ItemStack[] contents, int slot) {
        ItemStack item = contents[slot];
        return slot != manager.getSwitcherSlot() && item != null && !item.isEmpty() && !manager.isSwitcherItem(item);
    }

    /** Objets les plus nombreux de la page, un par ligne, nommés dans la langue du client. */
    private List<Component> contentLines(ItemStack[] contents) {
        int limit = config.getMenuPreviewItems();
        List<Component> lines = new ArrayList<>();
        if (limit <= 0) return lines;
        Map<String, Integer> amounts = new LinkedHashMap<>();
        Map<String, ItemStack> samples = new HashMap<>();
        if (contents != null) {
            for (int i = 0; i < contents.length; i++) {
                if (!counts(contents, i)) continue;
                ItemStack item = contents[i];
                Component custom = item.getData(DataComponentTypes.CUSTOM_NAME);
                String key = item.getType().getKey() + "|" + (custom == null ? "" : PlainTextComponentSerializer.plainText().serialize(custom));
                amounts.merge(key, item.getAmount(), Integer::sum);
                samples.putIfAbsent(key, item);
            }
        }
        if (amounts.isEmpty()) {
            lines.add(MessageUtil.itemText(config.getRawMessage("pages_menu_content_empty")));
            return lines;
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(amounts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        String template = config.getRawMessage("pages_menu_content_line");
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            Map.Entry<String, Integer> entry = sorted.get(i);
            Component name = samples.get(entry.getKey()).effectiveName();
            lines.add(MessageUtil.itemText(template.replace("%amount%", String.valueOf(entry.getValue())))
                .replaceText(builder -> builder.matchLiteral("%item%").replacement(name)));
        }
        if (sorted.size() > limit) {
            lines.add(MessageUtil.itemText(config.getRawMessage("pages_menu_content_more")
                .replace("%count%", String.valueOf(sorted.size() - limit))));
        }
        return lines;
    }

    private void renderSettings(Menu menu, PlayerInventoryData data) {
        Inventory inv = menu.inventory;
        frame(inv);
        int page = menu.settingsPage;
        String number = String.valueOf(page);
        ItemStack icon = data.getIcon(page);
        ItemStack shown = icon != null ? icon.clone() : new ItemStack(config.getSwitcherItemMaterial());
        decorate(shown, config.getRawMessage("pages_menu_icon_name").replace("%page%", number),
            MessageUtil.itemLines(config.getRawMessageList("pages_menu_icon_lore")), false);
        inv.setItem(SLOT_ICON, shown);

        boolean reserved = data.isReserved(page);
        String key = reserved ? "pages_menu_reserved_on" : "pages_menu_reserved_off";
        inv.setItem(SLOT_RESERVED, item(config.getMenuMaterial(reserved ? "reserved_on" : "reserved_off", reserved ? Material.LIME_DYE : Material.GRAY_DYE),
            config.getRawMessage(key + "_name"), MessageUtil.itemLines(config.getRawMessageList("pages_menu_reserved_lore")), false));

        if (icon != null) {
            inv.setItem(SLOT_RESET, item(config.getMenuMaterial("reset_icon", Material.BARRIER),
                config.getRawMessage("pages_menu_reset_name"), MessageUtil.itemLines(config.getRawMessageList("pages_menu_reset_lore")), false));
        }
        inv.setItem(SLOT_BACK, item(config.getMenuMaterial("back", Material.PAPER),
            config.getRawMessage("pages_menu_back_name"), List.of(), false));
    }

    /** Cadre du menu et fond de ses cases libres, sans infobulle. */
    private void frame(Inventory inv) {
        ItemStack border = blank(config.getMenuMaterial("frame", Material.BLACK_STAINED_GLASS_PANE));
        ItemStack filler = blank(config.getMenuMaterial("filler", Material.GRAY_STAINED_GLASS_PANE));
        int size = inv.getSize();
        for (int slot = 0; slot < size; slot++) {
            int column = slot % 9;
            boolean edge = slot < 9 || slot >= size - 9 || column == 0 || column == 8;
            inv.setItem(slot, edge ? border : filler);
        }
    }

    private static ItemStack blank(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack item(Material material, String name, List<Component> lore, boolean glint) {
        ItemStack item = new ItemStack(material);
        decorate(item, name, lore, glint);
        return item;
    }

    private static void decorate(ItemStack item, String name, List<Component> lore, boolean glint) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.displayName(MessageUtil.itemText(name));
        meta.lore(lore.isEmpty() ? null : lore);
        if (glint) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
    }

    // =========================================================================
    //  CLICS
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(menu.owner)) return;
        // Clics simples seulement : un double clic ou une touche ne déclenche jamais deux actions
        ClickType click = event.getClick();
        if (click == ClickType.DOUBLE_CLICK || (!click.isLeftClick() && !click.isRightClick())) return;
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) return;
        PlayerInventoryData data = manager.getPlayerData(player.getUniqueId());
        if (data == null) {
            closeLater(player);
            return;
        }
        boolean top = clicked == event.getView().getTopInventory();
        if (menu.settingsPage > 0) {
            settingsClick(player, menu, data, event, top);
        } else if (top) {
            listClick(player, menu, data, event.getRawSlot(), click.isRightClick());
        }
    }

    private void listClick(Player player, Menu menu, PlayerInventoryData data, int slot, boolean rightClick) {
        if (slot == menu.customizeSlot) {
            menu.customizing = !menu.customizing;
            renderList(player, menu, data);
            play(player, "navigate", "entity.bat.takeoff");
            return;
        }
        Integer page = menu.pageSlots.get(slot);
        if (page == null) return;
        if (!data.isAccessible(page)) {
            play(player, "deny", "entity.zombie.break_wooden_door");
            return;
        }
        if (canCustomize(player) && (menu.customizing || rightClick)) {
            boolean customizing = menu.customizing;
            later(player, online -> openSettings(online, page, customizing));
            play(player, "navigate", "entity.bat.takeoff");
            return;
        }
        // Page déjà portée : rien à changer, le menu se ferme sur elle
        if (page == data.getCurrentPage() || manager.switchToPage(player, page)) closeLater(player);
    }

    private void settingsClick(Player player, Menu menu, PlayerInventoryData data, InventoryClickEvent event, boolean top) {
        int page = menu.settingsPage;
        if (!canCustomize(player) || page > data.getMaxPages()) {
            closeLater(player);
            return;
        }
        if (!top) {
            // Objet de l'inventaire du joueur : la page en prend l'apparence, l'objet reste à sa place
            ItemStack source = event.getCurrentItem();
            if (event.getSlot() == manager.getSwitcherSlot() || source == null || source.isEmpty()) return;
            if (!manager.canBeIcon(source)) {
                play(player, "deny", "entity.zombie.break_wooden_door");
                return;
            }
            manager.setPageIcon(player, page, source);
            renderSettings(menu, data);
            play(player, "success", "entity.horse.armor");
            return;
        }
        switch (event.getRawSlot()) {
            case SLOT_RESERVED -> {
                manager.togglePageReserved(player, page);
                renderSettings(menu, data);
                play(player, "success", "entity.horse.armor");
            }
            case SLOT_RESET -> {
                if (data.getIcon(page) == null) return;
                manager.setPageIcon(player, page, null);
                renderSettings(menu, data);
                play(player, "success", "entity.horse.armor");
            }
            case SLOT_BACK -> {
                boolean customizing = menu.customizing;
                later(player, online -> openList(online, customizing));
                play(player, "navigate", "entity.bat.takeoff");
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) event.setCancelled(true);
    }

    /** Page changée par le sélecteur pendant que la liste est ouverte : la page portée affichée suit. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPageSwitch(PlayerPageSwitchEvent event) {
        Player player = event.getPlayer();
        if (!(player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu)) return;
        later(player, online -> {
            if (!(online.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu menu) || menu.settingsPage > 0) return;
            PlayerInventoryData data = manager.getPlayerData(online.getUniqueId());
            if (data != null) renderList(online, menu, data);
        });
    }

    // =========================================================================
    //  OUTILS
    // =========================================================================

    /** Au tick suivant : un inventaire ne s'ouvre ni ne se ferme pendant le traitement d'un clic. */
    private void later(Player player, java.util.function.Consumer<Player> action) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) action.accept(online);
        });
    }

    private void closeLater(Player player) {
        later(player, online -> {
            if (online.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) online.closeInventory();
        });
    }

    private void play(Player player, String name, String fallback) {
        Sound sound = config.getMenuSound(name, fallback);
        if (sound != null) player.playSound(player.getLocation(), sound, config.getSoundVolume(), 1.0f);
    }
}
