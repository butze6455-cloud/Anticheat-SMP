package de.vanyasmp.vanyaac;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

/** Sammelt alle Ereignisse, die legitime Bewegungsspruenge verursachen (Ausnahmen). */
public class ConnectionListener implements Listener {

    private final VanyaAC plugin;

    public ConnectionListener(VanyaAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        d.joinTime = System.currentTimeMillis();
        d.resetMovement();
        if (p.hasPermission("vanyaac.alerts") && plugin.getConfig().getBoolean("alerts.enable-on-join", true)) {
            d.alerts = true;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.removeData(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        long now = System.currentTimeMillis();
        d.resetMovement();
        if (now < d.ignoreTpUntil) {
            // Ruecksetzer durch das Anticheat selbst: keine Freistellung geben
            d.ignoreTpUntil = 0;
            return;
        }
        d.teleportUntil = now + 1500;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.resetMovement();
        d.teleportUntil = System.currentTimeMillis() + 2000;
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.resetMovement();
        d.teleportUntil = System.currentTimeMillis() + 2000;
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.resetMovement();
        d.exemptUntil = System.currentTimeMillis() + 3000;
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.exemptUntil = Math.max(d.exemptUntil, System.currentTimeMillis() + 1500);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.velocityUntil = Math.max(d.velocityUntil, System.currentTimeMillis() + 2500);
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent e) {
        if (e.getExited() instanceof Player p) {
            PlayerData d = plugin.data(p);
            d.exemptUntil = Math.max(d.exemptUntil, System.currentTimeMillis() + 1500);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        EntityType t = e.getEntity().getType();
        if (t != EntityType.WIND_CHARGE && t != EntityType.BREEZE_WIND_CHARGE) return;
        Location loc = e.getEntity().getLocation();
        long until = System.currentTimeMillis() + 4000;
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) < 144) {
                PlayerData d = plugin.data(p);
                d.velocityUntil = Math.max(d.velocityUntil, until);
            }
        }
    }
}
