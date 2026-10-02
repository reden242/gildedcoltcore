package dev.rdbot;

import dev.rdbot.ai.AiService;
import dev.rdbot.ai.KeyPool;
import dev.rdbot.kb.DiscordSource;
import dev.rdbot.kb.DocumentSource;
import dev.rdbot.kb.KnowledgeStore;
import dev.rdbot.kb.WebSource;
import dev.rdbot.store.Database;
import dev.rdbot.web.TokenAuth;
import net.dv8tion.jda.api.JDA;

import java.util.function.Supplier;

/** Everything the panel API and the bot share. One object, ten getters. */
public record Services(Config config, Database db, AiService ai, KeyPool keys,
                       KnowledgeStore store, WebSource web, DiscordSource discord,
                       DocumentSource documents, TokenAuth tokens, Supplier<JDA> jda) {
}
