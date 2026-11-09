package com.pouvoirs.roulette;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.Sound;
import net.kyori.adventure.title.Title;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Points gagnes en tuant, et Livre de la roulette obtenu tous les 100 points.
 * Les points sont sauvegardes sur le joueur (donnees persistantes).
 */
public final class PointsManager {

    private final PouvoirsPlugin plugin;
    private final NamespacedKey pointsKey;
    private final NamespacedKey bookKey;

    public PointsManager(PouvoirsPlugin plugin) {
        this.plugin = plugin;
        this.pointsKey = new NamespacedKey(plugin, "points");
        this.bookKey = new NamespacedKey(plugin, "livre_roulette");
    }

    // ------------------------------------------------------------ points

    public int getPoints(Player player) {
        Integer value = player.getPersistentDataContainer().get(pointsKey, PersistentDataType.INTEGER);
        if (value == null) {
            return 0;
        }
        return value;
    }

    public void setPoints(Player player, int points) {
        player.getPersistentDataContainer().set(pointsKey, PersistentDataType.INTEGER, Math.max(0, points));
    }

    /** Nombre de points necessaires pour recevoir un livre. */
    public int threshold() {
        return Math.max(1, plugin.getConfig().getInt("points.book-threshold", 100));
    }

    public int playerKillPoints() {
        return plugin.getConfig().getInt("points.player-kill", 10);
    }

    /** Points pour un monstre tue : 0 si les monstres ne comptent pas. */
    public int mobPoints(LivingEntity dead) {
        String mode = plugin.getConfig().getString("points.mob-kills", "hostile").toLowerCase(Locale.ROOT);
        boolean eligible;
        if (mode.equals("off")) {
            eligible = false;
        } else if (mode.equals("all")) {
            eligible = dead instanceof Mob;
        } else {
            eligible = dead instanceof Enemy;
        }
        if (!eligible) {
            return 0;
        }
        double bigChance = plugin.getConfig().getDouble("points.mob-big-chance", 0.3);
        if (ThreadLocalRandom.current().nextDouble() < bigChance) {
            return plugin.getConfig().getInt("points.mob-big-points", 5);
        }
        return plugin.getConfig().getInt("points.mob-small-points", 2);
    }

    /** Ajoute des points et donne un livre chaque fois que le seuil est atteint. */
    public void addPoints(Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        int needed = threshold();
        int total = getPoints(player) + amount;
        int books = 0;
        while (total >= needed) {
            total -= needed;
            books++;
        }
        setPoints(player, total);
        player.sendActionBar(Component.text("+" + amount + " points  (" + total + "/" + needed + ")",
                NamedTextColor.GOLD));
        for (int i = 0; i < books; i++) {
            giveBook(player);
        }
    }

    // ------------------------------------------------------------ livre

    public ItemStack createBook() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain("Livre de la roulette", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        lore.add(plain("Clic droit : relance la roulette.", NamedTextColor.YELLOW));
        lore.add(plain("Le nouveau pouvoir s'ajoute aux tiens.", NamedTextColor.GRAY));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(bookKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isBook(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(bookKey, PersistentDataType.BYTE);
    }

    /** Donne un livre (le pose au sol si l'inventaire est plein). */
    public void giveBook(Player player) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(createBook());
        for (ItemStack extra : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
        player.showTitle(Title.title(
                Component.text("Livre obtenu !", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("Clic droit pour relancer la roulette", NamedTextColor.GRAY)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
    }

    /** Retire un livre de l'inventaire. Renvoie false si le joueur n'en a pas. */
    public boolean consumeBook(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (!isBook(item)) {
                continue;
            }
            if (item.getAmount() > 1) {
                item.setAmount(item.getAmount() - 1);
                inventory.setItem(i, item);
            } else {
                inventory.setItem(i, null);
            }
            return true;
        }
        return false;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
