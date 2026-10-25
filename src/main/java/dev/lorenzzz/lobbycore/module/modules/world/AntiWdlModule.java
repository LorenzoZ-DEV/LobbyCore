package dev.lorenzzz.lobbycore.module.modules.world;

import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.List;
import java.util.Map;

/**
 * Turns World Downloader away.
 *
 * <p>The mod announces itself on the {@code wdl:init} plugin channel and then asks the server
 * what it is allowed to do on {@code wdl:control}. Answering with everything switched off is the
 * supported way to refuse — it is a protocol the mod respects, not a block it has to be tricked
 * into. There is no reliable way to stop a determined client from copying chunks it has been
 * sent, and this module does not pretend otherwise; it stops the one-click case, which is what
 * it is for.
 */
public final class AntiWdlModule extends AbstractModule implements Listener, PluginMessageListener {

    private static final String INIT_CHANNEL = "wdl:init";
    private static final String CONTROL_CHANNEL = "wdl:control";

    private static final String BYPASS = "lobbycore.bypass.wdl";

    private boolean kick;
    private List<String> actions;

    public AntiWdlModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "anti-wdl"; }
    @Override public String configFile() { return "anti_wdl"; }

    @Override
    public String description() {
        return "Blocca World Downloader";
    }

    @Override
    public void onEnable() {
        kick = config().getBoolean("anti_wdl.kick", false);
        actions = config().getStringList("anti_wdl.actions");

        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CONTROL_CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, INIT_CHANNEL, this);
        listen(this);
    }

    @Override
    protected void shutdown() {
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, INIT_CHANNEL, this);
        messenger.unregisterOutgoingPluginChannel(plugin, CONTROL_CHANNEL);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // The control packet is also sent unprompted on join: a client that has WDL but never
        // announces itself still gets told no before it can ask.
        if (!event.getPlayer().hasPermission(BYPASS)) sendDenial(event.getPlayer());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!INIT_CHANNEL.equals(channel)) return;
        if (player.hasPermission(BYPASS)) return;

        sendDenial(player);

        if (!actions.isEmpty()) managers.actions().execute(player, actions);

        if (kick) {
            player.kick(C.mm(managers.messages().raw("wdl-kick")));
            return;
        }
        if (managers.config().debug()) {
            C.info("World Downloader rilevato su &f" + player.getName() + "&7 — rifiutato.");
        }
    }

    /**
     * The WDL control payload: a flag byte set to "not permitted" followed by the four range
     * limits, all zero. The mod reads exactly this many bytes, so the shape matters more than
     * the values.
     */
    private void sendDenial(Player player) {
        byte[] payload = new byte[] {
                0,                       // globalIsEnabled = false
                0, 0, 0, 0,              // saveRadius = 0
                0,                       // canCacheChunks = false
                0,                       // canSaveEntities = false
                0,                       // canSaveTileEntities = false
                0                        // canSaveContainers = false
        };
        player.sendPluginMessage(plugin, CONTROL_CHANNEL, payload);

        String warning = managers.messages().raw("wdl-warning");
        if (!warning.isEmpty()) {
            player.sendMessage(C.parse(warning, player, Map.of("%player%", player.getName())));
        }
    }
}
