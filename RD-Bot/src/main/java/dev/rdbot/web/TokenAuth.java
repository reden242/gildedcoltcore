package dev.rdbot.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Panel token store + cookie sessions + brute-force throttling.
 *
 * <p>Tokens are 48+ characters of [a-zA-Z0-9] from SecureRandom. Disk holds
 * only SHA-256 hashes ({@code panel-tokens.json}); the cookie holds the raw
 * token, compared in constant time. Rotation replaces the whole list, which
 * invalidates every old cookie at once.
 */
public final class TokenAuth {

    private static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final java.nio.file.Path file;
    private final List<String> hashes = new ArrayList<>(); // sha256 hex
    private final SecureRandom random = new SecureRandom();

    // brute force throttle: ip -> failures + ban
    private final Map<String, int[]> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> bans = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final long banSeconds;

    public TokenAuth(java.nio.file.Path file, int maxFailures, long banSeconds) {
        this.file = file;
        this.maxFailures = Math.max(1, maxFailures);
        this.banSeconds = Math.max(30, banSeconds);
        load();
    }

    /** First boot: mint a token, persist its hash, return the raw value once. */
    public synchronized String ensureInitial() {
        if (!hashes.isEmpty()) return null;
        String token = generate();
        hashes.add(sha256(token));
        save();
        return token;
    }

    public synchronized String rotate() {
        String token = generate();
        hashes.clear();
        hashes.add(sha256(token));
        save();
        return token;
    }

    /**
     * Issues one more token without invalidating the others (used for the
     * admin DM links). Capped at 20 stored hashes, oldest pruned first.
     */
    public synchronized String issue() {
        String token = generate();
        hashes.add(sha256(token));
        while (hashes.size() > 20) hashes.remove(0);
        save();
        return token;
    }

    public synchronized boolean valid(String token) {
        if (token == null || token.isBlank()) return false;
        String hash = sha256(token.trim());
        for (String known : hashes) {
            if (MessageDigest.isEqual(hash.getBytes(StandardCharsets.UTF_8),
                    known.getBytes(StandardCharsets.UTF_8))) return true;
        }
        return false;
    }

    public synchronized int count() { return hashes.size(); }

    public boolean banned(String ip) {
        Long until = bans.get(ip);
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            bans.remove(ip);
            return false;
        }
        return true;
    }

    public void failure(String ip) {
        int[] count = failures.computeIfAbsent(ip, k -> new int[1]);
        count[0]++;
        if (count[0] >= maxFailures) {
            bans.put(ip, System.currentTimeMillis() + banSeconds * 1000L);
            failures.remove(ip);
        }
    }

    public void success(String ip) {
        failures.remove(ip);
    }

    /** Random per-login CSRF secret, echoed back in the X-CSRF-Token header. */
    public String issueCsrf() {
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private String generate() {
        StringBuilder sb = new StringBuilder(48);
        for (int i = 0; i < 48; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void load() {
        if (!java.nio.file.Files.exists(file)) return;
        try {
            String json = java.nio.file.Files.readString(file, StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            com.fasterxml.jackson.databind.JsonNode hashes = node.path("hashes");
            if (hashes.isArray()) {
                hashes.forEach(h -> this.hashes.add(h.asText()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }

    private void save() {
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("hashes", new ArrayList<>(hashes));
            doc.put("updated", System.currentTimeMillis());
            java.nio.file.Files.writeString(file,
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writerWithDefaultPrettyPrinter().writeValueAsString(doc),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write " + file, e);
        }
    }
}
