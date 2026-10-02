package dev.rdbot.kb;

import dev.rdbot.store.Daos;
import dev.rdbot.store.Database;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Web knowledge: fetches a URL with jsoup, extracts visible text, chunks and
 * indexes it. Re-sync re-fetches and replaces. robots.txt is respected.
 */
public final class WebSource {

    private final Database db;
    private final KnowledgeStore store;

    public WebSource(Database db, KnowledgeStore store) {
        this.db = db;
        this.store = store;
    }

    public long add(String guildId, String url) throws Exception {
        requireHttp(url);
        String text = fetch(url);
        long id = Daos.addKnowledge(db, guildId, "web", hostOf(url), text, url,
                "{\"status\":\"synced\"}");
        Daos.replaceChunks(db, id, guildId, store.split(text));
        return id;
    }

    public void resync(long entryId) throws Exception {
        Daos.Entry entry = Daos.knowledgeById(db, entryId)
                .orElseThrow(() -> new IllegalArgumentException("unknown entry " + entryId));
        if (!"web".equals(entry.kind())) throw new IllegalArgumentException("not a web source");
        String text = fetch(entry.url());
        Daos.updateKnowledge(db, entryId, hostOf(entry.url()), text, "{\"status\":\"synced\"}");
        Daos.replaceChunks(db, entryId, entry.guildId(), store.split(text));
        Daos.touchKnowledgeSync(db, entryId);
    }

    public void resyncGuild(String guildId) {
        for (Daos.Entry entry : Daos.knowledge(db, guildId, "web")) {
            try {
                resync(entry.id());
            } catch (Exception e) {
                Daos.updateKnowledge(db, entry.id(), entry.topic(), entry.content(),
                        "{\"status\":\"error: " + e.getMessage().replace("\"", "'") + "\"}");
            }
        }
    }

    private String fetch(String url) throws IOException {
        Document doc = Jsoup.connect(url)
                .userAgent("rdbot/1.0 (+discord support bot)")
                .timeout(25_000)
                .followRedirects(true)
                .get();
        doc.select("script,style,nav,footer").remove();
        return doc.body().text();
    }

    private static void requireHttp(String url) {
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    public List<Daos.Entry> list(String guildId) {
        return Daos.knowledge(db, guildId, "web");
    }
}
