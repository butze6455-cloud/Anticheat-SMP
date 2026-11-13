package de.vanyasmp.vanyaac;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Fly, HighJump, NoFall, Speed und Timer. */
public class MovementListener implements Listener {

    private static final int GROUND = 1, SPECIAL = 2, BOUNCE = 4, ICE = 8;

    private static final Set<Material> SPECIAL_BLOCKS = EnumSet.of(
            Material.COBWEB, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.BUBBLE_COLUMN,
            Material.HONEY_BLOCK, Material.SCAFFOLDING, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.KELP, Material.KELP_PLANT, Material.WATER, Material.LAVA);

    private static final Set<Material> ICE_BLOCKS = EnumSet.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);

    private final VanyaAC plugin;

    public MovementListener(VanyaAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) return; // nur Kopfdrehung

        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (plugin.isBypassed(p)) {
            d.resetMovement();
            return;
        }
        long now = System.currentTimeMillis();

        if (plugin.enabled("timer") && timer(p, d, e, from)) return;

        double h = Math.hypot(dx, dz);
        int flags = scan(to);
        boolean ground = (flags & GROUND) != 0;

        if ((flags & SPECIAL) != 0) d.exemptUntil = Math.max(d.exemptUntil, now + 700);
        if ((flags & BOUNCE) != 0) d.exemptUntil = Math.max(d.exemptUntil, now + 4000);
        if ((flags & ICE) != 0) d.iceUntil = now + 1200;
        if (p.isGliding()) d.exemptUntil = Math.max(d.exemptUntil, now + 2000);
        if (p.isRiptiding()) d.exemptUntil = Math.max(d.exemptUntil, now + 2500);

        boolean exempt = isExempt(p, d, now);
        boolean reverted = false;
        Location safe = d.lastGround != null && d.lastGround.getWorld() == to.getWorld() ? d.lastGround : from;

        if (ground) {
            // NoFall: Server-Fallstrecke wurde beim Landen zurueckgesetzt, obwohl wir weit gefallen sind
            if (!exempt && d.fallAccum > 3.5 && plugin.enabled("nofall")) {
                boolean bad = p.getFallDistance() < d.fallAccum * 0.4;
                if (d.buffer("nofall", bad, plugin.buf("nofall", 2))) {
                    plugin.violations().flag(p, "NoFall", String.format(Locale.ROOT,
                            "fall=%.1f server=%.1f", d.fallAccum, p.getFallDistance()), plugin.weight("nofall"));
                    p.setFallDistance((float) d.fallAccum); // Schaden wird trotzdem verursacht
                }
            }
            d.groundY = to.getY();
            d.hasGroundY = true;
            d.lastGround = to.clone();
        }

        if (exempt || ground) {
            d.airTicks = 0;
            d.fallAccum = 0;
        } else {
            d.airTicks++;
            if (dy < 0) d.fallAccum += -dy;
            else d.fallAccum = Math.max(0.0, d.fallAccum - dy);

            if (plugin.enabled("fly")) {
                // Gravitationsvorhersage
                if (d.hasLast && d.airTicks >= 3) {
                    double expected = (d.lastDy - 0.08) * 0.98;
                    double tol = plugin.getConfig().getDouble("checks.fly.tolerance", 0.08);
                    boolean bad = dy > expected + tol;
                    if (bad && supportedByEntity(p)) bad = false;
                    if (d.buffer("fly", bad, plugin.buf("fly", 3))) {
                        plugin.violations().flag(p, "Fly", String.format(Locale.ROOT,
                                "dy=%.3f erwartet=%.3f luft=%d", dy, expected, d.airTicks), plugin.weight("fly"));
                        setback(e, d, safe);
                        reverted = true;
                    }
                }
                // Maximale Sprunghoehe
                if (!reverted && d.hasGroundY) {
                    PotionEffect jb = p.getPotionEffect(PotionEffectType.JUMP_BOOST);
                    int lvl = jb == null ? 0 : jb.getAmplifier() + 1;
                    double allowed = 1.3 + 0.62 * lvl + 0.05 * lvl * lvl;
                    double height = to.getY() - d.groundY;
                    boolean bad = height > allowed + 0.15;
                    if (bad && supportedByEntity(p)) bad = false;
                    if (d.buffer("highjump", bad, 2)) {
                        plugin.violations().flag(p, "Fly", String.format(Locale.ROOT,
                                "Sprunghoehe=%.2f max=%.2f", height, allowed), plugin.weight("fly"));
                        setback(e, d, safe);
                        reverted = true;
                    }
                }
            }
        }

        // Speed
        if (!exempt && !reverted && plugin.enabled("speed")) {
            AttributeInstance attr = p.getAttribute(Attribute.MOVEMENT_SPEED);
            double base = attr == null ? 0.1 : attr.getValue();
            double expectedSpeed = base * 2.16; // Bloecke pro Tick am Boden
            double limit = ground ? expectedSpeed * 1.25 + 0.06 : expectedSpeed * 1.25 + 0.38;
            if (now < d.iceUntil) limit *= 2.3;
            limit *= plugin.getConfig().getDouble("checks.speed.multiplier", 1.0);
            boolean bad = h > limit;
            if (d.buffer("speed", bad, plugin.buf("speed", 4))) {
                plugin.violations().flag(p, "Speed", String.format(Locale.ROOT,
                        "h=%.3f max=%.3f", h, limit), plugin.weight("speed"));
                setback(e, d, from);
                reverted = true;
            }
        }

        if (!reverted) {
            d.lastDy = dy;
            d.hasLast = true;
        }
    }

    // ---------- Timer ----------

    private boolean timer(Player p, PlayerData d, PlayerMoveEvent e, Location from) {
        long nanos = System.nanoTime();
        if (d.lastMoveNanos == 0) {
            d.lastMoveNanos = nanos;
            return false;
        }
        double elapsed = (nanos - d.lastMoveNanos) / 1_000_000.0;
        d.lastMoveNanos = nanos;

        d.timerBalance += 50.0 - elapsed;
        if (d.timerBalance < -2500) d.timerBalance = -2500;

        double threshold = plugin.getConfig().getDouble("checks.timer.threshold-ms", 300);
        if (d.timerBalance > threshold) {
            double bal = d.timerBalance;
            d.timerBalance = 0;
            long now = System.currentTimeMillis();
            if (plugin.isLagging() || now < d.teleportUntil || now - d.joinTime < 3000) return false;
            plugin.violations().flag(p, "Timer", String.format(Locale.ROOT, "Balance=%.0fms", bal),
                    plugin.weight("timer"));
            setback(e, d, from);
            return true;
        }
        return false;
    }

    // ---------- Hilfsmethoden ----------

    private void setback(PlayerMoveEvent e, PlayerData d, Location target) {
        if (!plugin.getConfig().getBoolean("general.setback", true)) return;
        Location t = target.clone();
        t.setYaw(e.getTo().getYaw());
        t.setPitch(e.getTo().getPitch());
        d.ignoreTpUntil = System.currentTimeMillis() + 250;
        e.setTo(t);
        d.hasLast = false;
        d.airTicks = 0;
    }

    private boolean isExempt(Player p, PlayerData d, long now) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (p.getAllowFlight() || p.isFlying() || p.isInsideVehicle() || p.isDead()) return true;
        if (now < d.exemptUntil || now < d.velocityUntil || now < d.teleportUntil) return true;
        if (now - d.joinTime < 3000) return true;
        if (p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            return true;
        }
        if (plugin.isLagging()) return true;
        return p.getPing() > plugin.getConfig().getInt("general.max-ping", 600);
    }

    private boolean supportedByEntity(Player p) {
        for (Entity en : p.getNearbyEntities(1.6, 2.5, 1.6)) {
            if (en instanceof Vehicle || en instanceof Shulker) return true;
        }
        return false;
    }

    private boolean isWaterlogged(Block b) {
        return b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged();
    }

    /** Scannt die Umgebung des Spielers und liefert Bit-Flags. */
    private int scan(Location loc) {
        World w = loc.getWorld();
        double x = loc.getX(), y = loc.getY(), z = loc.getZ();
        int x0 = (int) Math.floor(x - 0.3), x1 = (int) Math.floor(x + 0.3);
        int z0 = (int) Math.floor(z - 0.3), z1 = (int) Math.floor(z + 0.3);
        int yGround = (int) Math.floor(y - 0.03);
        int yFence = (int) Math.floor(y - 0.53);
        int yBodyMin = (int) Math.floor(y - 0.2);
        int yBodyMax = (int) Math.floor(y + 1.8);
        int yLow = (int) Math.floor(y - 1.3);

        int flags = 0;
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                if (!w.isChunkLoaded(bx >> 4, bz >> 4)) continue;
                for (int by = yLow; by <= yBodyMax; by++) {
                    Block b = w.getBlockAt(bx, by, bz);
                    Material m = b.getType();
                    if (m.isAir()) continue;

                    if (by == yGround && !b.isPassable()) flags |= GROUND;
                    if (by == yFence && (Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m)
                            || Tag.FENCE_GATES.isTagged(m))) {
                        flags |= GROUND;
                    }
                    if (by <= yGround) {
                        if (m == Material.SLIME_BLOCK || Tag.BEDS.isTagged(m)) flags |= BOUNCE;
                        if (ICE_BLOCKS.contains(m)) flags |= ICE;
                    }
                    if (by >= yBodyMin && (SPECIAL_BLOCKS.contains(m) || b.isLiquid()
                            || Tag.CLIMBABLE.isTagged(m) || isWaterlogged(b))) {
                        flags |= SPECIAL;
                    }
                }
            }
        }
        return flags;
    }
}
