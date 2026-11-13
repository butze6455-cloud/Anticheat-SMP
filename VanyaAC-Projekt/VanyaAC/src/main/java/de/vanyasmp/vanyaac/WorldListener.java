package de.vanyasmp.vanyaac;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Nuker, BlockReach, Xray-Statistik und Kolben-Ausnahmen. */
public class WorldListener implements Listener {

    private static final Set<Material> STONE_LIKE = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.NETHERRACK, Material.ANDESITE,
            Material.DIORITE, Material.GRANITE, Material.TUFF, Material.BLACKSTONE, Material.BASALT);

    private static final Set<Material> RARE_ORES = EnumSet.of(
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Material.ANCIENT_DEBRIS,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);

    private final VanyaAC plugin;

    public WorldListener(VanyaAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (plugin.isBypassed(p)) return;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;

        PlayerData d = plugin.data(p);
        Block b = e.getBlock();
        long now = System.currentTimeMillis();
        boolean cancel = false;

        // ---- BlockReach ----
        if (plugin.enabled("blockreach")) {
            AttributeInstance ai = p.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
            double range = ai == null ? 4.5 : ai.getValue();
            double tol = plugin.getConfig().getDouble("checks.blockreach.tolerance", 0.6);
            double allowed = range + 0.9 + tol;
            double dist = p.getEyeLocation().distance(b.getLocation().add(0.5, 0.5, 0.5));
            if (dist > allowed) {
                plugin.violations().flag(p, "BlockReach", String.format(Locale.ROOT,
                        "%.2f > %.2f", dist, allowed), plugin.weight("blockreach"));
                cancel = true;
            }
        }

        // ---- Nuker ----
        if (plugin.enabled("nuker")) {
            d.breaks.addLast(now);
            while (!d.breaks.isEmpty() && now - d.breaks.peekFirst() > 1000) d.breaks.pollFirst();
            int max = plugin.getConfig().getInt("checks.nuker.max-per-second", 24);
            if (d.buffer("nuker", d.breaks.size() > max, 2)) {
                plugin.violations().flag(p, "Nuker", d.breaks.size() + " Bloecke/s", plugin.weight("nuker"));
                cancel = true;
            }
        }

        if (cancel) {
            e.setCancelled(true);
            return;
        }

        // ---- Xray-Statistik (nur Alarm fuer das Team) ----
        if (plugin.enabled("xray")) {
            Material m = b.getType();
            if (STONE_LIKE.contains(m)) {
                d.minedStone++;
            } else if (RARE_ORES.contains(m)) {
                d.minedRare++;
                int minSamples = plugin.getConfig().getInt("checks.xray.min-samples", 200);
                int minRare = plugin.getConfig().getInt("checks.xray.min-rare", 4);
                double maxRatio = plugin.getConfig().getDouble("checks.xray.max-ratio", 0.03);
                int total = d.minedStone + d.minedRare;
                double ratio = (double) d.minedRare / total;
                if (total >= minSamples && d.minedRare >= minRare && ratio > maxRatio
                        && now - d.lastXrayAlert > 60_000) {
                    d.lastXrayAlert = now;
                    plugin.violations().flag(p, "Xray", String.format(Locale.ROOT,
                            "%d seltene Erze auf %d Bloecke (%.1f%%)", d.minedRare, total, ratio * 100),
                            plugin.weight("xray"));
                    d.minedStone /= 2;
                    d.minedRare /= 2;
                }
            }
            if (d.minedStone + d.minedRare > 4000) {
                d.minedStone /= 2;
                d.minedRare /= 2;
            }
        }
    }

    // Kolben schieben Spieler: Bewegungs-Checks in der Naehe kurz aussetzen
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        pistonNear(e.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        pistonNear(e.getBlock());
    }

    private void pistonNear(Block b) {
        long until = System.currentTimeMillis() + 2000;
        Location loc = b.getLocation();
        for (Player p : b.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) < 36) {
                PlayerData d = plugin.data(p);
                d.velocityUntil = Math.max(d.velocityUntil, until);
            }
        }
    }
}
