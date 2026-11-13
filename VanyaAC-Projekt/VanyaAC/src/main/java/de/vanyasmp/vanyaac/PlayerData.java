package de.vanyasmp.vanyaac;

import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PlayerData {

    // Verstoesse
    public final Map<String, Double> vl = new HashMap<>();
    public final Set<String> punished = new HashSet<>();
    public final Map<String, Double> buffers = new HashMap<>();
    public final Map<String, Long> lastAlert = new HashMap<>();
    public boolean alerts;
    public long joinTime = System.currentTimeMillis();

    // Bewegung
    public boolean hasLast;
    public double lastDy;
    public int airTicks;
    public double fallAccum;
    public double groundY;
    public boolean hasGroundY;
    public Location lastGround;
    public long exemptUntil, velocityUntil, teleportUntil, iceUntil, ignoreTpUntil;

    // Timer
    public long lastMoveNanos;
    public double timerBalance;

    // Klicks / Abbau / Kampf
    public final ArrayDeque<Long> clicks = new ArrayDeque<>();
    public final ArrayDeque<Long> breaks = new ArrayDeque<>();
    public final Map<UUID, Long> recentTargets = new HashMap<>();

    // Xray-Statistik
    public int minedStone, minedRare;
    public long lastXrayAlert;

    /**
     * Puffer gegen Fehlalarme: schlechte Werte erhoehen, gute senken.
     * Gibt true zurueck, wenn der Schwellenwert erreicht wurde.
     */
    public boolean buffer(String key, boolean bad, double threshold) {
        double b = buffers.getOrDefault(key, 0.0);
        if (bad) {
            b += 1.0;
            if (b >= threshold) {
                buffers.put(key, threshold / 2.0);
                return true;
            }
        } else {
            b = Math.max(0.0, b - 0.25);
        }
        buffers.put(key, b);
        return false;
    }

    public void resetMovement() {
        hasLast = false;
        hasGroundY = false;
        airTicks = 0;
        fallAccum = 0;
        lastMoveNanos = 0;
        timerBalance = 0;
    }
}
