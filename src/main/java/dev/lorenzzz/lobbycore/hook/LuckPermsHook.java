package dev.lorenzzz.lobbycore.hook;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Prefix, suffix and group weight from LuckPerms.
 *
 * <p>This is the only class in the plugin that names {@code net.luckperms} types, and it is never
 * constructed unless the plugin is present — so a lobby without LuckPerms loads normally and
 * every caller sees empty prefixes and a flat weight.
 *
 * <p>Results are cached for thirty seconds. Chat asks for a prefix on every message and the
 * tablist asks for a weight on every sort pass; LuckPerms' own cache is cheap but not free, and
 * a rank change showing up half a minute late in the tablist is not a problem worth solving.
 */
public final class LuckPermsHook {

    private static final long TTL_SECONDS = 30;

    private final LuckPerms api;

    private final Cache<UUID, String> prefixes = Caffeine.newBuilder()
            .expireAfterWrite(TTL_SECONDS, TimeUnit.SECONDS).maximumSize(512).build();
    private final Cache<UUID, String> suffixes = Caffeine.newBuilder()
            .expireAfterWrite(TTL_SECONDS, TimeUnit.SECONDS).maximumSize(512).build();
    private final Cache<UUID, Integer> weights = Caffeine.newBuilder()
            .expireAfterWrite(TTL_SECONDS, TimeUnit.SECONDS).maximumSize(512).build();

    private LuckPermsHook(LuckPerms api) {
        this.api = api;
    }

    /** {@code null} when LuckPerms is absent or has not finished loading. */
    public static LuckPermsHook tryCreate() {
        try {
            return new LuckPermsHook(LuckPermsProvider.get());
        } catch (IllegalStateException | NoClassDefFoundError e) {
            return null;
        }
    }

    public String prefix(Player player) {
        return prefixes.get(player.getUniqueId(), uuid -> {
            CachedMetaData meta = metaData(uuid);
            String value = meta == null ? null : meta.getPrefix();
            return value == null ? "" : value;
        });
    }

    public String suffix(Player player) {
        return suffixes.get(player.getUniqueId(), uuid -> {
            CachedMetaData meta = metaData(uuid);
            String value = meta == null ? null : meta.getSuffix();
            return value == null ? "" : value;
        });
    }

    /**
     * The weight of the player's primary group, used to order the tablist. Higher is more
     * senior; a group with no weight set counts as zero.
     */
    public int weight(Player player) {
        return weights.get(player.getUniqueId(), uuid -> {
            User user = api.getUserManager().getUser(uuid);
            if (user == null) return 0;
            Group group = api.getGroupManager().getGroup(user.getPrimaryGroup());
            return group == null ? 0 : group.getWeight().orElse(0);
        });
    }

    public String primaryGroup(Player player) {
        User user = api.getUserManager().getUser(player.getUniqueId());
        return user == null ? "default" : user.getPrimaryGroup();
    }

    /** Drops one player's cached values — called on quit so a rejoin re-reads them. */
    public void invalidate(UUID uuid) {
        prefixes.invalidate(uuid);
        suffixes.invalidate(uuid);
        weights.invalidate(uuid);
    }

    private CachedMetaData metaData(UUID uuid) {
        User user = api.getUserManager().getUser(uuid);
        return user == null ? null : user.getCachedData().getMetaData();
    }
}
