package dev.rdbot.kb;

import dev.rdbot.store.Daos;
import dev.rdbot.store.Database;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Document knowledge: PDF (PDFBox), TXT and MD (plain text). */
public final class DocumentSource {

    private final Database db;
    private final KnowledgeStore store;

    public DocumentSource(Database db, KnowledgeStore store) {
        this.db = db;
        this.store = store;
    }

    public long add(String guildId, String fileName, byte[] bytes) throws Exception {
        String lower = fileName.toLowerCase();
        if (!lower.endsWith(".pdf") && !lower.endsWith(".txt") && !lower.endsWith(".md")) {
            throw new IllegalArgumentException("Only PDF, TXT and MD are accepted.");
        }
        Path tmp = Files.createTempFile("rdbot-doc", lower.endsWith(".pdf") ? ".pdf" : ".txt");
        try {
            Files.write(tmp, bytes);
            String text = lower.endsWith(".pdf") ? readPdf(tmp) : readText(tmp);
            if (text.isBlank()) throw new IllegalArgumentException("No readable text in " + fileName);
            long id = Daos.addKnowledge(db, guildId, "document", fileName, text, "",
                    "{\"status\":\"synced\",\"file\":\"" + fileName.replace("\"", "'") + "\"}");
            List<String> chunks = store.split(text);
            Daos.replaceChunks(db, id, guildId, chunks);
            return id;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String readPdf(Path file) throws IOException {
        try (PDDocument doc = PDDocument.load(file.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static String readText(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public List<Daos.Entry> list(String guildId) {
        return Daos.knowledge(db, guildId, "document");
    }
}
