package dev.rdbot.discord;

import dev.rdbot.Config;
import dev.rdbot.ai.AiService;
import dev.rdbot.ai.KeyPool;
import dev.rdbot.kb.KnowledgeStore;
import dev.rdbot.store.Database;
import dev.rdbot.web.PanelServer;
import dev.rdbot.web.TokenAuth;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;

/**
 * Wires everything Discord-shaped together.
 */
public final class Bot {

    private final Config config;
    private final Database db;
    private final AiService ai;
    private final KeyPool keys;
    private final KnowledgeStore store;
    private final TokenAuth tokens;
    private final PanelServer panel;
    private JDA jda;

    public Bot(Config config, Database db, AiService ai, KeyPool keys, KnowledgeStore store,
               TokenAuth tokens, PanelServer panel) {
        this.config = config;
        this.db = db;
        this.ai = ai;
        this.keys = keys;
        this.store = store;
        this.tokens = tokens;
        this.panel = panel;
    }

    public void start() throws Exception {
        String token = config.str("discord.token", "");
        if (token.isBlank()) throw new IllegalStateException("discord.token is empty (or RDBOT_BOT_TOKEN).");
        GapEmbed gaps = new GapEmbed(config, db, store);
        Escalator escalator = new Escalator(config, db, gaps);
        MessageListener messages = new MessageListener(config, db, ai, escalator);
        String panelBase = panel == null ? "" : panel.baseUrl();
        SlashCommands slash = new SlashCommands(config, db, ai, keys, store, tokens, panelBase);

        jda = JDABuilder.createDefault(token,
                        GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(messages, gaps, slash)
                .build()
                .awaitReady();
        jda.updateCommands().addCommands(SlashCommands.definitions()).queue();
        System.out.println("[bot] RD-Bot online in " + jda.getGuilds().size() + " guild(s).");
    }

    public JDA jda() { return jda; }

    public void shutdown() {
        if (jda != null) jda.shutdown();
    }
}
