package de.vanyasmp.vanyaac;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ViolationManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VanyaAC plugin;

    public ViolationManager(VanyaAC plugin) {
        this.plugin = plugin;
    }

    /** Verstoss melden. Gibt die neue VL des Spielers fuer diesen Check zurueck. */
    public double flag(Player p, String check, String info, double weight) {
        PlayerData d = plugin.data(p);
        FileConfiguration cfg = plugin.getConfig();
        double v = d.vl.merge(check, weight, Double::sum);
        long now = System.currentTimeMillis();

        Long last = d.lastAlert.get(check);
        if (last == null || now - last >= 750) {
            d.lastAlert.put(check, now);
            String raw = cfg.getString("alerts.format",
                    "&8[&dVanyaAC&8] &f%player% &7> &d%check% &8(&7%info%&8) &7VL &d%vl%")
                    .replace("%player%", p.getName())
                    .replace("%check%", check)
                    .replace("%info%", info)
                    .replace("%vl%", String.format(Locale.ROOT, "%.1f", v))
                    .replace("%ping%", String.valueOf(p.getPing()));
            Component msg = LEGACY.deserialize(raw);

            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("vanyaac.alerts") && plugin.data(staff).alerts) {
                    staff.sendMessage(msg);
                }
            }
            if (cfg.getBoolean("alerts.console", true)) {
                Bukkit.getConsoleSender().sendMessage(msg);
            }
            if (cfg.getBoolean("alerts.log-file", true)) {
                log(p.getName() + " | " + check + " | " + info + " | VL "
                        + String.format(Locale.ROOT, "%.1f", v));
            }
        }

        punish(p, d, check, v);
        return v;
    }

    private void punish(Player p, PlayerData d, String check, double v) {
        List<String> entries = plugin.getConfig().getStringList("punishments." + check.toLowerCase(Locale.ROOT));
        for (String entry : entries) {
            int idx = entry.indexOf(':');
            if (idx < 1) continue;
            double threshold;
            try {
                threshold = Double.parseDouble(entry.substring(0, idx).trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            String key = check + ":" + threshold;
            if (v >= threshold && d.punished.add(key)) {
                String cmd = entry.substring(idx + 1).trim()
                        .replace("%player%", p.getName())
                        .replace("%check%", check)
                        .replace("%vl%", String.format(Locale.ROOT, "%.1f", v));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        }
    }

    /** Wird alle 30 Sekunden aufgerufen und senkt die VL. */
    public void decay() {
        double dec = plugin.getConfig().getDouble("vl-decay-per-minute", 4.0) / 2.0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data(p);
            Iterator<Map.Entry<String, Double>> it = d.vl.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Double> en = it.next();
                double nv = en.getValue() - dec;
                if (nv <= 0) it.remove();
                else en.setValue(nv);
            }
            d.punished.removeIf(key -> {
                int i = key.lastIndexOf(':');
                String check = key.substring(0, i);
                double th = Double.parseDouble(key.substring(i + 1));
                return d.vl.getOrDefault(check, 0.0) < th * 0.5;
            });
        }
    }

    private void log(String line) {
        File f = new File(plugin.getDataFolder(), "violations.log");
        String text = "[" + LocalDateTime.now().format(FMT) + "] " + line + System.lineSeparator();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.writeString(f.toPath(), text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        });
    }
}
