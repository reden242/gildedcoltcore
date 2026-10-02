package dev.rdbot;

import java.nio.file.Path;

/**
 * Standalone entry point: {@code java -jar RD-Bot.jar}.
 *
 * <p>All of the work lives in {@link Boot} so the Paper plugin
 * ({@link RDBotPlugin}) boots through the exact same code path. Only the
 * shutdown wiring differs, because a standalone process needs a JVM hook
 * while a plugin is stopped by the server.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(System.getProperty("user.dir"));
        Boot boot = new Boot(dir, System.out::println);
        boot.start();

        Boot shutdown = boot;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown.stop(), "shutdown"));
        // The HTTP server and the scheduler are daemon threads, so the process
        // would exit on its own; park the main thread to keep it alive.
        Thread.currentThread().join();
    }
}