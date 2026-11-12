package org.Cry_Legende.sHInventoryPages.menu;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.config.ConfigManager;
import org.Cry_Legende.sHInventoryPages.inventory.InventoryManager;
import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.Cry_Legende.sHInventoryPages.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Nom d'une page, saisi dans un dialogue natif (clients 1.21.6 et plus). Le nom n'est vu que par son joueur, sur le
 * sélecteur et dans le menu des pages ; il est affiché tel quel, jamais interprété (pas de codes couleur). Un nom
 * vide retire le nom. La saisie est recontrôlée par le serveur, sur le thread principal.
 */
public final class PageNameDialog {

    private static final String INPUT = "name";
    private static final int WIDTH = 250;
    private static final int BUTTON_WIDTH = 120;

    private final SHInventoryPages plugin;
    private final ConfigManager config;
    private final InventoryManager manager;

    public PageNameDialog(SHInventoryPages plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.manager = plugin.getInventoryManager();
    }

    /** Ce client peut-il recevoir un dialogue ? Sans ViaVersion, tous les clients sont à la version du serveur. */
    public boolean supports(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ViaVersion")) return true;
        try {
            return ViaSupport.protocol(player) >= config.getMenuDialogMinProtocol();
        } catch (LinkageError e) {
            return false;
        }
    }

    /**
     * Ferme le menu et ouvre la saisie du nom de la page. Faux si le client ne connaît pas les dialogues : rien n'est
     * alors ouvert ni fermé.
     */
    public boolean open(Player player, int page) {
        PlayerInventoryData data = manager.getPlayerData(player.getUniqueId());
        if (data == null || !supports(player)) return false;
        try {
            String current = data.getName(page);
            String number = String.valueOf(page);
            int max = config.getMenuNameMaxLength();
            UUID uuid = player.getUniqueId();
            ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();

            List<DialogBody> body = new ArrayList<>();
            for (String line : config.getRawMessageList("pages_name_dialog_body")) {
                body.add(DialogBody.plainMessage(MessageUtil.itemText(line.replace("%page%", number).replace("%max%", String.valueOf(max))), WIDTH));
            }
            DialogInput input = DialogInput.text(INPUT, MessageUtil.itemText(config.getRawMessage("pages_name_dialog_field")))
                .width(WIDTH)
                .maxLength(max)
                .initial(current == null ? "" : current)
                .build();
            ActionButton confirm = ActionButton.builder(MessageUtil.itemText(config.getRawMessage("pages_name_dialog_confirm")))
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick((response, audience) -> onMainThread(uuid, online -> submit(online, page, response.getText(INPUT))), once))
                .build();
            // Échap lance aussi ce bouton : retour aux réglages de la page, rien n'est changé
            ActionButton cancel = ActionButton.builder(MessageUtil.itemText(config.getRawMessage("pages_name_dialog_cancel")))
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick((response, audience) -> onMainThread(uuid, online -> plugin.getPagesMenu().openSettings(online, page)), once))
                .build();
            DialogBase base = DialogBase.builder(MessageUtil.itemText(config.getRawMessage("pages_name_dialog_title").replace("%page%", number)))
                .canCloseWithEscape(true)
                // Fermé au clic : le client ne retire pas son écran d'attente quand le serveur ferme le dialogue
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .inputs(List.of(input))
                .build();
            Dialog dialog = Dialog.create(factory -> factory.empty().base(base).type(DialogType.confirmation(confirm, cancel)));
            player.closeInventory();
            player.showDialog(dialog);
            return true;
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "Dialogue du nom de page impossible pour " + player.getName(), e);
            return false;
        }
    }

    private void submit(Player player, int page, String typed) {
        if (!plugin.getPagesMenu().canCustomize(player)) return;
        String name = typed == null ? "" : typed.strip();
        if (name.isEmpty()) {
            manager.setPageName(player, page, null);
        } else if (!isValid(name)) {
            MessageUtil.sendMessage(player, config.getMessage("pages_name_invalid", "%max%", String.valueOf(config.getMenuNameMaxLength())));
        } else {
            manager.setPageName(player, page, name);
        }
        plugin.getPagesMenu().openSettings(player, page);
    }

    /** Une ligne courte de texte simple : ni caractère de contrôle, ni code de mise en forme. */
    private boolean isValid(String name) {
        if (name.length() > config.getMenuNameMaxLength()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '§' || Character.isISOControl(c)) return false;
        }
        return true;
    }

    private void onMainThread(UUID uuid, java.util.function.Consumer<Player> action) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) action.accept(online);
        });
    }
}
