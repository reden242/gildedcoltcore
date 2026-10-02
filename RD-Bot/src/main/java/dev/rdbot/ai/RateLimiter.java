package dev.rdbot.ai;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding 60-second window limiter for one API key.
 *
 * <p>Two ceilings: requests per minute and tokens per minute. A caller blocks
 * until a slot frees up or its wait budget runs out, so bursts queue instead
 * of hitting Groq and getting a 429. Fully synchronized - one instance per key
 * and every access happens from the AI worker or the panel.
 */
public final class RateLimiter {

    private final int requestsPerMinute;
    private final int tokensPerMinute;
    private final Deque<long[]> window = new ArrayDeque<>(); // [epochMillis, tokens]
    private int windowRequests;
    private long windowTokens;

    public RateLimiter(int requestsPerMinute, int tokensPerMinute) {
        this.requestsPerMinute = Math.max(1, requestsPerMinute);
        this.tokensPerMinute = Math.max(1, tokensPerMinute);
    }

    /**
     * Acquires room for one request with an estimated token cost, waiting up
     * to {@code waitMillis}. Returns false when the wait budget is exhausted.
     */
    public synchronized boolean acquire(long tokens, long waitMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, waitMillis);
        while (true) {
            prune(System.currentTimeMillis());
            boolean requestRoom = windowRequests < requestsPerMinute;
            boolean tokenRoom = windowTokens + tokens <= tokensPerMinute;
            if (requestRoom && tokenRoom) {
                windowRequests++;
                windowTokens += tokens;
                window.addLast(new long[]{System.currentTimeMillis(), tokens});
                return true;
            }
            long now = System.currentTimeMillis();
            if (now >= deadline) return false;
            long oldest = window.isEmpty() ? now : window.peekFirst()[0];
            long wait = Math.max(5L, oldest + 60_000L - now);
            try {
                wait(Math.min(wait, deadline - now));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void prune(long now) {
        while (!window.isEmpty() && window.peekFirst()[0] <= now - 60_000L) {
            long[] head = window.pollFirst();
            windowRequests--;
            windowTokens -= head[1];
        }
    }

    public synchronized int requestsInWindow() {
        prune(System.currentTimeMillis());
        return windowRequests;
    }

    public synchronized long tokensInWindow() {
        prune(System.currentTimeMillis());
        return windowTokens;
    }
}
