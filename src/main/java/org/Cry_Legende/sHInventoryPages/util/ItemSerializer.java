package org.Cry_Legende.sHInventoryPages.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ItemSerializer {

    /**
     * Serializes an array of ItemStacks to a Base64 String.
     * Size is fixed to 36 for main inventory slots.
     *
     * @param items The array of ItemStacks (should ideally be size 36).
     * @return Base64 encoded String.
     * @throws IllegalStateException if serialization fails.
     */
    public static String itemStackArrayToBase64(ItemStack[] items) throws IllegalStateException {
        if (items == null) {
            items = new ItemStack[36]; // Treat null as empty
        }
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to serialize ItemStack array.", e);
        }
    }

    /**
     * Deserializes a Base64 String back into an ItemStack array.
     *
     * @param base64Data The Base64 encoded String.
     * @return The deserialized ItemStack array.
     * @throws IOException if Base64 decoding or deserialization fails.
     */
    public static ItemStack[] itemStackArrayFromBase64(String base64Data) throws IOException {
        if (base64Data == null || base64Data.isEmpty()) {
            return new ItemStack[36];
        }
        try {
            byte[] decodedData = Base64Coder.decodeLines(base64Data);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(decodedData);
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            int arraySize = dataInput.readInt();
            if (arraySize < 0 || arraySize > 1000) {
                throw new IOException("Invalid array size read from data: " + arraySize);
            }
            ItemStack[] items = new ItemStack[arraySize];
            for (int i = 0; i < arraySize; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }
            dataInput.close();
            return items;
        } catch (ClassNotFoundException e) {
            throw new IOException("Unable to find class during ItemStack deserialization.", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid Base64 data.", e);
        }
    }

    /**
     * Serializes a single ItemStack to a Base64 String.
     *
     * @param item The ItemStack to serialize.
     * @return Base64 encoded String.
     * @throws IllegalStateException if serialization fails.
     */
    public static String itemStackToBase64(ItemStack item) throws IllegalStateException {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeObject(item);
            dataOutput.close();
            return Base64Coder.encodeLines(outputStream.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to serialize ItemStack.", e);
        }
    }

    /**
     * Deserializes a Base64 String back into an ItemStack.
     *
     * @param base64Data The Base64 encoded String.
     * @return The deserialized ItemStack.
     * @throws IOException if Base64 decoding or deserialization fails.
     */
    public static ItemStack itemStackFromBase64(String base64Data) throws IOException {
        if (base64Data == null || base64Data.isEmpty()) {
            return null;
        }
        try {
            byte[] decodedData = Base64Coder.decodeLines(base64Data);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(decodedData);
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            ItemStack item = (ItemStack) dataInput.readObject();
            dataInput.close();
            return item;
        } catch (ClassNotFoundException e) {
            throw new IOException("Unable to find class during ItemStack deserialization.", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid Base64 data.", e);
        }
    }
}