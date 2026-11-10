package org.Cry_Legende.sHInventoryPages.listeners;

import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import org.Cry_Legende.sHInventoryPages.SHInventoryPages;
import org.Cry_Legende.sHInventoryPages.inventory.InventoryManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Débordement : un objet donné à un joueur dont l'inventaire porté est plein est rangé dans ses autres pages au
 * lieu de rester au sol.
 *
 * <p>Est donné à un joueur : un objet qui lui est destiné ({@code /give}, {@code Player#give} : propriétaire
 * fixé), un objet tombé du bloc qu'il vient de casser, ou un objet sans lanceur qu'un plugin fait apparaître à ses
 * pieds (reste d'un {@code addItem}). Un objet né d'une mécanique du monde à ses pieds (ferme, piston, œuf...) ne
 * l'est pas : il garde les règles de ramassage du serveur et des protections. Jamais : un objet jeté, le butin d'une
 * mort, la sortie d'un distributeur, une explosion, le bloc cassé par un autre joueur, un objet factice ou décoratif,
 * ni un objet qu'un plugin a marqué (ramassage par les créatures coupé, nom affiché : empileurs comme WildStacker,
 * dont la quantité réelle n'est pas celle de l'entité). Priorité HIGH : tout est décidé avant WildStacker
 * (HIGHEST), qui n'empile alors que ce qui reste au sol.</p>
 */
public final class OverflowListener implements Listener {

    /**
     * Distance (au carré) entre le centre d'un bloc cassé et un objet qui en tombe : butin vanilla à ±0,25 du centre,
     * dropItemNaturally décalé de +0,25 à +0,75 depuis le point donné par le plugin (coin ou centre du bloc).
     */
    private static final double BREAK_DROP_RADIUS_SQUARED = 2.25;
    private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final InventoryManager manager;
    private final ClassLoader ownLoader = OverflowListener.class.getClassLoader();

    /** Bloc cassé par un joueur pendant le tick courant. */
    private record BreakRecord(Block block, Location center, UUID player) {
    }

    /** Origine d'objets du monde (mort, distributeur, explosion), valable jusqu'au prochain événement du même genre. */
    private enum LootKind {DEATH, DISPENSE, EXPLOSION}

    private record LootSource(LootKind kind, Location location, double radiusSquared) {
    }

    /**
     * Relevés du tick en cours, un jeu par fil. Avec les ticks de mondes en parallèle et le tick des chunks par régions
     * (DivineMC), chaque monde, voire chaque région, traite ses événements sur son propre fil : une mort et les objets
     * qui en tombent passent par le même fil, l'un après l'autre. Les relevés restent comparés par monde.
     */
    private static final class WorldRecords {
        final List<BreakRecord> breaks = new ArrayList<>();
        final List<LootSource> lootSources = new ArrayList<>();
        int tick = Integer.MIN_VALUE;
    }

    private final ThreadLocal<WorldRecords> records = ThreadLocal.withInitial(WorldRecords::new);

    public OverflowListener(SHInventoryPages plugin) {
        this.manager = plugin.getInventoryManager();
    }

    /** Les relevés ne valent que pour le tick en cours : les objets apparaissent dans le même tick que leur cause. */
    private WorldRecords resetIfNewTick(World world) {
        WorldRecords r = records.get();
        int now = Bukkit.getCurrentTick();
        if (r.tick != now) {
            r.breaks.clear();
            r.lootSources.clear();
            r.tick = now;
        }
        return r;
    }

    /** Relevés du tick en cours sur ce fil, ou null s'il n'y en a pas. */
    private WorldRecords validRecords(World world) {
        WorldRecords r = records.get();
        return r.tick == Bukkit.getCurrentTick() ? r : null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreakStart(BlockBreakEvent event) {
        if (!manager.isOverflowEnabled()) return;
        Block block = event.getBlock();
        resetIfNewTick(block.getWorld()).breaks.add(new BreakRecord(block, block.getLocation().add(0.5, 0.5, 0.5), event.getPlayer().getUniqueId()));
    }

    /** Une casse annulée plus tard (protection, autre plugin) ne désigne plus de destinataire. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreakEnd(BlockBreakEvent event) {
        WorldRecords r;
        if (event.isCancelled() && (r = validRecords(event.getBlock().getWorld())) != null) {
            UUID player = event.getPlayer().getUniqueId();
            r.breaks.removeIf(record -> record.player().equals(player) && record.block().equals(event.getBlock()));
        }
    }

    /*
     * Le butin d'une mort, d'un distributeur ou d'une explosion apparaît juste après son événement. Le relevé est donc
     * posé en MONITOR et retiré au début de l'événement suivant du même genre (LOWEST) : un objet qu'un plugin remet
     * pendant l'événement (butin rendu au tueur à ses pieds) n'est jamais pris pour du butin du monde, même quand deux
     * créatures meurent dans le même tick.
     */

    /** Créatures et joueurs (PlayerDeathEvent passe aussi ici). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDeathStart(EntityDeathEvent event) {
        clearLoot(LootKind.DEATH, event.getEntity().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        addLootSource(LootKind.DEATH, event.getEntity().getLocation(), manager.getLootRadius());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDispenseStart(BlockDispenseEvent event) {
        clearLoot(LootKind.DISPENSE, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        addLootSource(LootKind.DISPENSE, event.getBlock().getLocation().add(0.5, 0.5, 0.5), manager.getLootRadius());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityExplodeStart(EntityExplodeEvent event) {
        clearLoot(LootKind.EXPLOSION, event.getLocation().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        addLootSource(LootKind.EXPLOSION, event.getLocation(), manager.getExplosionRadius());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockExplodeStart(BlockExplodeEvent event) {
        clearLoot(LootKind.EXPLOSION, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        addLootSource(LootKind.EXPLOSION, event.getBlock().getLocation().add(0.5, 0.5, 0.5), manager.getExplosionRadius());
    }

    private void clearLoot(LootKind kind, World world) {
        WorldRecords r = validRecords(world);
        if (r != null) r.lootSources.removeIf(source -> source.kind() == kind);
    }

    private void addLootSource(LootKind kind, Location location, double radius) {
        if (!manager.isOverflowEnabled()) return;
        resetIfNewTick(location.getWorld()).lootSources.add(new LootSource(kind, location, radius * radius));
    }

    /**
     * Ramassage réel : la place réservée à cet objet est libérée. Pendant l'événement, la pile de l'entité vaut déjà
     * la quantité ramassée (Paper la ramène à ce que l'inventaire peut prendre).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            var stack = event.getItem().getItemStack();
            manager.consumeReservation(player, stack, stack.getAmount());
        }
    }

    /**
     * Objet au sol que le joueur touche alors que son inventaire porté n'en peut rien prendre (butin d'une créature,
     * objet jeté, pile d'un empileur...) : rangé dans ses autres pages au lieu de rester au sol. MONITOR : les règles de
     * ramassage de l'île ou d'un autre plugin ont déjà annulé l'événement si le joueur n'a pas le droit de le ramasser.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent event) {
        // Cas courant (place libre) écarté avant toute lecture de l'objet : le ramassage vanilla s'en charge
        if (event.getRemaining() <= 0 || !manager.isPickupIntoPages()) return;
        manager.pickUpIntoPages(event.getPlayer(), event.getItem(), event.getRemaining());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item entity = event.getEntity();
        var stack = entity.getItemStack();
        if (stack.isEmpty()) return;
        // Un objet d'interface n'existe jamais au sol
        if (manager.isSwitcherItem(stack)) {
            event.setCancelled(true);
            return;
        }
        if (!manager.isOverflowEnabled() || manager.isForeign(entity)) return;
        Location location = entity.getLocation();
        Target target = findTarget(entity, location);
        if (target != null) {
            manager.captureSpawnedItem(target.player(), event, target.needsGiftCheck() ? this::isPluginGift : null);
        }
    }

    /** Destinataire d'un objet ; sans propriétaire ni casse, il reste à prouver que c'est un don de plugin. */
    private record Target(Player player, boolean needsGiftCheck) {
    }

    private boolean isWorldLoot(Location location) {
        WorldRecords r = validRecords(location.getWorld());
        if (r == null) return false;
        for (LootSource source : r.lootSources) {
            if (source.location().getWorld() == location.getWorld()
                && source.location().distanceSquared(location) <= source.radiusSquared()) {
                return true;
            }
        }
        return false;
    }

    private Target findTarget(Item entity, Location location) {
        UUID owner = entity.getOwner();
        World world = location.getWorld();
        WorldRecords r = validRecords(world);
        if (r != null && !r.breaks.isEmpty()) {
            BreakRecord closest = null;
            double closestDistance = BREAK_DROP_RADIUS_SQUARED;
            for (BreakRecord record : r.breaks) {
                if (record.center().getWorld() != world) continue;
                double distance = record.center().distanceSquared(location);
                if (distance <= closestDistance) {
                    closest = record;
                    closestDistance = distance;
                }
            }
            if (closest != null) {
                // Tombé d'un bloc cassé ce tick : à celui qui l'a cassé, ou à personne
                if (!manager.isCaptureBrokenBlockDrops()) return null;
                if (owner != null && !owner.equals(closest.player())) return null;
                Player breaker = Bukkit.getPlayer(closest.player());
                return manager.isEligibleRecipient(breaker, world) ? new Target(breaker, false) : null;
            }
        }
        if (owner != null) return ownedTarget(owner, location, world);
        // Sans propriétaire ni casse : jamais le butin du monde qui vient de tomber à côté
        return isWorldLoot(location) ? null : feetTarget(location, world);
    }

    /** Objet destiné à un joueur (/give, Player#give) : à lui seul, à ses pieds ou à hauteur de ses yeux. */
    private Target ownedTarget(UUID owner, Location location, World world) {
        Player player = Bukkit.getPlayer(owner);
        if (!manager.isEligibleRecipient(player, world)) return null;
        double radius = manager.getOverflowCaptureRadius();
        double distance = Math.min(player.getLocation().distanceSquared(location),
            player.getEyeLocation().distanceSquared(location));
        return radius > 0 && distance <= radius * radius ? new Target(player, false) : null;
    }

    /**
     * Objet sans propriétaire : seulement s'il apparaît dans la colonne des pieds d'un joueur, là où un plugin pose
     * le reste d'un addItem (dropItem ou dropItemNaturally sur la position du joueur), et seulement si c'est bien
     * le code d'un autre plugin qui le fait apparaître (vérifié plus tard, si l'objet doit vraiment être rangé).
     * Une ferme, un piston, un œuf ou la sortie d'un générateur listé dans ignored_plugins aux pieds d'un visiteur
     * restent soumis aux règles de ramassage de l'île ou de la région.
     */
    private Target feetTarget(Location location, World world) {
        double feet = manager.getFeetRadius();
        if (feet <= 0) return null;
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : world.getNearbyPlayers(location, feet + 2.0)) {
            if (!manager.isEligibleRecipient(player, world)) continue;
            Location feetAt = player.getLocation();
            double dy = location.getY() - feetAt.getY();
            if (dy < -0.75 || dy > 2.0) continue;
            double dx = location.getX() - feetAt.getX();
            double dz = location.getZ() - feetAt.getZ();
            double horizontal = dx * dx + dz * dz;
            if (horizontal <= feet * feet && horizontal < bestDistance) {
                best = player;
                bestDistance = horizontal;
            }
        }
        return best != null ? new Target(best, true) : null;
    }

    /**
     * Vrai si le code d'un autre plugin a fait apparaître l'objet (pile d'appels de l'événement en cours) et que ce
     * plugin n'est pas déclaré comme posant des objets du monde ou des décors (overflow.ignored_plugins). Le plugin
     * retenu est le plus proche de l'apparition dans la pile.
     */
    private boolean isPluginGift() {
        return STACK.walk(frames -> {
            String spawner = null;
            for (StackWalker.StackFrame frame : (Iterable<StackWalker.StackFrame>) frames::iterator) {
                ClassLoader loader = frame.getDeclaringClass().getClassLoader();
                if (loader == ownLoader || !(loader instanceof ConfiguredPluginClassLoader plugin)) continue;
                // Classe déclarée comme mécanique du monde (sortie d'un générateur...) : jamais un don
                if (manager.isIgnoredClass(frame.getClassName())) return false;
                if (spawner == null) spawner = plugin.getConfiguration().getName();
            }
            return spawner != null && !manager.isIgnoredPlugin(spawner);
        });
    }
}
