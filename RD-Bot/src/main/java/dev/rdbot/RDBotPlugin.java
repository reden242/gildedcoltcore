package dev.rdbot;

import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

/**
 * Paper/Bukkit entry point for the RD-Bot.
 *
 * <p>The bot's own boot path is unchanged — it is the same {@link Boot} used by
 * the standalone {@code java -jar} form — it just runs inside a plugin
 * lifecycle and keeps its files in this plugin's data folder.
 *
 * <h2>Why the boot runs off the main thread</h2>
 * {@link Boot#start()} blocks: it creates the SQLite schema, binds the panel's
 * HTTP socket and waits on JDA's gateway handshake. {@code onEnable} runs on
 * the server's main thread, and a blocking call there freezes the whole server
 * for the length of the Discord connection. So the sequence is handed to a
 * single named thread and {@link #onDisable} waits briefly for it.
 *
 * <h2>Data folder</h2>
 * Everything lands in {@code plugins/RDBot/}: {@code config.yml},
 * {@code rdbot.db}, {@code panel-tokens.json}. {@code config.example.yml} is
 * written out on first start, so the panel token is only ever printed once and
 * only from this plugin's own log.
 */
public final class RDBotPlugin extends JavaPlugin {

    private static final String THREAD = "RD-Boot";
    /** Long enough for the gateway handshake on a healthy network. */
    private static final long SHUTDOWN_GRACE_MILLIS = 5_000L;

    private volatile Boot boot;
    private volatile Thread bootThread;
    private volatile boolean stopping;

    @Override
    public void onEnable() {
        Path dir = getDataFolder().toPath();
        if (!java.nio.file.Files.exists(dir) && !dir.toFile().mkdirs()) {
            getLogger().severe("Could not create the data folder at " + dir.toAbsolutePath()
                    + "; the bot will stay disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!getConfig().contains("discord")) {
            // Save the packaged defaults (config.example.yml) if it has one.
            saveResourceIfPresent();
        }

        this.boot = new Boot(dir, msg -> getLogger().info(msg));
        this.stopping = false;
        Thread t = new Thread(() -> {
            try {
                this.boot.start();
            } catch (Throwable e) {
                if (!this.stopping) {
                    getLogger().severe("Boot failed: " + e);
                    e.printStackTrace();
                }
            }
        }, THREAD);
        // Daemon so a stuck gateway handshake can never hold the JVM open.
        t.setDaemon(true);
        this.bootThread = t;
        t.start();
        getLogger().info("Booting on thread " + THREAD + "; data folder " + dir.toAbsolutePath());
    }

    @Override
    public void onDisable() {
        this.stopping = true;
        Thread t = this.bootThread;
        if (t != null && t.isAlive()) {
            // Give the boot a moment to reach a stoppable state rather than
            // interrupting mid-handshake and leaving the panel socket bound.
            t.interrupt();
            try {
                t.join(SHUTDOWN_GRACE_MILLIS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Boot b = this.boot;
        if (b != null) b.stop();
        getLogger().info("Stopped.");
    }

    /** Older configs are read straight off disk, so keep the two in step. */
    private void saveResourceIfPresent() {
        try {
            saveResource("config.example.yml", false);
            saveDefaultConfig();
        } catch (IllegalArgumentException ignored) {
            // No packaged default; a hand-written config.yml is expected.
        }
    }
}