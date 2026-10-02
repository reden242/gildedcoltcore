package dev.rdbot.discord;

import dev.rdbot.Config;
import dev.rdbot.kb.KnowledgeStore;
import dev.rdbot.store.Daos;
import dev.rdbot.store.Database;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;

import java.awt.Color;
import java.time.Instant;
import java.util.Map;

/**
 * Knowledge-gap embeds: what the bot could not answer, with an
 * <b>Add to Knowledge Base</b> button (staff-only) and a <b>Reply as
 * staff</b> modal prefilled with the suggested answer.
 */
public final class GapEmbed extends ListenerAdapter {

    private static final String ADD_PREFIX = "gap-add:";
    private static final String REPLY_PREFIX = "gap-reply:";

    private final Config config;
    private final Database db;
    private final KnowledgeStore store;

    public GapEmbed(Config config, Database db, KnowledgeStore store) {
        this.config = config;
        this.db = db;
        this.store = store;
    }

    /** Posts the gap embed to the gaps channel (falls back to the ticket). */
    public void post(TextChannel ticket, long insightId, User asker, String escalationText,
                     String suggestedTopic, String suggestedAnswer, String ticketChannelId) {
        long gapId = Daos.addGap(db, ticket.getGuild().getId(), escalationText,
                suggestedTopic, suggestedAnswer == null ? "" : suggestedAnswer, insightId,
                ticketChannelId);
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Knowledge gap #" + gapId)
                .setColor(Color.BLUE)
                .setDescription(truncate(escalationText.isBlank() ? "(no escalation text)" : escalationText, 1500))
                .addField("Asked by", asker.getName(), true)
                .addField("Ticket", "<#" + ticket.getId() + ">", true)
                .addField("Suggested topic", suggestedTopic == null || suggestedTopic.isBlank()
                        ? "-" : suggestedTopic, true)
                .addField("AI suggested response", truncate(
                        suggestedAnswer == null || suggestedAnswer.isBlank()
                                ? "(none)" : suggestedAnswer, 1000), false)
                .setTimestamp(Instant.now());
        String configured = config.str("discord.gaps-channel-id", "");
        TextChannel target = configured.isBlank() ? null
                : ticket.getGuild().getTextChannelById(configured);
        if (target == null) target = ticket;
        target.sendMessageEmbeds(embed.build())
                .setActionRow(
                        Button.success(ADD_PREFIX + gapId, "Add to Knowledge Base"),
                        Button.secondary(REPLY_PREFIX + gapId, "Reply as staff"))
                .queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        boolean add = id.startsWith(ADD_PREFIX);
        boolean reply = id.startsWith(REPLY_PREFIX);
        if (!add && !reply) return;
        if (!isStaff(event.getGuild(), event.getMember())) {
            event.reply("Staff only.").setEphemeral(true).queue();
            return;
        }
        long gapId = Long.parseLong(id.substring(id.indexOf(':') + 1));
        if (reply) {
            String guildId = event.getGuild().getId();
            String suggested = Daos.gapById(db, guildId, gapId)
                    .map(Daos.Gap::answer).orElse("");
            Modal modal = Modal.create(REPLY_PREFIX + gapId, "Reply as staff")
                    .addActionRow(TextInput.create("body", "Staff reply", TextInputStyle.PARAGRAPH)
                            .setValue(suggested.length() > 1500 ? suggested.substring(0, 1500) : suggested)
                            .setMaxLength(1500)
                            .build())
                    .build();
            event.replyModal(modal).queue();
            return;
        }
        // Add to Knowledge Base: one click, topic as stored, answer as content.
        try {
            String guildId = event.getGuild().getId();
            Daos.Gap gap = Daos.gapById(db, guildId, gapId)
                    .orElseThrow(() -> new IllegalStateException("gap #" + gapId + " not found"));
            String topic = gap.topic().isBlank() ? "Gap #" + gapId : gap.topic();
            String content = gap.answer().isBlank() ? gap.question() : gap.answer();
            store.addCustom(guildId, topic, content);
            Daos.markGap(db, gapId, "added");
            event.reply("Added **" + topic + "** to the knowledge base.").setEphemeral(true).queue();
        } catch (Exception e) {
            event.reply("Could not add: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith(REPLY_PREFIX)) return;
        if (!isStaff(event.getGuild(), event.getMember())) {
            event.reply("Staff only.").setEphemeral(true).queue();
            return;
        }
        String body = event.getValue("body") == null ? "" : event.getValue("body").getAsString();
        if (body.isBlank()) {
            event.reply("Reply is empty.").setEphemeral(true).queue();
            return;
        }
        // Post into the ticket recorded on the gap row.
        long gapId = Long.parseLong(id.substring(id.indexOf(':') + 1));
        String guildId = event.getGuild().getId();
        String ticketId = Daos.gapById(db, guildId, gapId).map(Daos.Gap::channelId).orElse("");
        TextChannel ticket = ticketId.isBlank() ? null
                : event.getGuild().getTextChannelById(ticketId);
        if (ticket == null) {
            event.reply("Original ticket channel not found.").setEphemeral(true).queue();
            return;
        }
        ticket.sendMessage("**Staff reply:**\n" + body.strip()).queue();
        Daos.markGap(db, gapId, "answered");
        event.reply("Posted.").setEphemeral(true).queue();
    }

    private boolean isStaff(net.dv8tion.jda.api.entities.Guild guild,
                            net.dv8tion.jda.api.entities.Member member) {
        if (guild == null || member == null) return false;
        return TicketDetector.isStaff(member, config, Map.of());
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}

