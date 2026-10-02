package dev.rdbot.discord;

import dev.rdbot.Config;
import dev.rdbot.ai.AiResponse;
import dev.rdbot.ai.AiService;
import dev.rdbot.store.Daos;
import dev.rdbot.store.Database;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Ticket messages in, AI replies out.
 *
 * <p>Order of gates: paused guild -> not a ticket -> bots -> staff (pause) ->
 * lock (stop replying) -> AiService (hours, cooldown, caps, cache) -> reply
 * or Escalator. Typing indicator goes up before the blocking AI call.
 */
public final class MessageListener extends ListenerAdapter {

    private final Config config;
    private final Database db;
    private final AiService ai;
    private final Escalator escalator;

    public MessageListener(Config config, Database db, AiService ai, Escalator escalator) {
        this.config = config;
        this.db = db;
        this.ai = ai;
        this.escalator = escalator;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild()) return;
        var guild = event.getGuild();
        Map<String, Object> settings = ai.guildSettings(guild.getId());
        if (Boolean.TRUE.equals(settings.get("paused"))) return;
        if (!(event.getChannel() instanceof TextChannel text)) return;
        if (!TicketDetector.isTicket(text, config, settings)) return;

        var author = event.getAuthor();
        var member = event.getMember();
        boolean staff = TicketDetector.isStaff(member, config, settings);

        if (staff) {
            // Staff in the room: optionally pause, never answer staff text.
            if (TicketDetector.pauseOnStaffReply(config, settings)) {
                Daos.lockChannel(db, text.getId(), "", "staff replied");
            }
            return;
        }
        if (author.isBot() || author.isSystem()) {
            return; // ignoreBots is effectively always on here on purpose
        }
        if (TicketDetector.ignoreStaff(config, settings) && staff) return;

        Object after = settings.get("afterEscalation");
        String afterMode = after == null ? config.str("ai.after-escalation", "stop") : String.valueOf(after);
        if ("stop".equalsIgnoreCase(afterMode)
                && Daos.isLocked(db, text.getId(), author.getId())) {
            return; // waiting on staff; archive in the panel lifts this
        }

        if (config.bool("discord.typing-indicator", true)) {
            text.sendTyping().queue();
        }

        AiService.Result result = ai.handle(guild.getId(), author.getId(),
                author.getName(), text.getId(), event.getMessage().getContentRaw(),
                history(text, event.getMessage().getId()));
        if (result == null) return; // cooldown

        String footer = footerText(guild.getId());
        if ("out-of-hours".equals(result.escalationCategory())) {
            escalator.postOffline(text, result.displayText(), result.outOfHoursRole());
            return;
        }
        if (result.escalationCategory() != null) {
            escalator.escalate(event, result, footer, settings);
            return;
        }
        if (!result.shouldAnswer()) return;
        String reply = result.displayText().isBlank()
                ? result.displayText() : result.displayText() + footer;
        text.sendMessage(reply.isBlank() ? "(empty reply suppressed)" : reply)
                .setMessageReference(event.getMessageId())
                .queue(sent -> ai.countReply(text.getId()));
    }

    /** Last N ticket messages as "name: text", oldest first. */
    private String history(TextChannel text, String currentMessageId) {
        int keep = Math.max(0, config.intOf("ai.history-messages", 8));
        if (keep == 0) return "";
        try {
            List<Message> past = text.getHistory().retrievePast(Math.min(keep + 2, 20)).complete();
            List<String> lines = new ArrayList<>();
            for (int i = past.size() - 1; i >= 0 && lines.size() < keep; i--) {
                Message m = past.get(i);
                if (m.getId().equals(currentMessageId) || m.getAuthor().isSystem()) continue;
                String raw = m.getContentRaw().strip();
                if (raw.isEmpty()) continue;
                lines.add(m.getAuthor().getName() + ": "
                        + (raw.length() > 300 ? raw.substring(0, 300) : raw));
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            return ""; // history is a bonus; never fail the reply over it
        }
    }

    private String footerText(String guildId) {
        String footer = config.str("discord.reply-footer",
                "Made by mtyri with love :heart:").strip();
        return footer.isEmpty() ? "" : "\n\n" + footer;
    }
}

