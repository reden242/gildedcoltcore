package dev.rdbot.discord;

import dev.rdbot.Config;
import dev.rdbot.ai.AiResponse;
import dev.rdbot.ai.AiService;
import dev.rdbot.store.Daos;
import dev.rdbot.store.Database;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.awt.Color;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;

/**
 * Everything that happens when the AI gives up on a ticket:
 * the short message + role ping in the ticket, the log-channel line, the
 * escalation record, the "stop replying" lock and the knowledge-gap hand-off.
 */
public final class Escalator {

    private final Config config;
    private final Database db;
    private final GapEmbed gaps;

    public Escalator(Config config, Database db, GapEmbed gaps) {
        this.config = config;
        this.db = db;
        this.gaps = gaps;
    }

    public void escalate(MessageReceivedEvent event, AiService.Result result, String footer,
                         Map<String, Object> settings) {
        TextChannel ticket = (TextChannel) event.getChannel();
        var author = event.getAuthor();
        AiResponse response = result.response();

        String roleId = escalationRole(settings);
        StringBuilder message = new StringBuilder();
        if (!roleId.isBlank()) message.append("<@&").append(roleId).append(">\n");
        message.append(result.displayText()).append(footer);

        ticket.sendMessage(message.toString())
                .setAllowedMentions(EnumSet.of(Message.MentionType.ROLE))
                .setMessageReference(event.getMessageId())
                .queue();

        long escalationId = Daos.addEscalation(db, event.getGuild().getId(), result.insightId(),
                author.getId(), author.getName(), ticket.getId(), response.category(),
                response.reason(), response.summary(), response.confidence());

        Object after = settings.get("afterEscalation");
        String afterMode = after == null ? config.str("ai.after-escalation", "stop") : String.valueOf(after);
        if ("stop".equalsIgnoreCase(afterMode)) {
            Daos.lockChannel(db, ticket.getId(), author.getId(), "escalation #" + escalationId);
        }

        postLog(event, escalationId, result);

        if (AiResponse.CATEGORY_UNKNOWN.equals(response.category())) {
            gaps.post(ticket, result.insightId(), author, result.displayText(),
                    response.suggestedTopic(), response.answer(), ticket.getId());
        }
    }

    /** Out-of-hours: exactly one plain message, optionally with the role ping. */
    public void postOffline(TextChannel ticket, String offlineText, String roleId) {
        StringBuilder message = new StringBuilder();
        if (roleId != null && !roleId.isBlank()) message.append("<@&").append(roleId).append(">\n");
        message.append(offlineText);
        ticket.sendMessage(message.toString())
                .setAllowedMentions(roleId == null || roleId.isBlank()
                        ? EnumSet.noneOf(Message.MentionType.class)
                        : EnumSet.of(Message.MentionType.ROLE))
                .queue();
    }

    private void postLog(MessageReceivedEvent event, long escalationId, AiService.Result result) {
        var guild = event.getGuild();
        String configured = config.str("discord.log-channel-id", "");
        TextChannel log = configured.isBlank() ? null : guild.getTextChannelById(configured);
        if (log == null) return;
        AiResponse r = result.response();
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Escalation #" + escalationId)
                .setColor(Color.ORANGE)
                .setDescription("**" + event.getAuthor().getName() + "** in "
                        + ticketJump(event) + "\n\n" + truncate(r.summary(), 400))
                .addField("Category", r.category(), true)
                .addField("Confidence", String.format("%.1f%%", r.confidence() * 100), true)
                .addField("Insight", "#" + result.insightId(), true)
                .addField("Reason", truncate(r.reason(), 400), false)
                .setTimestamp(Instant.now());
        log.sendMessageEmbeds(embed.build()).queue();
    }

    private static String ticketJump(MessageReceivedEvent event) {
        return "https://discord.com/channels/" + event.getGuild().getId()
                + "/" + event.getChannel().getId() + "/" + event.getMessageId();
    }

    private String escalationRole(Map<String, Object> settings) {
        Object v = settings.get("escalationRoleId");
        if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        return config.str("discord.escalation-role-id", "");
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
