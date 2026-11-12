package org.Cry_Legende.sHInventoryPages.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Encodage des pages en base.
 *
 * <p>Format écrit : {@code SHIP2:} suivi du Base64 d'une suite d'emplacements. Chaque objet est encodé par
 * {@link ItemStack#serializeAsBytes()} : NBT complet avec sa DataVersion, mis à niveau par Paper à la lecture.
 * Composants, PDC et {@code max_stack_size} personnalisé sont conservés tels quels.</p>
 *
 * <p>Ancien format : Base64 (avec retours à la ligne) d'un {@link BukkitObjectOutputStream}. Toujours lu, et
 * réécrit au format actuel à la sauvegarde suivante. Il sert aussi de secours, emplacement par emplacement, pour
 * un objet que le codec de Paper refuserait : la page reste sauvegardable au lieu de faire échouer tout le joueur.</p>
 */
@SuppressWarnings("deprecation") // BukkitObjectInput/OutputStream : anciennes pages et secours uniquement
public final class ItemCodec {

    public static final String PREFIX = "SHIP2:";
    private static final byte SLOT_EMPTY = 0;
    private static final byte SLOT_NATIVE = 1;
    private static final byte SLOT_LEGACY = 2;
    private static final int MAX_SLOTS = 1000;

    private ItemCodec() {
    }

    /** Encode une page. Les emplacements null ou vides sont gardés à leur place. */
    public static String encodePage(ItemStack[] items, Logger log) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(items.length);
            for (int slot = 0; slot < items.length; slot++) {
                ItemStack item = items[slot];
                if (item == null || item.isEmpty()) {
                    out.writeByte(SLOT_EMPTY);
                    continue;
                }
                byte[] data;
                byte kind;
                try {
                    data = item.serializeAsBytes();
                    kind = SLOT_NATIVE;
                } catch (RuntimeException nativeFailure) {
                    data = encodeLegacyItem(item, nativeFailure);
                    kind = SLOT_LEGACY;
                    log.log(Level.WARNING, "Objet refusé par le codec de Paper, enregistré à l'ancien format : "
                        + item.getAmount() + "x " + item.getType() + " (emplacement " + slot + ")", nativeFailure);
                }
                out.writeByte(kind);
                out.writeInt(data.length);
                out.write(data);
            }
        }
        return PREFIX + Base64.getEncoder().encodeToString(buffer.toByteArray());
    }

    /**
     * Décode une page (format actuel ou ancien). Une donnée vide donne une page vide. Toute donnée illisible lève
     * une exception : le chargement échoue plutôt que de présenter (puis sauvegarder) une page amputée.
     */
    public static ItemStack[] decodePage(String stored, int pageSize) throws IOException {
        if (stored == null || stored.isEmpty()) {
            return new ItemStack[pageSize];
        }
        if (!stored.startsWith(PREFIX)) {
            return decodeLegacyPage(stored);
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IOException("Base64 invalide", e);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            int size = in.readInt();
            if (size < 0 || size > MAX_SLOTS) {
                throw new IOException("Nombre d'emplacements invalide : " + size);
            }
            ItemStack[] items = new ItemStack[size];
            for (int slot = 0; slot < size; slot++) {
                byte kind = in.readByte();
                if (kind == SLOT_EMPTY) continue;
                int length = in.readInt();
                if (length <= 0 || length > raw.length) {
                    throw new IOException("Longueur invalide " + length + " (emplacement " + slot + ")");
                }
                byte[] data = in.readNBytes(length);
                if (data.length != length) {
                    throw new EOFException("Page tronquée (emplacement " + slot + ")");
                }
                ItemStack item = switch (kind) {
                    case SLOT_NATIVE -> decodeNative(data, slot);
                    case SLOT_LEGACY -> decodeLegacyItem(data);
                    default -> throw new IOException("Type d'emplacement inconnu " + kind + " (emplacement " + slot + ")");
                };
                items[slot] = item == null || item.isEmpty() ? null : item;
            }
            return items;
        }
    }

    /** Encode un seul objet (icône d'une page) : Base64 de {@link ItemStack#serializeAsBytes()}. */
    public static String encodeItem(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    /** Décode un objet encodé par {@link #encodeItem} ; une donnée illisible lève une exception. */
    public static ItemStack decodeItem(String stored) throws IOException {
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(stored));
        } catch (RuntimeException e) {
            throw new IOException("Objet illisible", e);
        }
    }

    private static ItemStack decodeNative(byte[] data, int slot) throws IOException {
        try {
            return ItemStack.deserializeBytes(data);
        } catch (RuntimeException e) {
            throw new IOException("Objet illisible (emplacement " + slot + ")", e);
        }
    }

    private static byte[] encodeLegacyItem(ItemStack item, RuntimeException nativeFailure) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(buffer)) {
            out.writeObject(item);
        } catch (IOException | RuntimeException e) {
            e.addSuppressed(nativeFailure);
            throw new IOException("Objet impossible à enregistrer : " + item.getAmount() + "x " + item.getType(), e);
        }
        return buffer.toByteArray();
    }

    private static ItemStack decodeLegacyItem(byte[] data) throws IOException {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(data))) {
            return (ItemStack) in.readObject();
        } catch (ClassNotFoundException | ClassCastException e) {
            throw new IOException("Objet à l'ancien format illisible", e);
        }
    }

    /** Ancien format : Base64 « par lignes » (snakeyaml Base64Coder.encodeLines), lu par le décodeur MIME. */
    private static ItemStack[] decodeLegacyPage(String stored) throws IOException {
        byte[] raw;
        try {
            raw = Base64.getMimeDecoder().decode(stored);
        } catch (IllegalArgumentException e) {
            throw new IOException("Base64 invalide (ancien format)", e);
        }
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(raw))) {
            int size = in.readInt();
            if (size < 0 || size > MAX_SLOTS) {
                throw new IOException("Nombre d'emplacements invalide (ancien format) : " + size);
            }
            ItemStack[] items = new ItemStack[size];
            for (int slot = 0; slot < size; slot++) {
                ItemStack item = (ItemStack) in.readObject();
                items[slot] = item == null || item.isEmpty() ? null : item;
            }
            return items;
        } catch (ClassNotFoundException | ClassCastException e) {
            throw new IOException("Page à l'ancien format illisible", e);
        }
    }
}
