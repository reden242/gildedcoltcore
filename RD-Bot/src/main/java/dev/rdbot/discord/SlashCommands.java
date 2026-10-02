package dev.rdbot.discord;

import dev.rdbot.Config;
import dev.rdbot.ai.AiService;
import dev.rdbot.ai.KeyPool;
import dev.rdbot.kb.KnowledgeStore;
import dev.rdbot.store.Daos;
import dev.rdbot.store.Database;
import dev.rdbot.web.TokenAuth;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.Map;

/**
 * Slash commands: /kb, /rdbot, /panel-token.
 *
 * <p>Staff gating uses the same {@link TicketDetector} check as the message
 * flow, so panel-configured staff roles apply here too.
 */
public final class SlashCommands extends ListenerAdapter {

    private final Config config;
    private final Database db;
    private final AiService ai;
    private final KeyPool keys;
    private final KnowledgeStore store;
    private final TokenAuth tokens;
    private final String panelBaseUrl;

    public SlashCommands(Config config, Database db, AiService ai, KeyPool keys,
                         KnowledgeStore store, TokenAuth tokens, String panelBaseUrl) {
        this.config = config;
        this.db = db;
        this.ai = ai;
        this.keys = keys;
        this.store = store;
        this.tokens = tokens;
        this.panelBaseUrl = panelBaseUrl;
    }

    public static java.util.List<net.dv8tion.jda.api.interactions.commands.build.SlashCommandData> definitions() {
        return java.util.List.of(
                Commands.slash("kb", "Manage the knowledge base")
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
                        .addSubcommands(
                                new SubcommandData("add", "Add a knowledge entry")
                                        .addOption(OptionType.STRING, "topic", "Topic, e.g. Billing", true)
                                        .addOption(OptionType.STRING, "information", "The factual text", true),
                                new SubcommandData("list", "List knowledge entries"),
                                new SubcommandData("remove", "Remove a knowledge entry")
                                        .addOption(OptionType.INTEGER, "id", "Entry id", true),
                                new SubcommandData("reload", "Reload prompt.md + knowledge sources")),
                Commands.slash("rd-bot", "Pause, resume or check the bot")
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
                        .addSubcommands(
                                new SubcommandData("pause", "Stop replying in this guild"),
                                new SubcommandData("resume", "Resume replying in this guild"),
                                new SubcommandData("status", "Key health, latency, queue size")),
                Commands.slash("panel-token", "Rotate the web panel token")
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
                        .addSubcommands(new SubcommandData("regenerate", "Create a new panel token")),
                Commands.slash("panel", "Get the web panel link in DMs")
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
                        .addSubcommands(new SubcommandData("link", "DM yourself a panel login link")));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String command = event.getName();
        String sub = event.getSubcommandName() == null ? "" : event.getSubcommandName();
        switch (command) {
            case "kb" -> handleKb(event, sub);
            case "rd-bot" -> handleRDBot(event, sub);
            case "panel-token" -> handlePanelToken(event, sub);
            case "panel" -> handlePanel(event, sub);
            default -> event.reply("Unknown command.").setEphemeral(true).queue();
        }
    }

    /* ---------------- /kb ---------------- */

    private void handleKb(SlashCommandInteractionEvent event, String sub) {
        if (!staff(event)) {
            event.reply("Staff only.").setEphemeral(true).queue();
            return;
        }
        String guildId = event.getGuild() == null ? "" : event.getGuild().getId();
        try {
            switch (sub) {
                case "add" -> {
                    String topic = option(event, "topic");
                    String info = option(event, "information");
                    int limit = config.intOf("knowledge.limits.custom", 20);
                    if (Daos.knowledgeCount(db, guildId, "custom") >= limit) {
                        event.reply("Custom knowledge is full (" + limit + "). Remove one first.")
                                .setEphemeral(true).queue();
                        return;
                    }
                    long id = store.addCustom(guildId, topic.substring(0, Math.min(200, topic.length())),
                            info.substring(0, Math.min(5000, info.length())));
                    event.reply("Added knowledge item #" + id + ".").setEphemeral(true).queue();
                }
                case "list" -> {
                    var entries = Daos.knowledge(db, guildId, "custom");
                    if (entries.isEmpty()) {
                        event.reply("No custom knowledge yet.").setEphemeral(true).queue();
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (var e : entries) {
                        sb.append('#').append(e.id()).append(" **").append(e.topic()).append("**\n");
                        if (sb.length() > 1800) break;
                    }
                    event.reply(sb.toString()).setEphemeral(true).queue();
                }
                case "remove" -> {
                    long id = event.getOption("id", 0L, OptionMapping::getAsLong);
                    store.remove(id);
                    event.reply("Removed #" + id + ".").setEphemeral(true).queue();
                }
                case "reload" -> {
                    // prompt.md is read per request; custom chunks reindex here.
                    for (var e : Daos.knowledge(db, guildId, "custom")) {
                        store.reindex(guildId, e.id(), e.topic(), e.content());
                    }
                    event.reply("Reloaded prompt.md and " + "reindexed custom knowledge.")
                            .setEphemeral(true).queue();
                }
                default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
            }
        } catch (Exception e) {
            event.reply("Failed: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    /* ---------------- /rd-bot ---------------- */

    private void handleRDBot(SlashCommandInteractionEvent event, String sub) {
        if (!staff(event)) {
            event.reply("Staff only.").setEphemeral(true).queue();
            return;
        }
        String guildId = event.getGuild() == null ? "" : event.getGuild().getId();
        switch (sub) {
            case "pause" -> {
                setPaused(guildId, true);
                event.reply("RD-Bot paused in this guild.").setEphemeral(true).queue();
            }
            case "resume" -> {
                setPaused(guildId, false);
                event.reply("RD-Bot resumed in this guild.").setEphemeral(true).queue();
            }
            case "status" -> {
                long cooling = keys.all().stream().filter(k -> k.isCooling()).count();
                event.reply("RD-Bot: " + keys.size() + " key(s), " + cooling + " cooling. "
                        + "Gateway ping: " + event.getJDA().getGatewayPing() + "ms.")
                        .setEphemeral(true).queue();
            }
            default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    /* ---------------- /panel-token ---------------- */

    private void handlePanelToken(SlashCommandInteractionEvent event, String sub) {
        if (!"regenerate".equals(sub)) {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
            return;
        }
        String ownerId = config.str("discord.owner-id", "");
        if (ownerId.isBlank() || event.getUser() == null
                || !event.getUser().getId().equals(ownerId)) {
            event.reply("Owner only. Set discord.owner-id first.").setEphemeral(true).queue();
            return;
        }
        String token = tokens.rotate();
        event.reply("New panel link (old cookies are now invalid):\n" + panelBaseUrl + "/?token=" + token)
                .setEphemeral(true).queue();
    }

    private boolean staff(SlashCommandInteractionEvent event) {
        if (event.getUser() != null && isAdmin(event.getUser().getId())) return true;
        if (event.getGuild() == null || event.getMember() == null) return false;
        return TicketDetector.isStaff(event.getMember(), config,
                ai.guildSettings(event.getGuild().getId()));
    }

    /** Owner + panel admin ids: full commands, no staff role needed. */
    private boolean isAdmin(String userId) {
        if (userId == null) return false;
        if (userId.equals(config.str("discord.owner-id", ""))) return true;
        return config.strings("panel.admin-user-ids").contains(userId);
    }

    /* ---------------- /panel ---------------- */

    private void handlePanel(SlashCommandInteractionEvent event, String sub) {
        if (!"link".equals(sub)) {
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
            return;
        }
        if (event.getUser() == null || !isAdmin(event.getUser().getId())) {
            event.reply("Panel admins only.").setEphemeral(true).queue();
            return;
        }
        String base = config.str("panel.public-base-url", "").strip();
        if (base.isEmpty()) base = panelBaseUrl;
        if (base.isBlank()) {
            event.reply("Panel is disabled or has no URL configured.").setEphemeral(true).queue();
            return;
        }
        String token = tokens.issue();
        String link = base.replaceAll("/+$", "") + "/?token=" + token;
        event.getUser().openPrivateChannel().queue(
                dm -> dm.sendMessage("RD-Bot panel login (keep it secret, it is a full-access key):\n" + link)
                        .queue(
                                sent -> event.reply("Sent you the panel link in DMs.").setEphemeral(true).queue(),
                                failed -> event.reply("Could not DM you — enable DMs from server members first.")
                                        .setEphemeral(true).queue()),
                failed -> event.reply("Could not open DMs — enable DMs from server members first.")
                        .setEphemeral(true).queue());
    }

    private void setPaused(String guildId, boolean paused) {
        Map<String, Object> settings =
                new java.util.LinkedHashMap<>(ai.guildSettings(guildId));
        settings.put("paused", paused);
        Daos.saveSettings(db, guildId, settings);
    }

    private static String option(SlashCommandInteractionEvent event, String name) {
        OptionMapping mapping = event.getOption(name);
        return mapping == null ? "" : mapping.getAsString();
    }
}


