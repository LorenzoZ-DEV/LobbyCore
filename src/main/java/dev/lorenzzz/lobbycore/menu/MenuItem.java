package dev.lorenzzz.lobbycore.menu;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.util.ItemBuilder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

/**
 * One entry of a menu's {@code items:} block: what it looks like, where it sits, and what
 * clicking it does.
 *
 * <p>The rendered stack is cached when the item holds no placeholder, which is most of them —
 * fillers, static buttons. An item whose name or lore still has a {@code %…%} is rebuilt per
 * viewer, because a server selector showing everyone the first player's ping is worse than
 * spending the allocation.
 */
public final class MenuItem {

    /** {@code slot: -1} means "every slot the layout has not already claimed". */
    public static final int FILL_SLOT = -1;

    private final ConfigurationSection section;
    private final List<Integer> slots;
    private final String permission;
    private final boolean hideWithoutPermission;

    private final List<String> anyClick;
    private final List<String> leftClick;
    private final List<String> rightClick;
    private final List<String> shiftLeftClick;
    private final List<String> shiftRightClick;
    private final List<String> middleClick;

    /** Set only when nothing in the item needs a player to render. */
    private final ItemStack staticStack;

    public MenuItem(ConfigurationSection section) {
        this.section = section;
        this.slots = ItemBuilder.slots(section);
        this.permission = section.getString("permission", "");
        this.hideWithoutPermission = section.getBoolean("hide_without_permission", true);

        this.anyClick = section.getStringList("actions");
        this.leftClick = section.getStringList("left_click_actions");
        this.rightClick = section.getStringList("right_click_actions");
        this.shiftLeftClick = section.getStringList("shift_left_click_actions");
        this.shiftRightClick = section.getStringList("shift_right_click_actions");
        this.middleClick = section.getStringList("middle_click_actions");

        this.staticStack = dynamic() ? null : ItemBuilder.fromConfig(section);
    }

    /** Whether the display text still needs a player before it means anything. */
    private boolean dynamic() {
        if (!C.papiEnabled()) return false;
        String name = section.getString("display_name", "");
        if (name.indexOf('%') >= 0) return true;
        for (String line : section.getStringList("lore")) {
            if (line.indexOf('%') >= 0) return true;
        }
        return false;
    }

    public List<Integer> slots() {
        return slots;
    }

    public boolean isFiller() {
        return slots.size() == 1 && slots.get(0) == FILL_SLOT;
    }

    /** Whether this viewer may see the item at all. */
    public boolean visibleTo(Player player) {
        if (permission.isEmpty() || player.hasPermission(permission)) return true;
        return !hideWithoutPermission;
    }

    /** The stack as this viewer should see it. */
    public ItemStack render(Player player) {
        if (staticStack != null) return staticStack.clone();

        ItemStack item = ItemBuilder.fromConfig(section);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String name = section.getString("display_name", "");
        if (!name.isEmpty()) {
            meta.displayName(C.item(C.replace(name, player, Map.of("%player%", player.getName()))));
        }

        List<String> lore = section.getStringList("lore");
        if (!lore.isEmpty()) {
            meta.lore(lore.stream()
                    .map(line -> C.item(C.replace(line, player, Map.of("%player%", player.getName()))))
                    .toList());
        }

        item.setItemMeta(meta);
        return item;
    }

    /**
     * The action list for a click. A specific list wins over the generic {@code actions:} one;
     * when only the generic list is set, every click type runs it.
     */
    public List<String> actionsFor(ClickType click) {
        List<String> specific = switch (click) {
            case LEFT -> leftClick;
            case RIGHT -> rightClick;
            case SHIFT_LEFT -> shiftLeftClick;
            case SHIFT_RIGHT -> shiftRightClick;
            case MIDDLE -> middleClick;
            default -> List.of();
        };
        return specific.isEmpty() ? anyClick : specific;
    }

    public boolean hasActions() {
        return !anyClick.isEmpty() || !leftClick.isEmpty() || !rightClick.isEmpty()
                || !shiftLeftClick.isEmpty() || !shiftRightClick.isEmpty() || !middleClick.isEmpty();
    }

    /** An empty section, for callers that need a placeholder item and have no config. */
    public static ConfigurationSection emptySection() {
        return new MemoryConfiguration();
    }
}
