package org.Cry_Legende.sHInventoryPages.database;

import org.Cry_Legende.sHInventoryPages.inventory.PlayerInventoryData;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface DatabaseManager {

    boolean connect();

    void disconnect();

    boolean createTables();

    CompletableFuture<Map<Integer, ItemStack[]>> loadPlayerInventories(UUID uuid);

    CompletableFuture<Void> savePlayerInventoryPage(UUID uuid, int pageNumber, ItemStack[] contents);

    CompletableFuture<Void> saveAllPlayerPages(UUID uuid, Map<Integer, ItemStack[]> pageData);

    CompletableFuture<Void> deletePlayerInventoryPage(UUID uuid, int pageNumber);

    // Charge toutes les pages ET l'équipement pour un joueur
    CompletableFuture<PlayerInventoryData> loadPlayerInventoriesAndEquipment(UUID uuid);

    // Sauvegarde toutes les pages ET l'équipement d'un joueur
    // (Peut-être renommer en savePlayerInventoryData)
    CompletableFuture<Void> saveFullPlayerInventoryData(PlayerInventoryData data);

    void saveFullPlayerInventoryDataBlocking(PlayerInventoryData data) throws Exception;


    CompletableFuture<Void> deleteAllPlayerData(UUID uuid); // Doit supprimer les pages et l'équipement
}
