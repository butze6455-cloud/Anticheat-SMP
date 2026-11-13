package de.vanyasmp.vanyaac;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Locale;

/** Reach, KillAura (Winkel, Multi, Wand) und AutoClicker. */
public class CombatListener implements Listener {

    private final VanyaAC plugin;

    public CombatListener(VanyaAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        long now = System.currentTimeMillis();
        PlayerData d = plugin.data(p);

        // Mace-Smash schleudert den Angreifer: Bewegungs-Checks kurz aussetzen
        if (p.getInventory().getItemInMainHand().getType() == Material.MACE) {
            d.velocityUntil = Math.max(d.velocityUntil, now + 2500);
        }

        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (plugin.isBypassed(p)) return;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;

        Entity target = e.getEntity();
        if (target.equals(p)) return;

        boolean cancel = false;
        Location eye = p.getEyeLocation();
        Vector eyeVec = eye.toVector();
        BoundingBox bb = target.getBoundingBox();
        Vector nearest = new Vector(
                clamp(eyeVec.getX(), bb.getMinX(), bb.getMaxX()),
                clamp(eyeVec.getY(), bb.getMinY(), bb.getMaxY()),
                clamp(eyeVec.getZ(), bb.getMinZ(), bb.getMaxZ()));
        double dist = nearest.distance(eyeVec);

        // ---- Reach ----
        if (plugin.enabled("reach")) {
            AttributeInstance ai = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
            double range = ai == null ? 3.0 : ai.getValue();
            double tol = plugin.getConfig().getDouble("checks.reach.tolerance", 0.3);
            double allowed = range + tol + Math.min(p.getPing(), 250) * 0.002;
            boolean bad = dist > allowed;
            if (bad) cancel = true;
            if (d.buffer("reach", bad, plugin.buf("reach", 2))) {
                plugin.violations().flag(p, "Reach", String.format(Locale.ROOT,
                        "%.2f > %.2f", dist, allowed), plugin.weight("reach"));
            }
        }

        // ---- KillAura ----
        if (plugin.enabled("killaura")) {
            // Mehrere Ziele in kurzer Zeit
            d.recentTargets.values().removeIf(t -> now - t > 200);
            d.recentTargets.put(target.getUniqueId(), now);
            int multi = plugin.getConfig().getInt("checks.killaura.multi-targets", 3);
            if (d.recentTargets.size() >= multi) {
                plugin.violations().flag(p, "KillAura", "Multi (" + d.recentTargets.size() + " Ziele)",
                        plugin.weight("killaura"));
                d.recentTargets.clear();
                cancel = true;
            }

            // Blickwinkel zum Ziel
            Vector toCenter = bb.getCenter().subtract(eyeVec);
            if (toCenter.length() > 1.8 && bb.getWidthX() < 3.0 && bb.getHeight() < 4.0) {
                double angle = Math.toDegrees(eye.getDirection().angle(toCenter));
                double max = plugin.getConfig().getDouble("checks.killaura.max-angle", 70);
                if (d.buffer("ka-angle", angle > max, 3)) {
                    plugin.violations().flag(p, "KillAura", String.format(Locale.ROOT,
                            "Winkel=%.0f", angle), plugin.weight("killaura"));
                    cancel = true;
                }
            }

            // Treffer durch Waende
            boolean walled = blocked(eye, eyeVec, nearest) && blocked(eye, eyeVec, bb.getCenter());
            if (d.buffer("ka-wall", walled, 3)) {
                plugin.violations().flag(p, "KillAura", "Treffer durch Block", plugin.weight("killaura"));
                cancel = true;
            }
        }

        if (cancel && plugin.getConfig().getBoolean("general.cancel-hits", true)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        if (!plugin.enabled("autoclicker")) return;
        Player p = e.getPlayer();
        if (plugin.isBypassed(p)) return;

        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();
        d.clicks.addLast(now);
        while (!d.clicks.isEmpty() && now - d.clicks.peekFirst() > 1000) d.clicks.pollFirst();

        int max = plugin.getConfig().getInt("checks.autoclicker.max-cps", 22);
        boolean bad = d.clicks.size() > max;
        if (d.buffer("autoclicker", bad, 3)) {
            plugin.violations().flag(p, "AutoClicker", "CPS=" + d.clicks.size(), plugin.weight("autoclicker"));
            d.clicks.clear();
        }
    }

    private boolean blocked(Location eye, Vector eyeVec, Vector point) {
        Vector dir = point.clone().subtract(eyeVec);
        double len = dir.length();
        if (len < 0.6) return false;
        dir.normalize();
        RayTraceResult r = eye.getWorld().rayTraceBlocks(eye, dir, len, FluidCollisionMode.NEVER, true);
        if (r == null || r.getHitBlock() == null) return false;
        return r.getHitPosition().distance(eyeVec) < len - 0.3;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
