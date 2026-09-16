package com.coltcore.core.modules;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Seed data for the local clean-versus-advertising classifier. */
public final class SeedCorpus {

    private SeedCorpus() { }

    public record Sample(String text, String label) { }

    private static final int PER_LABEL = 12_000;
    private static final Pattern POSSIBLE_ADDRESS = Pattern.compile(
            "https?:|discord|\\b\\d{1,3}(\\.\\d{1,3}){3}\\b|\\b[a-z0-9-]+\\.[a-z]{2,}\\b");

    private static final String[] AD_FRAMES = {
            "join %s", "come to %s", "try %s", "check out %s", "play on %s",
            "best server is %s", "free ranks at %s", "come play %s",
            "everyone join %s", "new server %s", "server ip is %s",
            "ip %s", "hop on %s", "looking for players at %s",
            "my server is %s", "we moved to %s", "visit %s", "pvp server %s"
    };

    private static final String[] AD_HOSTS = {
            "examplecraft.net", "play.examplecraft.gg", "mc123.org", "coolpvp.xyz",
            "funserver.com", "hypixelclone.net", "pvpzone.gg", "survivalcraft.org",
            "minescape.xyz", "craftkingdom.net", "skypvp.gg", "bedwars.pro",
            "anarchy.ml", "smp.tk", "creativecraft.com", "dragonmc.net",
            "emeraldisle.org", "foxcraft.gg", "goldrush.xyz", "icecraft.ml",
            "junglecraft.net", "kingdoms.gg", "lunarmc.xyz", "nethermc.org"
    };

    private static final String[] BENIGN_DOMAIN_LINES = {
            "check reddit.com later", "that youtube.com tutorial helped",
            "the docs at papermc.io explain it", "i read it on minecraft.net",
            "look at spigotmc.org for plugins", "github.com has the source",
            "wikipedia.org says otherwise", "dictionary.com has the meaning",
            "the wiki at fandom.com is old", "google.com it yourself",
            "i saw that on twitch.tv", "the clip is on streamable.com",
            "paste it on pastebin.com", "that map is on planetminecraft.com",
            "the sound is on soundcloud.com", "that song is on music.apple.com",
            "buy it on store.steampowered.com", "the mod is on curseforge.com",
            "check fabricmc.net for the loader", "the issue is on bugs.mojang.com",
            "the answer is on stackoverflow.com", "that image is on imgur.com",
            "the video is on vimeo.com", "read the post on medium.com",
            "the changelog is on papermc.io", "that plugin is on dev.bukkit.org",
            "the texture pack is on resourcepack.net", "the stats are on namemc.com",
            "the server list is on minecraftservers.org", "the guide is on digminecraft.com"
    };

    private static final String[] DIRECT_AD_LINES = {
            "discord.gg/comehere", "discord.com/invite/comehere",
            "join discord.gg/example", "discord.gg/example for free stuff",
            "join our discord.gg/server", "discord.gg/server now",
            "invite discord.gg/players", "discord.com/invite/players",
            "discord.gg/community", "discord.gg/community join now"
    };

    public static List<Sample> build(List<String> labels) {
        Set<String> clean = new LinkedHashSet<>();
        for (String line : realChat()) clean.add(line);
        addWithMutations(clean, BENIGN_DOMAIN_LINES);

        Set<String> advertising = new LinkedHashSet<>();
        for (String frame : AD_FRAMES) {
            for (String host : AD_HOSTS) addWithMutations(advertising, String.format(frame, host));
        }
        addWithMutations(advertising, DIRECT_AD_LINES);

        List<Sample> output = new ArrayList<>();
        emit(output, labels, clean, LocalAiModule.LABEL_CLEAN);
        emit(output, labels, advertising, LocalAiModule.LABEL_ADVERTISING);
        return output;
    }

    private static void addWithMutations(Set<String> output, String[] lines) {
        for (String line : lines) addWithMutations(output, line);
    }

    private static void addWithMutations(Set<String> output, String line) {
        for (int mutation = 0; mutation < Mutations.COUNT; mutation++) {
            output.add(Mutations.apply(line, mutation));
        }
    }

    private static void emit(List<Sample> output, List<String> labels,
                             Set<String> examples, String label) {
        if (!labels.contains(label)) return;
        int added = 0;
        for (String text : examples) {
            if (text == null || text.isBlank() || text.length() > 300) continue;
            output.add(new Sample(text, label));
            if (++added == PER_LABEL) return;
        }
    }

    private static List<String> realChat() {
        List<String> lines = new ArrayList<>();
        try (InputStream input = SeedCorpus.class.getResourceAsStream("/realchat.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String clean = line.trim().toLowerCase(Locale.ROOT);
                if (!clean.isBlank() && !POSSIBLE_ADDRESS.matcher(clean).find()) lines.add(clean);
            }
        } catch (Exception ignored) {
            // A server without the optional resource simply trains on the generated data.
        }
        return lines;
    }
}
