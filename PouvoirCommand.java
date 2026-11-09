package com.pouvoirs.roulette;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /pouvoir                          : menu pour activer / desactiver ses pouvoirs (ou ouvrir la roulette)
 * /pouvoir points                   : voir ses points
 * /pouvoir on|off|toggle <pouvoir> : activer / desactiver un de ses pouvoirs
 * Commandes admin :
 * /pouvoir liste                    : liste des identifiants de pouvoirs
 * /pouvoir reset <joueur>           : efface les pouvoirs et redonne un tirage
 * /pouvoir set <pouvoir>           : remplace tes pouvoirs par celui-la (ou /pouvoir set <joueur> <pouvoir>)
 * /pouvoir add <joueur> <pouvoir>   : ajoute un pouvoir
 * /pouvoir givepoints <joueur> <n>  : donne des points (et des livres si le seuil est atteint)
 * /pouvoir givebook <joueur>        : donne un Livre de la roulette
 */
public final class PouvoirCommand implements TabExecutor {

    private static final String ADMIN_PERMISSION = "pouvoirs.admin";

    private final PowerManager manager;
    private final PowerMenu menu;
    private final PointsManager points;

    public PouvoirCommand(PouvoirsPlugin plugin, PowerManager manager, PowerMenu menu, PointsManager points) {
        this.manager = manager;
        this.menu = menu;
        this.points = points;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return showOwn(sender, true);
        }
        if (args[0].equalsIgnoreCase("points")) {
            return showOwn(sender, false);
        }
        if (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("toggle")) {
            return toggleOwn(sender, args);
        }

        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text("Tu n'as pas la permission de faire ça.", NamedTextColor.RED));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "liste" -> {
                List<String> ids = new ArrayList<>();
                for (Power power : Power.values()) {
                    ids.add(power.id());
                }
                sender.sendMessage(Component.text("Pouvoirs : ", NamedTextColor.GOLD)
                        .append(Component.text(String.join(", ", ids), NamedTextColor.YELLOW)));
            }
            case "reset" -> {
                Player target = target(sender, args);
                if (target == null) {
                    return true;
                }
                manager.reset(target);
                menu.openIntro(target);
                sender.sendMessage(Component.text("Pouvoirs de " + target.getName()
                        + " effacés : il peut retirer à la roulette.", NamedTextColor.GREEN));
            }
            case "set", "add" -> {
                // /pouvoir set <pouvoir>  (pour soi)   ou   /pouvoir set <joueur> <pouvoir>
                Player target;
                Power power;
                if (args.length == 2 && Power.fromId(args[1]) != null) {
                    if (!(sender instanceof Player self)) {
                        sender.sendMessage(Component.text("Précise un joueur : /pouvoir " + args[0] + " <joueur> <pouvoir>",
                                NamedTextColor.RED));
                        return true;
                    }
                    target = self;
                    power = Power.fromId(args[1]);
                } else {
                    target = target(sender, args);
                    if (target == null) {
                        return true;
                    }
                    power = args.length >= 3 ? Power.fromId(args[2]) : null;
                }
                if (power == null) {
                    sender.sendMessage(Component.text("Pouvoir inconnu. Utilise /pouvoir liste.", NamedTextColor.RED));
                    return true;
                }
                if (args[0].equalsIgnoreCase("add")) {
                    manager.addPower(target, power);
                } else {
                    manager.setOnly(target, power);
                }
                if (power == Power.COLOSSE) {
                    manager.fillHealth(target);
                }
                sender.sendMessage(Component.text(target.getName() + " a maintenant le pouvoir "
                        + power.displayName() + ".", NamedTextColor.GREEN));
                target.sendMessage(Component.text("Nouveau pouvoir : ", NamedTextColor.GOLD)
                        .append(Component.text(power.displayName(), power.rarity().color(), TextDecoration.BOLD)));
            }
            case "givepoints" -> {
                Player target = target(sender, args);
                if (target == null) {
                    return true;
                }
                int amount;
                try {
                    amount = args.length >= 3 ? Integer.parseInt(args[2]) : 0;
                } catch (NumberFormatException ex) {
                    amount = 0;
                }
                if (amount <= 0) {
                    sender.sendMessage(Component.text("Précise un nombre de points positif.", NamedTextColor.RED));
                    return true;
                }
                points.addPoints(target, amount);
                sender.sendMessage(Component.text(amount + " points donnés à " + target.getName() + ".",
                        NamedTextColor.GREEN));
            }
            case "givebook" -> {
                Player target = target(sender, args);
                if (target == null) {
                    return true;
                }
                points.giveBook(target);
                sender.sendMessage(Component.text("Livre de la roulette donné à " + target.getName() + ".",
                        NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text(
                    "Usage : /pouvoir [points | liste | reset | set | add | givepoints | givebook]",
                    NamedTextColor.RED));
        }
        return true;
    }

    /** /pouvoir toggle <pouvoir> | on <pouvoir> | off <pouvoir> */
    private boolean toggleOwn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Cette commande est réservée aux joueurs.");
            return true;
        }
        Power power = args.length >= 2 ? Power.fromId(args[1]) : null;
        if (power == null || !manager.getPowers(player).contains(power)) {
            player.sendMessage(Component.text("Tu n'as pas ce pouvoir. Tape /pouvoir pour voir les tiens.",
                    NamedTextColor.RED));
            return true;
        }
        String mode = args[0].toLowerCase(Locale.ROOT);
        boolean active = manager.isActive(player, power);
        if (mode.equals("toggle") || (mode.equals("on") && !active) || (mode.equals("off") && active)) {
            active = manager.toggle(player, power);
        }
        player.sendMessage(Component.text(power.displayName() + (active ? " activé." : " désactivé."),
                active ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean showOwn(CommandSender sender, boolean openMenu) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Cette commande est réservée aux joueurs.");
            return true;
        }

        List<Power> powers = manager.getPowers(player);
        if (openMenu && !powers.isEmpty()) {
            menu.openPowers(player);
            return true;
        }
        if (powers.isEmpty()) {
            if (manager.isPending(player)) {
                menu.openIntro(player);
                return true;
            }
            player.sendMessage(Component.text(
                    "Tu n'as pas de pouvoir. Gagne des points en tuant pour obtenir un Livre de la roulette.",
                    NamedTextColor.GRAY));
        } else {
            player.sendMessage(Component.text("Tes pouvoirs (" + powers.size() + ") :", NamedTextColor.GOLD));
            for (Power power : powers) {
                player.sendMessage(Component.text("- ", NamedTextColor.GRAY)
                        .append(Component.text(power.displayName(), power.rarity().color(), TextDecoration.BOLD))
                        .append(Component.text(" : " + power.description(), NamedTextColor.GRAY)));
            }
        }

        player.sendMessage(Component.text("Points : " + points.getPoints(player) + "/" + points.threshold()
                + " (" + points.playerKillPoints() + " par joueur tué)", NamedTextColor.YELLOW));
        return true;
    }

    private Player target(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Précise le nom d'un joueur en ligne.", NamedTextColor.RED));
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Joueur introuvable (il doit être en ligne).", NamedTextColor.RED));
        }
        return target;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add("points");
            options.add("on");
            options.add("off");
            options.add("toggle");
            if (sender.hasPermission(ADMIN_PERMISSION)) {
                options.add("liste");
                options.add("reset");
                options.add("set");
                options.add("add");
                options.add("givepoints");
                options.add("givebook");
            }
        } else if (args.length == 2 && sender instanceof Player p
                && (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("toggle"))) {
            for (Power power : manager.getPowers(p)) {
                options.add(power.id());
            }
        } else if (sender.hasPermission(ADMIN_PERMISSION)) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            boolean usesPlayer = sub.equals("reset") || sub.equals("set") || sub.equals("add")
                    || sub.equals("givepoints") || sub.equals("givebook");
            if (args.length == 2 && usesPlayer) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    options.add(player.getName());
                }
                if (sub.equals("set") || sub.equals("add")) {
                    for (Power power : Power.values()) {
                        options.add(power.id());
                    }
                }
            } else if (args.length == 3 && (sub.equals("set") || sub.equals("add"))) {
                for (Power power : Power.values()) {
                    options.add(power.id());
                }
            }
        }

        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
