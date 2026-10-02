package dev.rdbot.ai;

import dev.rdbot.store.Daos;
import dev.rdbot.store.Database;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The key pool: assigns each guild the least-loaded key, keeps it sticky,
 * and fails over on cooldown.
 *
 * ponytail: in-process counters, no persistence of usage - assignments are
 * the only thing that must survive a restart. Add per-minute persistence if
 * the panel ever needs historical charts.
 */
public final class KeyPool {

    private final List<ApiKey> keys;
    private final Map<String, Integer> sticky = new ConcurrentHashMap<>();
    private final Database db;

    public KeyPool(List<String> rawKeys, int requestsPerMinute, int tokensPerMinute, Database db) {
        this.keys = new ArrayList<>();
        for (int i = 0; i < rawKeys.size(); i++) {
            this.keys.add(new ApiKey(i, rawKeys.get(i), requestsPerMinute, tokensPerMinute));
        }
        this.db = db;
    }

    public int size() { return keys.size(); }

    /** The key a guild is pinned to, assigning (and persisting) one if new. */
    public ApiKey forGuild(String guildId) {
        if (keys.isEmpty()) throw new IllegalStateException("No Groq API keys configured");
        Integer idx = sticky.get(guildId);
        if (idx == null) {
            idx = Daos.keyAssignment(db, guildId).orElseGet(this::leastLoadedIndex);
            sticky.put(guildId, idx);
            Daos.saveKeyAssignment(db, guildId, idx);
        }
        ApiKey key = keys.get(idx);
        if (key.isCooling()) {
            int alt = nextAvailable(idx);
            if (alt != idx) {
                sticky.put(guildId, alt);
                Daos.saveKeyAssignment(db, guildId, alt);
                return keys.get(alt);
            }
        }
        return key;
    }

    /** Next non-cooling key after {@code from}, wrapping; falls back to it. */
    public ApiKey failover(ApiKey from) {
        int alt = nextAvailable(from.index());
        return keys.get(alt);
    }

    private int nextAvailable(int from) {
        for (int i = 1; i <= keys.size(); i++) {
            ApiKey candidate = keys.get((from + i) % keys.size());
            if (!candidate.isCooling()) return candidate.index();
        }
        return from;
    }

    private int leastLoadedIndex() {
        int best = 0;
        long bestLoad = Long.MAX_VALUE;
        for (ApiKey key : keys) {
            long load = key.limiter().requestsInWindow() * 1_000_000L + key.limiter().tokensInWindow();
            if (!key.isCooling() && load < bestLoad) {
                bestLoad = load;
                best = key.index();
            }
        }
        return best;
    }

    public List<ApiKey> all() { return List.copyOf(keys); }

    /** Panel view: never the raw key, only the last four characters. */
    public List<Map<String, Object>> stats() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiKey key : keys) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("index", key.index());
            row.put("last4", key.last4());
            row.put("requests", key.totalRequests());
            row.put("tokens", key.totalTokens());
            row.put("errors", key.totalErrors());
            row.put("cooldownMs", key.cooldownRemainingMillis());
            row.put("requestsInWindow", key.limiter().requestsInWindow());
            row.put("tokensInWindow", key.limiter().tokensInWindow());
            row.put("lastError", key.lastError());
            out.add(row);
        }
        return out;
    }
}
