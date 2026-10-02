package dev.rdbot.kb;

import dev.rdbot.store.Daos;
import dev.rdbot.store.Database;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chunking + retrieval: split text into ~600-char chunks, score with a
 * weighted keyword/BM25-style heuristic (topic and title words count more),
 * take the top-K chunks for the prompt.
 */
public final class KnowledgeStore {

    private final Database db;
    private final int chunkSize;

    public KnowledgeStore(Database db, int chunkSize) {
        this.db = db;
        this.chunkSize = Math.max(200, chunkSize);
    }

    public long addCustom(String guildId, String topic, String information) {
        long id = Daos.addKnowledge(db, guildId, "custom", topic, information, "", "{}");
        reindex(guildId, id, topic, information);
        return id;
    }

    public void reindex(String guildId, long entryId, String topic, String text) {
        Daos.replaceChunks(db, entryId, guildId, Chunker.split(text, chunkSize, topic));
    }

    public List<Retriever.Hit> retrieve(String guildId, String question, int topK) {
        return Retriever.search(Daos.chunks(db, guildId), question, topK);
    }

    public void remove(long id) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM knowledge WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<String> split(String text) {
        return new ArrayList<>(Chunker.split(text, chunkSize, ""));
    }

    public List<Retriever.Hit> empty() {
        return Collections.emptyList();
    }
}
