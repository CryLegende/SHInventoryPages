package org.Cry_Legende.sHInventoryPages.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * Textes de la configuration et de messages.yml : codes & et &#RRGGBB. Les messages passent par
 * {@link #colorize} (texte à codes §, prêt à recevoir des remplacements) puis sont envoyés en composants.
 */
public final class MessageUtil {

    /** Codes & et couleurs &#RRGGBB, tels qu'écrits dans les fichiers. */
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder()
        .character('&').hexCharacter('#').hexColors().build();
    /** Codes §, couleurs hexadécimales comprises (format §x§r§r§g§g§b§b). */
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.SECTION_CHAR).hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private static String pluginPrefix = "";

    private MessageUtil() {
    }

    public static void setPluginPrefix(String prefixFormat) {
        pluginPrefix = colorize(prefixFormat);
    }

    /** Texte à codes & et &#RRGGBB vers texte à codes §. */
    public static String colorize(String message) {
        if (message == null || message.isEmpty()) return "";
        return SECTION.serialize(AMPERSAND.deserialize(message));
    }

    /** Texte à codes § (message déjà colorisé) vers composant. */
    public static Component component(String colorized) {
        return SECTION.deserialize(colorized == null ? "" : colorized);
    }

    /** Nom ou ligne de description d'objet écrit avec des codes & : jamais en italique. */
    public static Component itemText(String raw) {
        return AMPERSAND.deserialize(raw == null ? "" : raw).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> itemLines(List<String> raw) {
        List<Component> lines = new ArrayList<>(raw.size());
        for (String line : raw) lines.add(itemText(line));
        return lines;
    }

    /** Comme {@link #sendMessage}, {@code placeholder} remplacé par un composant (nom d'objet traduit par le client). */
    public static void sendMessage(CommandSender target, String message, String placeholder, Component value) {
        if (target != null && message != null && !message.isEmpty()) {
            target.sendMessage(component(pluginPrefix + message).replaceText(builder -> builder.matchLiteral(placeholder).replacement(value)));
        }
    }

    /** Message déjà colorisé, précédé du préfixe du plugin ; un message vide n'est pas envoyé. */
    public static void sendMessage(CommandSender target, String message) {
        if (target != null && message != null && !message.isEmpty()) {
            target.sendMessage(component(pluginPrefix + message));
        }
    }
}
