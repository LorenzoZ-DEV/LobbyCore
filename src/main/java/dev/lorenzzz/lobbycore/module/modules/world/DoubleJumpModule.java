package dev.lorenzzz.lobbycore.module.modules.world;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lorenzzz.lobbycore.LobbyCore;
import dev.lorenzzz.lobbycore.module.AbstractModule;
import dev.lorenzzz.lobbycore.C;
import dev.lorenzzz.lobbycore.util.Sounds;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The lobby double jump: flight is armed while a player stands on the ground, and the moment
 * they try to use it the event is cancelled and turned into a launch.
 *
 * <p>Si puo risaltare per aria quante volte si vuole: spammare il tasto e una salita. Il
 * riarmo e pero differito di qualche tick, e quel ritardo e la parte che conta. Annullare
 * l'evento non basta a togliere il volo al client — se {@code allowFlight} resta acceso, il
 * gioco lo lascia in modalita volo e il giocatore galleggia senza riuscire a scendere. Per
 * questo ogni spinta spegne il volo e lo riaccende due tick dopo: in mezzo c'e sempre un
 * istante disarmato che riporta il client coi piedi per aria, per cosi dire. Spammando non
 * si nota, ma e la differenza fra un doppio salto e un blocco in volo.
 *
 * <p>Il tetto di salti in aria e {@code max_air_jumps}, zero per nessun tetto. Il conteggio
 * si ricarica atterrando, e per sapere quando si e atterrati davvero servono due fasi: nei
 * primi tick dopo la spinta il giocatore e ancora dentro i cinque centesimi di blocco sopra
 * il pavimento, quindi "sono a terra" vorrebbe dire "non sono ancora partito".
 *
 * <p>Ogni salto in catena sale di un grado di scala maggiore — do re mi fa sol la si do — e
 * scoppia un fuoco d'artificio sul posto. La scala si ferma al do alto perche li finisce
 * davvero: Minecraft accetta pitch fra 0.5 e 2.0, cioe un'ottava sotto e una sopra, e do-do
 * quell'ottava la occupa tutta. Dal nono salto in poi la nota resta quella, non per scelta di
 * design ma perche piu su il gioco non va.
 *
 * <p>Il contatore e una combo, non una statistica: {@code combo.reset_seconds} senza saltare e
 * si riparte da do. Scade da solo perche e una Caffeine con {@code expireAfterWrite} — il
 * "sono passati dieci secondi" non ha bisogno di nessun task che lo controlli.
 */
public final class DoubleJumpModule extends AbstractModule implements Listener {

    private static final String PERMISSION = "lobbycore.doublejump";

    /**
     * I gradi di una scala maggiore in semitoni: do re mi fa sol la si do.
     *
     * <p>Il pitch di Minecraft e un moltiplicatore, non un numero di nota: {@code 2^(n/12)}
     * alza di n semitoni. Con il pitch base a 1.0 l'ultimo grado cade esatto su 2.0, il
     * massimo che il gioco accetta — la scala ci sta dentro per un pelo.
     */
    private static final int[] MAJOR_SCALE = { 0, 2, 4, 5, 7, 9, 11, 12 };

    /** Usato quando la lista dei colori nel file e vuota o tutta illeggibile. */
    private static final Color DEFAULT_COLOR = Color.fromRGB(0x98, 0x63, 0xE7);

    private double power;
    private double verticalPower;
    private String sound;
    private float volume;
    private float pitch;
    private Particle particle;
    private int particleCount;
    private boolean requirePermission;

    private int maxAirJumps;
    private long rearmDelayTicks;

    private boolean comboEnabled;
    private int comboMax;

    private boolean airburstEnabled;
    private FireworkEffect.Type airburstShape;
    private boolean airburstFlicker;
    private boolean airburstTrail;
    private List<Color> airburstColors = List.of();

    /** Marchia i nostri fuochi d'artificio, per poterne annullare il danno. */
    private NamespacedKey key;

    /**
     * Chi e per aria dopo una spinta, e in che fase: {@code FALSE} = partito ma ancora
     * appoggiato al blocco da cui si e staccato, {@code TRUE} = per aria davvero.
     *
     * <p>Le due fasi servono a sapere quando e atterrato, che e il momento in cui il conteggio
     * dei salti in aria si ricarica. Nei primi tick dopo la spinta il giocatore e ancora
     * dentro i cinque centesimi di blocco sopra il pavimento: senza la fase, quella lettura
     * verrebbe scambiata per un atterraggio e il tetto di salti non varrebbe niente.
     */
    private final Cache<UUID, Boolean> airborne = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    /** Spinte fatte da quando si e staccato da terra, per il tetto {@code max_air_jumps}. */
    private final Cache<UUID, Integer> airJumps = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    /**
     * Il grado di combo raggiunto da ogni giocatore.
     *
     * <p>Costruita in {@link #onEnable()} e non qui: la scadenza e {@code reset_seconds} del
     * file, e {@code expireAfterWrite} riparte ad ogni salto. Il reset "dopo un po' che non
     * salti" e quindi gratis, senza nessun task che controlla gli orologi.
     */
    private Cache<UUID, Integer> combo;

    /** Last launch per player, to stop a landing from immediately becoming another launch. */
    private final Cache<UUID, Long> cooldown = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    /**
     * Launches that have not landed yet. Cancelling the landing damage here instead of in
     * world-settings is deliberate: it applies to everyone, including staff with the world
     * bypass (who otherwise eat the landing of every double jump they test).
     */
    private final Cache<UUID, Long> noFall = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    private long cooldownMillis;
    private long noFallMillis;

    public DoubleJumpModule(LobbyCore plugin) {
        super(plugin);
    }

    @Override public String id() { return "double-jump"; }
    @Override public String configFile() { return "double_jump"; }

    @Override
    public String description() {
        return "Doppio salto nella lobby";
    }

    @Override
    public void onEnable() {
        var config = config();
        power = config.getDouble("double_jump.power", 1.6);
        verticalPower = config.getDouble("double_jump.vertical_power", 0.75);
        sound = config.getString("double_jump.sound.name", "ENTITY_FIREWORK_ROCKET_BLAST");
        volume = (float) config.getDouble("double_jump.sound.volume", 1.0);
        pitch = (float) config.getDouble("double_jump.sound.pitch", 1.0);
        particleCount = config.getInt("double_jump.particle.count", 20);
        requirePermission = config.getBoolean("double_jump.require_permission", false);
        cooldownMillis = config.getLong("double_jump.cooldown_millis", 100L);
        maxAirJumps = Math.max(0, config.getInt("double_jump.max_air_jumps", 0));
        rearmDelayTicks = Math.max(1L, config.getLong("double_jump.rearm_delay_ticks", 2L));
        noFallMillis = Math.max(0L, config.getLong("double_jump.landing_protection_seconds", 10L)) * 1000L;

        particle = parseParticle(config.getString("double_jump.particle.type", "CLOUD"));

        key = new NamespacedKey(plugin, "double_jump_airburst");

        comboEnabled = config.getBoolean("double_jump.combo.enabled", true);
        comboMax = Math.max(1, config.getInt("double_jump.combo.max", 60));
        long comboResetSeconds = Math.max(1L, config.getLong("double_jump.combo.reset_seconds", 10L));
        combo = Caffeine.newBuilder()
                .expireAfterWrite(comboResetSeconds, TimeUnit.SECONDS)
                .build();

        airburstEnabled = config.getBoolean("double_jump.airburst.enabled", true);
        airburstShape = parseShape(config.getString("double_jump.airburst.shape", "BALL"));
        airburstFlicker = config.getBoolean("double_jump.airburst.flicker", true);
        airburstTrail = config.getBoolean("double_jump.airburst.trail", true);
        airburstColors = parseColors(config.getStringList("double_jump.airburst.colors"));

        listen(this);
        for (Player player : plugin.getServer().getOnlinePlayers()) arm(player);
    }

    @Override
    protected void shutdown() {
        // Leaving flight armed would let everyone fly the moment the module stops.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
                player.setAllowFlight(false);
            }
        }
        airborne.invalidateAll();
        airJumps.invalidateAll();
        cooldown.invalidateAll();
        noFall.invalidateAll();
        if (combo != null) combo.invalidateAll();
    }

    private static Particle parseParticle(String name) {
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Particle.CLOUD;
        }
    }

    private static FireworkEffect.Type parseShape(String name) {
        try {
            return FireworkEffect.Type.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            C.warning("Forma airburst '" + name + "' sconosciuta — uso BALL. "
                    + "Ammesse: BALL, BALL_LARGE, STAR, BURST, CREEPER.");
            return FireworkEffect.Type.BALL;
        }
    }

    /** {@code #RRGGBB} (o {@code RRGGBB}) -> colore. Le voci illeggibili vengono saltate. */
    private static List<Color> parseColors(List<String> raw) {
        List<Color> colors = new ArrayList<>(raw.size());
        for (String value : raw) {
            String hex = value == null ? "" : value.trim();
            if (hex.startsWith("#")) hex = hex.substring(1);
            try {
                colors.add(Color.fromRGB(Integer.parseInt(hex, 16)));
                // NumberFormatException se non e esadecimale, IllegalArgumentException se e
                // fuori dai 24 bit: IllegalArgumentException le copre entrambe.
            } catch (IllegalArgumentException e) {
                C.warning("Colore airburst '" + value + "' non valido — serve #RRGGBB.");
            }
        }
        return List.copyOf(colors);
    }

    private boolean eligible(Player player) {
        if (!inLobbyWorld(player.getWorld())) return false;
        if (requirePermission && !player.hasPermission(PERMISSION)) return false;
        // Creative and spectator players have real flight; arming would take it away from them.
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    private void arm(Player player) {
        if (eligible(player)) player.setAllowFlight(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        arm(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        airborne.invalidate(uuid);
        airJumps.invalidate(uuid);
        cooldown.invalidate(uuid);
        noFall.invalidate(uuid);
        combo.invalidate(uuid);
    }

    /**
     * Eats the landing of a double jump. Consumed on use: a later, unrelated fall still
     * hurts (unless world-settings already covers the player).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!inLobbyWorld(player.getWorld())) return;

        Long launched = noFall.getIfPresent(player.getUniqueId());
        if (launched == null) return;
        if (System.currentTimeMillis() - launched > noFallMillis) {
            noFall.invalidate(player.getUniqueId());
            return;
        }
        noFall.invalidate(player.getUniqueId());
        event.setCancelled(true);
    }

    @EventHandler
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!event.isFlying() || !eligible(player)) return;

        event.setCancelled(true);

        // Il cooldown si controlla PRIMA di disarmare il volo. Disarmarlo e poi uscire
        // lascerebbe il giocatore senza volo e fuori da `spent`: onMove non lo riarmerebbe
        // mai piu e il doppio salto sparirebbe fino al relog. Chi atterra e risalta subito
        // resta quindi armato, e il salto gli riesce appena il cooldown e passato.
        Long last = cooldown.getIfPresent(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (last != null && now - last < cooldownMillis) return;

        UUID uuid = player.getUniqueId();
        boolean fromGround = standingOnGround(player);
        int used = fromGround ? 0 : airJumps.get(uuid, ignored -> 0);

        // Tetto raggiunto: niente spinta e niente riarmo, si riparte dopo l'atterraggio.
        if (maxAirJumps > 0 && used >= maxAirJumps) {
            player.setAllowFlight(false);
            player.setFlying(false);
            return;
        }

        // Spegnere il volo e obbligatorio, non una pulizia: se allowFlight resta acceso il
        // client resta in modalita volo e il giocatore galleggia senza poter scendere.
        player.setAllowFlight(false);
        player.setFlying(false);

        cooldown.put(uuid, now);
        airJumps.put(uuid, used + 1);
        // FALSE solo se la spinta parte da terra: a mezz'aria e gia staccato per definizione.
        airborne.put(uuid, !fromGround);
        if (noFallMillis > 0) noFall.put(uuid, now);

        Vector launch = player.getLocation().getDirection().multiply(power).setY(verticalPower);
        player.setVelocity(launch);

        int step = bumpCombo(player);
        // Il pitch del file resta il tono di base: alzarlo o abbassarlo traspone tutta la scala,
        // invece di venire ignorato appena la combo e accesa.
        Sounds.play(player, sound, volume, clampPitch(pitch * scalePitch(step)));
        airburst(player, step);

        if (particleCount > 0) {
            player.getWorld().spawnParticle(particle, player.getLocation(), particleCount,
                    0.3, 0.1, 0.3, 0.02);
        }

        // Riarmo differito: i due tick disarmati qui sopra sono quello che riporta il client
        // fuori dalla modalita volo. Rimetterlo subito lo lascerebbe li appeso.
        later(() -> rearmInAir(player), rearmDelayTicks);
    }

    /**
     * Rimette il salto a disposizione dopo una spinta, anche per aria: e questo a rendere lo
     * spam una salita. Si ferma solo se nel frattempo il giocatore e uscito, ha cambiato mondo
     * o ha esaurito il tetto di salti.
     */
    private void rearmInAir(Player player) {
        if (!player.isOnline() || !eligible(player)) return;
        if (maxAirJumps > 0 && airJumps.get(player.getUniqueId(), ignored -> 0) >= maxAirJumps) return;
        player.setAllowFlight(true);
    }

    // ------------------------------------------------------------- combo e nota

    /**
     * Avanza la combo di un grado e restituisce quello nuovo.
     *
     * <p>Voce assente vuol dire che la combo e scaduta da sola — nessun salto per
     * {@code reset_seconds} — e si riparte da uno. Arrivati a {@code combo.max} si ricomincia
     * da capo: quello che conta qui e la catena, non il totale, e oltre l'ottavo salto la nota
     * non cambia piu comunque.
     */
    private int bumpCombo(Player player) {
        if (!comboEnabled) return 1;
        UUID uuid = player.getUniqueId();
        Integer previous = combo.getIfPresent(uuid);
        int next = previous == null || previous >= comboMax ? 1 : previous + 1;
        combo.put(uuid, next);
        return next;
    }

    /**
     * Il moltiplicatore di pitch del grado di combo: do re mi fa sol la si do sui primi otto
     * salti, poi fermo sul do alto.
     *
     * <p>Fermo non per scelta: il do dell'ottava e gia pitch 2.0, il massimo che Minecraft
     * accetta. Sopra non c'e nessuna nota da suonare.
     */
    private static float scalePitch(int step) {
        int degree = Math.min(Math.max(step, 1), MAJOR_SCALE.length) - 1;
        return (float) Math.pow(2.0, MAJOR_SCALE[degree] / 12.0);
    }

    /** Fuori da 0.5-2.0 il client taglia da solo: meglio fermarsi al bordo consapevolmente. */
    private static float clampPitch(float value) {
        return Math.max(0.5f, Math.min(2.0f, value));
    }

    /** Un colore per grado di scala; l'ultimo si ripete, esattamente come fa la nota. */
    private Color colorFor(int step) {
        if (airburstColors.isEmpty()) return DEFAULT_COLOR;
        int degree = Math.min(Math.max(step, 1), MAJOR_SCALE.length) - 1;
        return airburstColors.get(Math.min(degree, airburstColors.size() - 1));
    }

    /**
     * Il fuoco d'artificio che scoppia sul posto.
     *
     * <p>E un razzo vero fatto detonare nello stesso tick in cui nasce: e questo a renderlo un
     * airburst invece che un razzo che parte per aria e scoppia un secondo dopo da tutt'altra
     * parte. L'entita e silenziosa perche il botto lo suoniamo noi, intonato sul grado della
     * scala — lasciarla parlare darebbe due esplosioni sovrapposte, la seconda stonata.
     */
    private void airburst(Player player, int step) {
        if (!airburstEnabled) return;
        Location at = player.getLocation();
        if (at.getWorld() == null) return;

        try {
            Firework firework = at.getWorld().spawn(at, Firework.class);
            firework.setSilent(true);
            firework.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);

            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(airburstShape)
                    .withColor(colorFor(step))
                    .flicker(airburstFlicker)
                    .trail(airburstTrail)
                    .build());
            // Zero stadi di razzo: senza questo il gioco gli darebbe comunque una salita.
            meta.setPower(0);
            firework.setFireworkMeta(meta);

            firework.detonate();
        } catch (Throwable failure) {
            // Un plugin di protezione che vieta lo spawn di entita farebbe tirare qui ad ogni
            // salto. Un avviso solo e l'airburst si spegne: il doppio salto continua a
            // funzionare, che e la parte che conta.
            airburstEnabled = false;
            C.warning("Airburst non spawnabile in questo mondo ("
                    + failure.getClass().getSimpleName() + ") — resta spento, il doppio salto no.");
        }
    }

    /**
     * Il fuoco d'artificio non deve fare male a nessuno.
     *
     * <p>Un razzo con effetti che scoppia danneggia chi sta entro cinque blocchi, cioe sempre
     * chi ha appena saltato. Contare sul god mode di world-settings vorrebbe dire rompersi
     * appena un admin lo spegne, quindi il danno lo annulliamo qui, sul nostro tag.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAirburstDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework
                && firework.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        // Il volo veniva armato solo al join: chi entrava in lobby da un altro mondo restava
        // senza doppio salto fino al login dopo, e chi usciva se lo portava dietro.
        combo.invalidate(player.getUniqueId());
        airborne.invalidate(player.getUniqueId());
        airJumps.invalidate(player.getUniqueId());
        if (eligible(player)) {
            arm(player);
        } else if (player.getGameMode() == GameMode.SURVIVAL
                || player.getGameMode() == GameMode.ADVENTURE) {
            player.setAllowFlight(false);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        // Cheapest possible early exit: nobody is mid-jump most of the time, and this handler
        // runs several times a second for every player in the lobby.
        Boolean offGround = airborne.getIfPresent(player.getUniqueId());
        if (offGround == null) {
            recover(player);
            return;
        }

        if (!offGround) {
            // Prima tappa: aspettare che si stacchi davvero dal pavimento. Finche e ancora
            // appoggiato, "sono a terra" non vuol dire "sono atterrato" — vuol dire che non
            // e ancora partito.
            if (!standingOnGround(player)) airborne.put(player.getUniqueId(), Boolean.TRUE);
            return;
        }

        // Seconda tappa: e stato in aria e ora tocca terra. Il conteggio dei salti riparte.
        if (!standingOnGround(player)) return;
        airborne.invalidate(player.getUniqueId());
        airJumps.invalidate(player.getUniqueId());
        arm(player);
    }

    /**
     * Rete di sicurezza per chi e rimasto senza volo armato senza passare dall'atterraggio: un
     * teleport (il soccorso dal vuoto di world-settings), un atterraggio in acqua, la voce che
     * scade dopo trenta secondi in aria, o un riarmo differito finito in un mondo sbagliato.
     * Senza questa, quel giocatore resta senza doppio salto fino al relog.
     *
     * <p>Il controllo costoso e l'ultimo: chi ha il volo gia armato — cioe quasi tutti, quasi
     * sempre — esce alla prima condizione senza toccare nessun blocco.
     */
    private void recover(Player player) {
        if (player.getAllowFlight() || !eligible(player)) return;
        if (standingOnGround(player)) arm(player);
    }

    /**
     * Whether the player has landed.
     *
     * <p>Not {@code Player#isOnGround()}: that value is whatever the client last claimed, which
     * a modified client is free to lie about — and lying about it here would mean infinite
     * double jumps. Reading the block under their feet costs one chunk lookup, and only for the
     * handful of players actually mid-jump.
     */
    private static boolean standingOnGround(Player player) {
        return player.getLocation().subtract(0, 0.05, 0).getBlock().getType().isSolid();
    }
}
