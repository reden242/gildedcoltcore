package dev.rdbot.kb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * BM25-flavoured keyword retrieval that runs in the process.
 *
 * <p>Term frequency against the chunk, IDF against the guild's corpus, and a
 * topic/title weight multiplier. No vector DB, no embeddings, no extra
 * dependency - rude on long-tail wording, honest on everything else.
 */
public final class Retriever {

    public record Hit(long entryId, String topic, String kind, String text, double score) { }

    private Retriever() { }

    public static List<Hit> search(List<Chunk> chunks, String question, int topK) {
        if (chunks.isEmpty() || question == null || question.isBlank()) return List.of();
        List<String> terms = tokenize(question);
        if (terms.isEmpty()) return List.of();

        double avgLen = chunks.stream().mapToInt(c -> tokenize(c.text()).size()).average().orElse(50);
        int total = chunks.size();
        List<Hit> hits = new ArrayList<>();
        for (Chunk chunk : chunks) {
            String combined = chunk.text() + " " + chunk.topic() + " " + chunk.topic();
            List<String> doc = tokenize(combined);
            if (doc.isEmpty()) continue;
            double score = 0;
            for (String term : terms) {
                long freq = doc.stream().filter(term::equals).count();
                if (freq == 0) continue;
                long docsWithTerm = chunks.stream()
                        .filter(c -> tokenize(c.text() + " " + c.topic()).contains(term))
                        .count();
                double idf = Math.log(1.0 + (total - docsWithTerm + 0.5) / (docsWithTerm + 0.5));
                double k = 1.2;
                double b = 0.75;
                double tf = freq * (k + 1) / (freq + k * (1 - b + b * doc.size() / avgLen));
                score += idf * tf;
            }
            if (score > 0) hits.add(new Hit(chunk.entryId(), chunk.topic(), chunk.kind(), chunk.text(), score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(Math.max(1, topK), hits.size()));
    }

    private static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        for (String word : text.toLowerCase().split("[^a-z0-9]+")) {
            if (word.length() >= 3 && !STOP.contains(word)) out.add(word);
        }
        return out;
    }

    private static final java.util.Set<String> STOP = java.util.Set.of(
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "had", "her",
            "was", "one", "our", "out", "has", "have", "with", "this", "that", "from",
            "they", "will", "what", "how", "why", "when", "who", "your", "does", "did");
}
