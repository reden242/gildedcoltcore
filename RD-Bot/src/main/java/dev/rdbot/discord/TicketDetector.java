package dev.rdbot.discord;

import dev.rdbot.Config;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

import java.util.List;
import java.util.Map;

/** Ticket/staff detection shared by the message listener and slash commands. */
public final class TicketDetector {

    private TicketDetector() { }

    public static boolean isTicket(MessageChannel channel, Config config, Map<String, Object> settings) {
        if (!(channel instanceof TextChannel text)) return false;
        String guildId = text.getGuild().getId();
        List<String> categories = settingStrings(settings, "ticketCategories",
                guildCategories(config, guildId));
        var parent = text.getParentCategory();
        if (parent != null && categories.contains(parent.getId())) return true;
        List<String> prefixes = settingStrings(settings, "channelPrefixes",
                config.strings("discord.tickets.channel-prefixes"));
        String name = text.getName().toLowerCase();
        for (String prefix : prefixes) {
            if (!prefix.isBlank() && name.startsWith(prefix.toLowerCase())) return true;
        }
        List<String> keywords = settingStrings(settings, "topicKeywords",
                config.strings("discord.tickets.topic-keywords"));
        String topic = text.getTopic() == null ? "" : text.getTopic().toLowerCase();
        for (String keyword : keywords) {
            if (!keyword.isBlank() && topic.contains(keyword.toLowerCase())) return true;
        }
        return false;
    }

    public static boolean isStaff(Member member, Config config, Map<String, Object> settings) {
        if (member == null) return false;
        if (member.hasPermission(Permission.ADMINISTRATOR)
                || member.hasPermission(Permission.MANAGE_SERVER)) return true;
        List<String> staffRoles = settingStrings(settings, "staffRoleIds",
                config.strings("discord.tickets.staff-role-ids"));
        if (staffRoles.isEmpty()) return false;
        for (var role : member.getRoles()) {
            if (staffRoles.contains(role.getId())) return true;
        }
        return false;
    }

    public static boolean ignoreBots(Config config, Map<String, Object> settings) {
        Object v = settings.get("ignoreBots");
        return v == null ? config.bool("discord.tickets.ignore-bots", true)
                : Boolean.parseBoolean(String.valueOf(v));
    }

    public static boolean ignoreStaff(Config config, Map<String, Object> settings) {
        Object v = settings.get("ignoreStaff");
        return v == null ? config.bool("discord.tickets.ignore-staff", true)
                : Boolean.parseBoolean(String.valueOf(v));
    }

    public static boolean pauseOnStaffReply(Config config, Map<String, Object> settings) {
        Object v = settings.get("pauseOnStaffReply");
        return v == null ? config.bool("discord.tickets.pause-on-staff-reply", true)
                : Boolean.parseBoolean(String.valueOf(v));
    }

    private static List<String> settingStrings(Map<String, Object> settings, String key,
                                               List<String> fallback) {
        Object v = settings.get(key);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return fallback;
    }

    /**
     * Per-guild ticket categories from {@code discord.tickets.guild-categories},
     * falling back to the global list. Panel-saved {@code ticketCategories}
     * still win over both.
     */
    private static List<String> guildCategories(Config config, String guildId) {
        Map<String, Object> perGuild = config.map("discord.tickets.guild-categories");
        Object scoped = perGuild.get(guildId);
        if (scoped instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        return config.strings("discord.tickets.category-ids");
    }

    public static String channelLabel(Channel channel) {
        return channel instanceof TextChannel t
                ? "#" + t.getName() + " (" + t.getId() + ")"
                : channel.getId();
    }
}
