package dev.rdbot.kb;

/** One retrieved knowledge fragment plus where it came from. */
public record Chunk(long entryId, String topic, String kind, int idx, String text) {
}
