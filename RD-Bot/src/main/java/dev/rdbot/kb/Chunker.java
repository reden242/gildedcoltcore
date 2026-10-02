package dev.rdbot.kb;

import java.util.ArrayList;
import java.util.List;

/** Splits text into chunks without cutting sentences in the middle. */
public final class Chunker {

    private Chunker() { }

    public static List<String> split(String text, int maxSize, String header) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        StringBuilder current = new StringBuilder();
        if (header != null && !header.isBlank()) {
            current.append(header.strip()).append(": ");
        }
        // Paragraphs first, sentences second: never breaks a word.
        for (String paragraph : text.split("\n\\s*\n")) {
            if (current.length() + paragraph.length() > maxSize && current.length() > 0) {
                out.add(current.toString().strip());
                current.setLength(0);
            }
            if (paragraph.length() <= maxSize) {
                if (current.length() > 0) current.append('\n');
                current.append(paragraph.strip());
                continue;
            }
            for (String sentence : paragraph.split("(?<=[.!?])\\s+")) {
                if (current.length() + sentence.length() > maxSize && current.length() > 0) {
                    out.add(current.toString().strip());
                    current.setLength(0);
                }
                if (current.length() > 0) current.append(' ');
                current.append(sentence.strip());
            }
        }
        if (current.length() > 0) out.add(current.toString().strip());
        return out;
    }
}
