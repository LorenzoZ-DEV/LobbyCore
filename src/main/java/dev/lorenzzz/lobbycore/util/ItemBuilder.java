package dev.lorenzzz.lobbycore.util;

import dev.lorenzzz.lobbycore.C;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds an {@link ItemStack} from a config section, in the shape every yml in this plugin uses:
 *
 * <pre>
 * material: PLAYER_HEAD      # or `username:` / `base64:` for a head
 * amount: 1
 * display_name: "&amp;aQualcosa"
 * lore: ["riga uno"]
 * glow: true                 # unbreaking 1 + HIDE_ENCHANTS
 * custom_model_data: 6767
 * enchantments: { SHARPNESS: 3 }
 * item_flags: [HIDE_ATTRIBUTES]
 * trim: { enabled: true, pattern: SENTRY, material: NETHERITE }
 * </pre>
 *
 * <p>Enchantments and trims go through {@link Registry} rather than the old enum constants,
 * because those stopped existing in the 1.21 line.
 */
public final class ItemBuilder {

    private ItemBuilder() {}

    /** Parses one item section. Returns an air stack when {@code material} is missing or bogus. */
    public static ItemStack fromConfig(ConfigurationSection section) {
        if (section == null) return new ItemStack(Material.AIR);

        Material material = material(section.getString("material", "STONE"));
        ItemStack item = new ItemStack(material, Math.max(1, section.getInt("amount", 1)));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String name = section.getString("display_name");
        if (name != null && !name.isEmpty()) meta.displayName(C.item(name));

        List<String> lore = section.getStringList("lore");
        if (!lore.isEmpty()) meta.lore(C.items(lore));

        if (section.contains("custom_model_data")) {
            meta.setCustomModelData(section.getInt("custom_model_data"));
        }

        ConfigurationSection enchants = section.getConfigurationSection("enchantments");
        if (enchants != null) {
            for (String key : enchants.getKeys(false)) {
                Enchantment enchantment = enchantment(key);
                if (enchantment != null) meta.addEnchant(enchantment, enchants.getInt(key, 1), true);
            }
        }

        // `glow` is the config-level way of saying "shiny, no tooltip clutter" — a level-1
        // enchantment plus the flag that hides it.
        if (section.getBoolean("glow", false)) {
            Enchantment unbreaking = enchantment("unbreaking");
            if (unbreaking != null) meta.addEnchant(unbreaking, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        if (section.getBoolean("unbreakable", false)) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }

        for (String flag : section.getStringList("item_flags")) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(flag.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }

        applyTrim(meta, section.getConfigurationSection("trim"));
        applySkull(meta, section);

        item.setItemMeta(meta);
        return item;
    }

    /** Stamps a marker onto an item so a listener can recognise it later without name matching. */
    public static ItemStack tag(ItemStack item, NamespacedKey key) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean hasTag(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public static Material material(String name) {
        if (name == null) return Material.STONE;
        Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        return material == null ? Material.STONE : material;
    }

    /** Resolves an enchantment by its modern key, accepting the old {@code SHARPNESS} spelling. */
    public static Enchantment enchantment(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        try {
            return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
        } catch (Exception e) {
            return null;
        }
    }

    /** Slots an item should occupy: {@code slot: 4}, {@code slots: [0,1,2]}, or -1 for "fill". */
    public static List<Integer> slots(ConfigurationSection section) {
        List<Integer> slots = new ArrayList<>();
        if (section.contains("slots")) {
            slots.addAll(section.getIntegerList("slots"));
        } else if (section.contains("slot")) {
            slots.add(section.getInt("slot"));
        }
        return slots;
    }

    private static void applyTrim(ItemMeta meta, ConfigurationSection trim) {
        if (trim == null || !(meta instanceof ArmorMeta armorMeta)) return;
        if (!trim.getBoolean("enabled", false)) return;
        try {
            TrimPattern pattern = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(
                    trim.getString("pattern", "sentry").toLowerCase(Locale.ROOT)));
            TrimMaterial material = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(
                    trim.getString("material", "netherite").toLowerCase(Locale.ROOT)));
            if (pattern != null && material != null) armorMeta.setTrim(new ArmorTrim(material, pattern));
        } catch (Exception ignored) {
        }
    }

    private static void applySkull(ItemMeta meta, ConfigurationSection section) {
        if (!(meta instanceof SkullMeta skull)) return;

        String username = section.getString("username");
        if (username != null && !username.isEmpty()) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(username);
            skull.setOwningPlayer(owner);
            return;
        }

        String base64 = section.getString("base64");
        if (base64 == null || base64.isEmpty()) return;
        URL url = textureUrl(base64);
        if (url == null) return;

        // A random UUID on purpose: the profile is a texture carrier, not a real account, and
        // reusing one id across different heads makes the client cache the first texture for all
        // of them.
        PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "");
        PlayerTextures textures = profile.getTextures();
        textures.setSkin(url);
        profile.setTextures(textures);
        skull.setOwnerProfile(profile);
    }

    /**
     * Pulls the skin URL out of a base64 texture blob — the format head databases hand out.
     * The blob decodes to {@code {"textures":{"SKIN":{"url":"…"}}}}; the URL is taken with a
     * substring rather than a JSON parse so no JSON library has to be shaded for one field.
     */
    private static URL textureUrl(String base64) {
        try {
            String decoded = new String(Base64.getDecoder().decode(base64));
            int start = decoded.indexOf("\"url\"");
            if (start < 0) return null;
            start = decoded.indexOf('"', decoded.indexOf(':', start)) + 1;
            int end = decoded.indexOf('"', start);
            if (start <= 0 || end <= start) return null;
            return URI.create(decoded.substring(start, end)).toURL();
        } catch (Exception e) {
            return null;
        }
    }

    /** A lore list already rendered — kept out of the item path so callers can cache it. */
    public static List<Component> lore(List<String> lines) {
        return C.items(lines);
    }
}
