package com.pouvoirs.roulette;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;
import java.util.UUID;

/**
 * Stocke les pouvoirs de chaque joueur (dans ses donnees persistantes, donc ils survivent
 * aux redemarrages), applique les effets / attributs, et gere l'objet de dash.
 * Un joueur peut avoir plusieurs pouvoirs : ils se cumulent.
 */
public final class PowerManager {

    private final PouvoirsPlugin plugin;
    private final NamespacedKey powersKey;
    private final NamespacedKey legacyKey;
    private final NamespacedKey pendingKey;
    private final NamespacedKey dashItemKey;
    private final NamespacedKey disabledKey;
    private final Map<UUID, Set<Power>> disabled = new HashMap<>();
    private final Map<UUID, List<Power>> cache = new HashMap<>();
    private final Map<UUID, Long> dashReady = new HashMap<>();

    public PowerManager(PouvoirsPlugin plugin) {
        this.plugin = plugin;
        this.powersKey = new NamespacedKey(plugin, "pouvoirs");
        this.legacyKey = new NamespacedKey(plugin, "pouvoir"); // ancien format : un seul pouvoir
        this.pendingKey = new NamespacedKey(plugin, "attente");
        this.dashItemKey = new NamespacedKey(plugin, "objet_dash");
        this.disabledKey = new NamespacedKey(plugin, "desactives");
    }

    // ------------------------------------------------------------ sauvegarde

    /** Liste des pouvoirs sauvegardes sur le joueur (meme ceux qui ne sont pas encore actifs). */
    public List<Power> readSaved(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        String raw = pdc.get(powersKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            raw = pdc.get(legacyKey, PersistentDataType.STRING);
        }
        List<Power> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String part : raw.split(",")) {
            Power power = Power.fromId(part.trim());
            if (power != null && !result.contains(power)) {
                result.add(power);
            }
        }
        return result;
    }

    private boolean hasSavedData(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        return pdc.has(powersKey, PersistentDataType.STRING) || pdc.has(legacyKey, PersistentDataType.STRING);
    }

    private void save(Player player, List<Power> powers) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.remove(legacyKey);
        if (powers.isEmpty()) {
            pdc.remove(powersKey);
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (Power power : powers) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(power.name());
        }
        pdc.set(powersKey, PersistentDataType.STRING, builder.toString());
    }

    // ------------------------------------------------------------ etat

    /** Lit les pouvoirs sauvegardes du joueur et les applique. */
    public void load(Player player) {
        List<Power> powers = readSaved(player);
        disabled.put(player.getUniqueId(), readDisabled(player));
        if (powers.isEmpty()) {
            cache.remove(player.getUniqueId());
            if (hasSavedData(player)) {
                // pouvoir sauvegarde qui n'existe plus : le joueur retire a la roulette
                save(player, powers);
                setPending(player, true);
            }
        } else {
            cache.put(player.getUniqueId(), powers);
        }
        refresh(player);
    }

    public void unload(Player player) {
        cache.remove(player.getUniqueId());
        dashReady.remove(player.getUniqueId());
        disabled.remove(player.getUniqueId());
    }

    public List<Power> getPowers(Player player) {
        List<Power> list = cache.get(player.getUniqueId());
        if (list == null) {
            return List.of();
        }
        return list;
    }

    public boolean hasPower(Player player) {
        return !getPowers(player).isEmpty();
    }

    /** Vrai si le joueur possede le pouvoir ET qu'il est active. */
    public boolean has(Player player, Power power) {
        return getPowers(player).contains(power) && isActive(player, power);
    }

    public boolean isActive(Player player, Power power) {
        Set<Power> off = disabled.get(player.getUniqueId());
        return off == null || !off.contains(power);
    }

    /** Pouvoirs possedes ET actives. */
    public List<Power> getActivePowers(Player player) {
        List<Power> result = new ArrayList<>();
        for (Power power : getPowers(player)) {
            if (isActive(player, power)) {
                result.add(power);
            }
        }
        return result;
    }

    private Set<Power> readDisabled(Player player) {
        Set<Power> result = EnumSet.noneOf(Power.class);
        String raw = player.getPersistentDataContainer().get(disabledKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split(",")) {
            Power power = Power.fromId(part.trim());
            if (power != null) {
                result.add(power);
            }
        }
        return result;
    }

    private void saveDisabled(Player player, Set<Power> off) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (off.isEmpty()) {
            pdc.remove(disabledKey);
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (Power power : off) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(power.name());
        }
        pdc.set(disabledKey, PersistentDataType.STRING, builder.toString());
    }

    /** Active ou desactive un pouvoir possede. Renvoie le nouvel etat (true = actif). */
    public boolean toggle(Player player, Power power) {
        if (!getPowers(player).contains(power)) {
            return false;
        }
        Set<Power> off = disabled.computeIfAbsent(player.getUniqueId(), k -> EnumSet.noneOf(Power.class));
        boolean nowActive;
        if (off.contains(power)) {
            off.remove(power);
            nowActive = true;
        } else {
            off.add(power);
            nowActive = false;
        }
        saveDisabled(player, off);

        if (!nowActive) {
            removeEffects(player, power);
            if (power == Power.DASH && !plugin.getConfig().getBoolean("dash.for-everyone", false)) {
                removeDashItems(player);
            }
        }
        refresh(player);
        return nowActive;
    }

    /** Retire les attributs et effets infinis d'un seul pouvoir. */
    private void removeEffects(Player player, Power power) {
        List<Power.Attr> attrs = power.attributes();
        for (int i = 0; i < attrs.size(); i++) {
            AttributeInstance instance = player.getAttribute(attrs.get(i).attribute());
            if (instance == null) {
                continue;
            }
            AttributeModifier modifier = findModifier(instance, modifierKey(power, i));
            if (modifier != null) {
                instance.removeModifier(modifier);
            }
        }
        for (Power.Fx fx : power.effects()) {
            boolean usedByOther = false;
            for (Power other : getActivePowers(player)) {
                for (Power.Fx otherFx : other.effects()) {
                    if (otherFx.type().equals(fx.type())) {
                        usedByOther = true;
                    }
                }
            }
            PotionEffect current = player.getPotionEffect(fx.type());
            if (!usedByOther && current != null && current.isInfinite()) {
                player.removePotionEffect(fx.type());
            }
        }
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && player.getHealth() > maxHealth.getValue()) {
            player.setHealth(maxHealth.getValue());
        }
    }

    /** "En attente" = le joueur a le droit de tirer son pouvoir mais ne l'a pas encore fait. */
    public boolean isPending(Player player) {
        Byte value = player.getPersistentDataContainer().get(pendingKey, PersistentDataType.BYTE);
        return value != null && value == 1;
    }

    public void setPending(Player player, boolean pending) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (pending) {
            pdc.set(pendingKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            pdc.remove(pendingKey);
        }
    }

    // ------------------------------------------------------------ actions

    /**
     * Sauvegarde un pouvoir sur le joueur SANS l'activer tout de suite
     * (utilise pendant la roulette : le resultat est deja decide, mais pas encore revele).
     */
    public void grantQuietly(Player player, Power power) {
        List<Power> list = new ArrayList<>(readSaved(player));
        if (!list.contains(power)) {
            list.add(power);
        }
        save(player, list);
        setPending(player, false);
    }

    /** Ajoute un pouvoir aux pouvoirs actuels et l'active. */
    public void addPower(Player player, Power power) {
        grantQuietly(player, power);
        load(player);
    }

    /** Remplace tous les pouvoirs du joueur par un seul. */
    public void setOnly(Player player, Power power) {
        clear(player);
        List<Power> list = new ArrayList<>();
        list.add(power);
        save(player, list);
        setPending(player, false);
        load(player);
    }

    /** Efface tous les pouvoirs du joueur et le remet "en attente" d'un tirage. */
    public void reset(Player player) {
        clear(player);
        save(player, new ArrayList<>());
        disabled.remove(player.getUniqueId());
        player.getPersistentDataContainer().remove(disabledKey);
        setPending(player, true);
    }

    /** Retire les effets, attributs et objets des pouvoirs actuels (sans toucher a la sauvegarde). */
    public void clear(Player player) {
        List<Power> old = cache.remove(player.getUniqueId());

        for (Power power : Power.values()) {
            List<Power.Attr> attrs = power.attributes();
            for (int i = 0; i < attrs.size(); i++) {
                AttributeInstance instance = player.getAttribute(attrs.get(i).attribute());
                if (instance == null) {
                    continue;
                }
                AttributeModifier modifier = findModifier(instance, modifierKey(power, i));
                if (modifier != null) {
                    instance.removeModifier(modifier);
                }
            }
        }

        if (old != null) {
            for (Power power : old) {
                for (Power.Fx fx : power.effects()) {
                    PotionEffect current = player.getPotionEffect(fx.type());
                    if (current != null && current.isInfinite()) {
                        player.removePotionEffect(fx.type());
                    }
                }
            }
        }
        removeDashItems(player);

        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && player.getHealth() > maxHealth.getValue()) {
            player.setHealth(maxHealth.getValue());
        }
    }

    /** Remplit la barre de vie (apres un pouvoir qui ajoute des coeurs). */
    public void fillHealth(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            player.setHealth(maxHealth.getValue());
        }
    }

    /**
     * Remet en place tout ce qui manque (effet efface par du lait, un totem, la mort...).
     * Appelee regulierement et apres les evenements importants.
     */
    public void refresh(Player player) {
        for (Power power : getActivePowers(player)) {
            List<Power.Attr> attrs = power.attributes();
            for (int i = 0; i < attrs.size(); i++) {
                Power.Attr attr = attrs.get(i);
                AttributeInstance instance = player.getAttribute(attr.attribute());
                if (instance == null) {
                    continue;
                }
                NamespacedKey key = modifierKey(power, i);
                if (findModifier(instance, key) == null) {
                    instance.addModifier(new AttributeModifier(key, attr.amount(), attr.operation()));
                }
            }

            for (Power.Fx fx : power.effects()) {
                if (player.getPotionEffect(fx.type()) == null) {
                    player.addPotionEffect(new PotionEffect(
                            fx.type(), PotionEffect.INFINITE_DURATION, fx.amplifier(), true, false, true));
                }
            }
        }

        if (wantsDash(player)) {
            ensureDashItem(player);
        }
    }

    public void startRefreshTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                refresh(player);
            }
        }, 40L, 40L);
    }

    // ------------------------------------------------------------ dash

    /** Le joueur doit-il avoir l'objet de dash ? (pouvoir Dash, ou dash pour tout le monde) */
    public boolean wantsDash(Player player) {
        return has(player, Power.DASH) || plugin.getConfig().getBoolean("dash.for-everyone", false);
    }

    public ItemStack createDashItem() {
        ItemStack item = new ItemStack(Material.FEATHER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain("Dash", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true));
        List<Component> lore = new ArrayList<>();
        lore.add(plain("Clic droit : fonce là où tu regardes.", NamedTextColor.GRAY));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(dashItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isDashItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(dashItemKey, PersistentDataType.BYTE);
    }

    private boolean holdsDashItem(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (isDashItem(inventory.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    private void ensureDashItem(Player player) {
        if (!holdsDashItem(player)) {
            player.getInventory().addItem(createDashItem());
        }
    }

    private void removeDashItems(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (isDashItem(inventory.getItem(i))) {
                inventory.setItem(i, null);
            }
        }
    }

    /** Lance le dash (avec temps de recharge). */
    public void useDash(Player player) {
        long now = System.currentTimeMillis();
        long cooldownMillis = (long) (plugin.getConfig().getDouble("dash.cooldown-seconds", 3.0) * 1000.0);
        Long ready = dashReady.get(player.getUniqueId());
        if (ready != null && ready > now) {
            long seconds = (ready - now + 999L) / 1000L;
            player.sendActionBar(Component.text("Dash prêt dans " + seconds + " s", NamedTextColor.GRAY));
            return;
        }
        dashReady.put(player.getUniqueId(), now + cooldownMillis);

        double strength = plugin.getConfig().getDouble("dash.strength", 1.6);
        Vector velocity = player.getLocation().getDirection().clone().multiply(strength);
        // Direction exacte du regard (haut, bas, devant). Petit decollage seulement si on est au sol.
        if (player.isOnGround() && velocity.getY() < 0.15) {
            velocity.setY(0.15);
        }
        player.setVelocity(velocity);
        player.setFallDistance(0f);

        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 15, 0.3, 0.1, 0.3, 0.02);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.7f, 1.2f);
    }

    // ------------------------------------------------------------ utilitaires

    private NamespacedKey modifierKey(Power power, int index) {
        return new NamespacedKey(plugin, "pouvoir_" + power.name().toLowerCase(Locale.ROOT) + "_" + index);
    }

    private AttributeModifier findModifier(AttributeInstance instance, NamespacedKey key) {
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (modifier.getKey().equals(key)) {
                return modifier;
            }
        }
        return null;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
