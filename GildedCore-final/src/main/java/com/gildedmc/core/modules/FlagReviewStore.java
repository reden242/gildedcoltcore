package com.gildedmc.core.modules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The staff-visible record of advertising flags: who said what, at what
 * score, and why. Fed from {@code ChatGuardModule.blockAdvertising}, so
 * every block lands here exactly once; read by the flag-review chest GUI,
 * where executive+ staff mark each one false-positive or confirmed.
 *
 * <p>Bounded in-memory ring (latest {@value #MAX_FLAGS} survive a reload
 * boundary by not surviving it — a restart clears the queue, which is
 * acceptable for a live-review tool and keeps no unbounded state).
 * Thread-safe: blocks arrive from async chat threads while staff click on
 * the main thread.
 */
public final class FlagReviewStore {

    /** Most flags retained. The GUI shows the newest first. */
    static final int MAX_FLAGS = 54;

    /** One flagged message awaiting adjudication. */
    public record Flag(int id, String playerName, String text, double score,
                       String reason, String surface, long time) { }

    private final Deque<Flag> flags = new ArrayDeque<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    /** Records a flag. Never throws. */
    public synchronized void add(String playerName, String text, double score,
                                 String reason, String surface) {
        try {
            this.flags.addLast(new Flag(this.nextId.getAndIncrement(),
                    playerName == null ? "?" : playerName,
                    text == null ? "" : text, score,
                    reason == null ? "" : reason,
                    surface == null ? "" : surface,
                    System.currentTimeMillis()));
            while (this.flags.size() > MAX_FLAGS) this.flags.removeFirst();
        } catch (Throwable ignored) {
            // A review backlog must never break a block.
        }
    }

    /** Newest first. */
    public synchronized List<Flag> list() {
        List<Flag> out = new ArrayList<>(this.flags);
        out.sort((a, b) -> Integer.compare(b.id(), a.id()));
        return out;
    }

    /** Removes a flag after adjudication. Returns it, or null. */
    public synchronized Flag remove(int id) {
        for (Flag f : this.flags) {
            if (f.id() == id) {
                this.flags.remove(f);
                return f;
            }
        }
        return null;
    }

    public synchronized int size() {
        return this.flags.size();
    }

    public synchronized void clear() {
        this.flags.clear();
    }
}
