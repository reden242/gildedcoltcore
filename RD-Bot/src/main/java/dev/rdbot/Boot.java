package dev.rdbot;

import dev.rdbot.ai.AiService;
import dev.rdbot.ai.KeyPool;
import dev.rdbot.discord.Bot;
import dev.rdbot.kb.DiscordSource;
import dev.rdbot.kb.DocumentSource;
import dev.rdbot.kb.KnowledgeStore;
import dev.rdbot.kb.WebSource;
import dev.rdbot.store.Database;
import dev.rdbot.web.PanelServer;
import dev.rdbot.web.TokenAuth;
import net.dv8tion.jda.api.JDA;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The bot's boot sequence, extracted from the old {@code main} so a Bukkit
 * plugin and a standalone process can share exactly one startup path.
 *
 * <p>Everything here blocks — SQLite schema creation, the panel's HTTP bind and
 * especially {@link Bot#start()}, which waits on JDA's gateway handshake. A
 * Paper server must never run any of it on the main thread, so
 * {@link RDBotPlugin} calls {@link #start} from its own thread.
 *
 * <p>{@link #stop} is idempotent and safe to call before {@link #start} has
 * finished, because a plugin can be disabled while the boot thread is still
 * connecting.
 */
public final class Boot {

    private final Path dir;
    private final Consumer<String> log;

    private Database db;
    private Bot discord;
    private PanelServer panel;
    private ScheduledExecutorService scheduler;

    public Boot(Path dir, Consumer<String> log) {
        this.dir = dir;
        this.log = log;
    }

    /**
     * Config -> database -> AI + knowledge -> panel -> Discord. The panel
     * starts before Discord on purpose: a bad bot token must not take the
     * control surface down with it.
     *
     * @return the first-run panel token, or null when the panel is disabled or
     *         a token already existed
     */
    public String start() throws Exception {
        Path configFile = this.dir.resolve("config.yml");
        Config.copyDefaultsIfMissing(configFile);
        if (!java.nio.file.Files.exists(configFile)) {
            throw new IllegalStateException("config.yml not found. Copy config.example.yml to config.yml and fill it in.");
        }
        Config config = new Config(configFile);

        Database database = new Database(this.dir.resolve(config.str("paths.database", "rdbot.db")));
        this.db = database;

        KeyPool keys = new KeyPool(config.groqKeys(),
                config.intOf("ai.requests-per-minute", 30),
                config.intOf("ai.tokens-per-minute", 60000), database);
        KnowledgeStore store = new KnowledgeStore(database, config.intOf("knowledge.chunk-size", 700));
        AiService ai = new AiService(config, database, keys, store);
        TokenAuth tokens = new TokenAuth(
                this.dir.resolve(config.str("paths.panel-tokens", "panel-tokens.json")),
                config.intOf("panel.max-auth-failures", 8),
                config.intOf("panel.ban-seconds", 300));

        String firstToken = tokens.ensureInitial();
        AtomicReference<Bot> bot = new AtomicReference<>();
        Services services = new Services(config, database, ai, keys, store,
                new WebSource(database, store), new DiscordSource(database, store),
                new DocumentSource(database, store), tokens, () -> {
            Bot b = bot.get();
            return b == null ? null : b.jda();
        });

        if (config.bool("panel.enabled", true)) {
            PanelServer server = new PanelServer(services);
            server.start();
            this.panel = server;
            this.log.accept("[panel] listening on " + server.baseUrl());
        }
        if (firstToken != null) {
            this.log.accept("========================================================");
            this.log.accept("First run: panel token (shown ONCE):");
            this.log.accept("  " + panelBase(config) + "/?token=" + firstToken);
            this.log.accept("It is stored as a hash only. Rotate with /panel-token regenerate.");
            this.log.accept("========================================================");
        }

        Bot botInstance = new Bot(config, database, ai, keys, store, tokens, this.panel);
        bot.set(botInstance);
        this.discord = botInstance;
        botInstance.start();

        // Web source re-sync on a timer (0 = manual only).
        int resyncMinutes = config.intOf("knowledge.web-resync-minutes", 0);
        if (resyncMinutes > 0) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "web-resync");
                t.setDaemon(true);
                return t;
            });
            WebSource web = services.web();
            this.scheduler.scheduleAtFixedRate(() -> {
                try {
                    JDA jda = botInstance.jda();
                    if (jda == null) return;
                    for (var guild : jda.getGuilds()) web.resyncGuild(guild.getId());
                } catch (Exception e) {
                    this.log.accept("[web] resync failed: " + e.getMessage());
                }
            }, resyncMinutes, resyncMinutes, TimeUnit.MINUTES);
        }
        return firstToken;
    }

    /** Idempotent. Safe while {@link #start} is still running or has failed. */
    public void stop() {
        ScheduledExecutorService s = this.scheduler;
        this.scheduler = null;
        if (s != null) s.shutdownNow();
        Bot d = this.discord;
        this.discord = null;
        if (d != null) {
            try {
                d.shutdown();
            } catch (Exception ignored) {
            }
        }
        PanelServer p = this.panel;
        this.panel = null;
        if (p != null) p.stop();
        Database database = this.db;
        this.db = null;
        if (database != null) database.close();
    }

    public static String panelBase(Config config) {
        return "http://" + config.str("panel.bind", "0.0.0.0") + ":"
                + config.intOf("panel.port", 12022);
    }
}