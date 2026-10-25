package dev.lorenzzz.lobbycore.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * An axis-aligned box in one world, read from config. Used to fence in where lobby blocks may be
 * placed and where the launchpads live.
 *
 * <p>The world is resolved lazily and then held: {@link #contains} runs per block placement and
 * a {@code Bukkit.getWorld(name)} on every call is a map lookup this does not need to repeat.
 * Comparing world objects rather than names also means a world unloaded and reloaded under the
 * same name does not silently keep matching.
 */
public final class Region {

    private final String worldName;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    private volatile World cachedWorld;

    public Region(String worldName, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.worldName = worldName;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    /** Reads every child of a {@code regions:} section; an absent section yields an empty list. */
    public static List<Region> parseAll(ConfigurationSection section) {
        List<Region> regions = new ArrayList<>();
        if (section == null) return regions;

        for (String key : section.getKeys(false)) {
            ConfigurationSection region = section.getConfigurationSection(key);
            if (region == null) continue;
            regions.add(new Region(
                    region.getString("world", "world"),
                    region.getInt("min.x", -100), region.getInt("min.y", -64), region.getInt("min.z", -100),
                    region.getInt("max.x", 100), region.getInt("max.y", 320), region.getInt("max.z", 100)));
        }
        return regions;
    }

    public boolean contains(Location location) {
        World world = location.getWorld();
        if (world == null) return false;

        World resolved = cachedWorld;
        if (resolved == null) {
            resolved = Bukkit.getWorld(worldName);
            cachedWorld = resolved;
        }

        if (resolved != null) {
            if (world != resolved) return false;
        } else if (!world.getName().equals(worldName)) {
            // The world is not loaded yet: fall back to the name so a region in a lazily loaded
            // world still works once it comes up.
            return false;
        }

        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** True when no region is configured (everything allowed) or the point is inside one. */
    public static boolean allowed(List<Region> regions, Location location) {
        if (regions.isEmpty()) return true;
        for (Region region : regions) {
            if (region.contains(location)) return true;
        }
        return false;
    }
}
