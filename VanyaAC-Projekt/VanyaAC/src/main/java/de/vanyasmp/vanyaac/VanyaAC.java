package de.vanyasmp.vanyaac;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VanyaAC extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String PREFIX = "&8[&dVanyaAC&8] ";

    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();
    private ViolationManager violations;
    private long lastTickNanos = System.nanoTime();
    private volatile long lagUntil;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        violations = new ViolationManager(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new ConnectionListener(this), this);
        pm.registerEvents(new MovementListener(this), this);
        pm.registerEvents(new CombatListener(this), this);
        pm.registerEvents(new WorldListener(this), this);

        PluginCommand cmd = getCommand("vac");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // VL-Abbau alle 30 Sekunden
        getServer().getScheduler().runTaskTimer(this, violations::decay, 600L, 600L);

        // Server-Lag erkennen (Paketstaus nach Lagspikes sind kein Cheat)
        getServer().getScheduler().runTaskTimer(this, () -> {
            long n = System.nanoTime();
            long ms = (n - lastTickNanos) / 1_000_000L;
            lastTickNanos = n;
            if (ms > 200) {
                lagUntil = System.currentTimeMillis() + 3000;
                for (PlayerData d : data.values()) {
                    d.timerBalance = 0;
                    d.lastMoveNanos = 0;
                }
            }
        }, 1L, 1L);

        getLogger().info("VanyaAC aktiviert.");
    }

    @Override
    public void onDisable() {
        data.clear();
    }

    // ---------- Hilfsmethoden ----------

    public static Component msg(String s) {
        return LEGACY.deserialize(s);
    }

    public PlayerData data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), u -> new PlayerData());
    }

    public void removeData(UUID id) {
        data.remove(id);
    }

    public ViolationManager violations() {
        return violations;
    }

    public boolean enabled(String check) {
        return getConfig().getBoolean("checks." + check + ".enabled", true);
    }

    public double weight(String check) {
        return getConfig().getDouble("checks." + check + ".weight", 1.0);
    }

    public int buf(String check, int def) {
        return getConfig().getInt("checks." + check + ".buffer", def);
    }

    public boolean isBypassed(Player p) {
        return p.hasPermission(getConfig().getString("general.bypass-permission", "vanyaac.bypass"));
    }

    /** true, wenn der Server gerade laggt (TPS niedrig oder kuerzlicher Lagspike). */
    public boolean isLagging() {
        if (System.currentTimeMillis() < lagUntil) return true;
        double[] tps = Bukkit.getTPS();
        return tps.length > 0 && tps[0] < getConfig().getDouble("general.min-tps", 17.5);
    }

    // ---------- Befehl ----------

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            s.sendMessage(msg(PREFIX + "&7Befehle:"));
            s.sendMessage(msg("&d/vac alerts &8- &7Alerts an/aus"));
            s.sendMessage(msg("&d/vac info <Spieler> &8- &7VL anzeigen"));
            s.sendMessage(msg("&d/vac reset <Spieler> &8- &7VL zuruecksetzen"));
            s.sendMessage(msg("&d/vac reload &8- &7Config neu laden"));
            return true;
        }

        String sub = a[0].toLowerCase(Locale.ROOT);

        if (sub.equals("alerts")) {
            if (!(s instanceof Player p)) {
                s.sendMessage(msg("&cNur fuer Spieler."));
                return true;
            }
            if (!p.hasPermission("vanyaac.alerts")) {
                s.sendMessage(msg("&cKeine Berechtigung."));
                return true;
            }
            PlayerData d = data(p);
            d.alerts = !d.alerts;
            s.sendMessage(msg(PREFIX + "&7Alerts " + (d.alerts ? "&aaktiviert" : "&cdeaktiviert") + "&7."));
            return true;
        }

        if (!s.hasPermission("vanyaac.admin")) {
            s.sendMessage(msg("&cKeine Berechtigung."));
            return true;
        }

        if (sub.equals("reload")) {
            reloadConfig();
            s.sendMessage(msg(PREFIX + "&7Config neu geladen."));
            return true;
        }

        if (sub.equals("info") || sub.equals("reset")) {
            if (a.length < 2) {
                s.sendMessage(msg("&cNutzung: /vac " + sub + " <Spieler>"));
                return true;
            }
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) {
                s.sendMessage(msg("&cSpieler nicht online."));
                return true;
            }
            PlayerData d = data(t);
            if (sub.equals("reset")) {
                d.vl.clear();
                d.punished.clear();
                d.buffers.clear();
                s.sendMessage(msg(PREFIX + "&7VL von &f" + t.getName() + " &7zurueckgesetzt."));
                return true;
            }
            if (d.vl.isEmpty()) {
                s.sendMessage(msg(PREFIX + "&f" + t.getName() + " &7hat keine Verstoesse."));
                return true;
            }
            s.sendMessage(msg(PREFIX + "&7Verstoesse von &f" + t.getName() + "&7:"));
            for (Map.Entry<String, Double> en : d.vl.entrySet()) {
                s.sendMessage(msg("&8- &d" + en.getKey() + " &7VL "
                        + String.format(Locale.ROOT, "%.1f", en.getValue())));
            }
            return true;
        }

        s.sendMessage(msg("&cUnbekannter Unterbefehl. /vac help"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : List.of("alerts", "info", "reset", "reload", "help")) {
                if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
            }
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("info") || a[0].equalsIgnoreCase("reset"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
