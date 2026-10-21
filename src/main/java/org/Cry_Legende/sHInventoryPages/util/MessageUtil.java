package org.Cry_Legende.sHInventoryPages.util;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageUtil {

    private static String pluginPrefix = ChatColor.translateAlternateColorCodes('&', "&8[&bSHInvPages&8] &r");
    // Pattern for hex color codes like &#RRGGBB
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");


    public static void setPluginPrefix(String prefixFormat) {
        pluginPrefix = colorize(prefixFormat);
    }

    public static String colorize(String message) {
        if (message == null) return "";

        // Translate traditional '&' color codes
        message = ChatColor.translateAlternateColorCodes('&', message);

        // Translate hex color codes &#RRGGBB
        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuffer buffer = new StringBuffer(message.length() + 4 * 8); // Pre-allocate buffer
        while (matcher.find()) {
            String group = matcher.group(1);
            matcher.appendReplacement(buffer, ChatColor.of("#" + group).toString());
        }
        return matcher.appendTail(buffer).toString();
    }

    public static String stripColor(String message) {
        return ChatColor.stripColor(colorize(message)); // Ensure it's colorized first before stripping
    }

    public static void sendMessage(CommandSender target, String message) {
        if (target != null && message != null && !message.isEmpty()) {
            target.sendMessage(pluginPrefix + colorize(message));
        }
    }

    public static void sendMessageNoPrefix(CommandSender target, String message) {
        if (target != null && message != null && !message.isEmpty()) {
            target.sendMessage(colorize(message));
        }
    }

    // You can add more methods here, e.g., for sending action bar messages, titles, etc.
}