package dev.rdbot.ai;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One Groq key plus its live stats.
 *
 * <p>Stats are exposed in the panel (requests, tokens, errors, cooldown) but
 * the raw key never leaves this class: {@link #last4()} is all callers see.
 */
public final class ApiKey {

    private final int index;
    private final String value;
    private final RateLimiter limiter;

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private volatile long cooldownUntil;
    private volatile String lastError = "";

    public ApiKey(int index, String value, int requestsPerMinute, int tokensPerMinute) {
        this.index = index;
        this.value = value;
        this.limiter = new RateLimiter(requestsPerMinute, tokensPerMinute);
    }

    public int index() { return index; }

    /** Raw key. Only the Groq client may read this. */
    public String secret() { return value; }

    public String last4() {
        return value.length() <= 4 ? "****" : value.substring(value.length() - 4);
    }

    public RateLimiter limiter() { return limiter; }

    public void recordSuccess(long tokens) {
        totalRequests.incrementAndGet();
        totalTokens.addAndGet(tokens);
    }

    public void recordError(String message) {
        totalErrors.incrementAndGet();
        if (message != null && !message.isBlank()) {
            this.lastError = message.length() > 200 ? message.substring(0, 200) : message;
        }
    }

    /** Marks the key cooling until the given epoch millis (429 Retry-After). */
    public void coolDownUntil(long epochMillis) {
        this.cooldownUntil = epochMillis;
    }

    public boolean isCooling() {
        return cooldownUntil > System.currentTimeMillis();
    }

    public long cooldownRemainingMillis() {
        return Math.max(0L, cooldownUntil - System.currentTimeMillis());
    }

    public long totalRequests() { return totalRequests.get(); }
    public long totalTokens() { return totalTokens.get(); }
    public long totalErrors() { return totalErrors.get(); }
    public String lastError() { return lastError; }
}
