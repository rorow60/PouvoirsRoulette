package com.pouvoirs.roulette;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Connexion du joueur, points de kill, utilisation du livre et du dash,
 * et comportement des pouvoirs qui ne passent pas par un simple effet de potion ou un attribut.
 */
public final class PowerListener implements Listener {

    private final PouvoirsPlugin plugin;
    private final PowerManager manager;
    private final PowerMenu menu;
    private final PointsManager points;

    public PowerListener(PouvoirsPlugin plugin, PowerManager manager, PowerMenu menu, PointsManager points) {
        this.plugin = plugin;
        this.manager = manager;
        this.menu = menu;
        this.points = points;
    }

    // ------------------------------------------------------------ connexion

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        manager.load(player);
        if (manager.hasPower(player)) {
            return;
        }

        boolean firstJoinOnly = plugin.getConfig().getBoolean("first-join-only", true);
        if (!player.hasPlayedBefore() || !firstJoinOnly) {
            manager.setPending(player, true);
        }

        if (manager.isPending(player)) {
            // Deux essais : un joueur Bedrock (Geyser) met parfois plus de temps a etre pret.
            for (long delay : new long[] {60L, 160L}) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && !manager.hasPower(player)) {
                        menu.openIntro(player);
                    }
                }, delay);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.unload(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        refreshLater(event.getPlayer());
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.MILK_BUCKET) {
            refreshLater(event.getPlayer());
        }
    }

    @EventHandler
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player) {
            refreshLater(player);
        }
    }

    private void refreshLater(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                manager.refresh(player);
            }
        }, 2L);
    }

    // ------------------------------------------------------------ points de kill

    /** Monstres tues (les joueurs tues sont geres par onPlayerDeath). */
    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player) {
            return;
        }
        Player killer = dead.getKiller();
        if (killer == null) {
            return;
        }
        int amount = points.mobPoints(dead);
        if (amount > 0) {
            points.addPoints(killer, amount);
        }
    }

    /** Joueur tue par un autre joueur : points pour le tueur. Retire aussi l'objet de dash des drops. */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(manager::isDashItem);

        Player dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || killer.equals(dead)) {
            return;
        }
        points.addPoints(killer, points.playerKillPoints());
    }

    // ------------------------------------------------------------ livre et dash

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }

        boolean book = points.isBook(item);
        boolean dash = !book && manager.isDashItem(item);
        if (!book && !dash) {
            return;
        }

        // Sur un coffre, une porte, etc. : on laisse le jeu ouvrir le bloc normalement.
        Block clicked = event.getClickedBlock();
        if (action == Action.RIGHT_CLICK_BLOCK && clicked != null && clicked.getType().isInteractable()) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (book) {
            menu.openBonus(player);
        } else {
            manager.useDash(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDropDash(PlayerDropItemEvent event) {
        if (manager.isDashItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ degats subis

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        List<Power> powers = manager.getActivePowers(player);
        if (powers.isEmpty()) {
            return;
        }
        DamageCause cause = event.getCause();

        for (Power power : powers) {
            switch (power) {
                case SUPER_RESSORTS -> {
                    if (cause == DamageCause.FALL) {
                        event.setCancelled(true);
                        return;
                    }
                }
                case INCREVABLE -> {
                    if (cause == DamageCause.BLOCK_EXPLOSION || cause == DamageCause.ENTITY_EXPLOSION) {
                        event.setCancelled(true);
                        return;
                    }
                }
                case PEAU_DE_PAPIER -> event.setDamage(event.getDamage() * 1.5);
                case PYROPHOBE -> {
                    if (cause == DamageCause.FIRE || cause == DamageCause.FIRE_TICK
                            || cause == DamageCause.LAVA || cause == DamageCause.HOT_FLOOR) {
                        event.setDamage(event.getDamage() * 2.0);
                    }
                }
                default -> {
                }
            }
        }
    }

    // ------------------------------------------------------------ degats infliges

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Player attacker = null;
        boolean melee = false;
        if (event.getDamager() instanceof Player direct) {
            attacker = direct;
            melee = true;
        } else if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null) {
            return;
        }

        if (manager.has(attacker, Power.VAMPIRE) && event.getEntity() != attacker) {
            double healed = event.getFinalDamage() * 0.30;
            if (healed > 0) {
                attacker.heal(healed);
            }
        }
        if (melee && manager.has(attacker, Power.MAINS_DE_BEURRE)
                && ThreadLocalRandom.current().nextDouble() < 0.08) {
            dropHeldItem(attacker);
        }
    }

    private void dropHeldItem(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            return;
        }
        ItemStack copy = hand.clone();
        player.getInventory().setItemInMainHand(null);
        Item dropped = player.getWorld().dropItem(player.getEyeLocation(), copy);
        dropped.setPickupDelay(40);
        dropped.setVelocity(player.getLocation().getDirection().multiply(0.2));
        player.sendActionBar(Component.text("Tes mains glissent... tu as lâché ton objet !", NamedTextColor.RED));
    }

    // ------------------------------------------------------------ faim

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (manager.has(player, Power.ESTOMAC_D_ACIER)
                && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }
}
