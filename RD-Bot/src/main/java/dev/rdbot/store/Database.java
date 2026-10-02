package dev.rdbot.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite wrapper.
 *
 * <p>One connection, guarded by a monitor. SQLite is fast enough for a bot of
 * this size and keeps the deploy a single directory. Every public method is
 * synchronized so DAOs can be called from JDA worker threads, the panel HTTP
 * threads and the scheduler at once without interleaving transactions.
 */
public final class Database implements AutoCloseable {

    private final Connection connection;
    private final Object lock = new Object();

    public Database(Path file) {
        try {
            this.connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + file.toAbsolutePath()
                            + "?busy_timeout=5000&journal_mode=WAL&synchronous=NORMAL");
            try (Statement st = this.connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open database " + file, e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = this.connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    guild_id TEXT PRIMARY KEY,
                    json TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS knowledge (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    guild_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    topic TEXT NOT NULL DEFAULT '',
                    content TEXT NOT NULL DEFAULT '',
                    url TEXT NOT NULL DEFAULT '',
                    meta TEXT NOT NULL DEFAULT '{}',
                    created_at INTEGER NOT NULL,
                    synced_at INTEGER NOT NULL DEFAULT 0
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    entry_id INTEGER NOT NULL REFERENCES knowledge(id) ON DELETE CASCADE,
                    guild_id TEXT NOT NULL,
                    idx INTEGER NOT NULL,
                    text TEXT NOT NULL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_chunks_guild ON knowledge_chunks(guild_id)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS insights (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    guild_id TEXT NOT NULL,
                    user_id TEXT NOT NULL DEFAULT '',
                    user_tag TEXT NOT NULL DEFAULT '',
                    channel_id TEXT NOT NULL DEFAULT '',
                    message_id TEXT NOT NULL DEFAULT '',
                    question TEXT NOT NULL DEFAULT '',
                    answer TEXT NOT NULL DEFAULT '',
                    confidence REAL NOT NULL DEFAULT 0,
                    category TEXT NOT NULL DEFAULT '',
                    reason TEXT NOT NULL DEFAULT '',
                    summary TEXT NOT NULL DEFAULT '',
                    suggested_topic TEXT,
                    sources TEXT NOT NULL DEFAULT '[]',
                    escalated INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_insights_guild ON insights(guild_id, created_at DESC)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS escalations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    guild_id TEXT NOT NULL,
                    insight_id INTEGER,
                    user_id TEXT NOT NULL DEFAULT '',
                    user_tag TEXT NOT NULL DEFAULT '',
                    channel_id TEXT NOT NULL DEFAULT '',
                    category TEXT NOT NULL DEFAULT '',
                    reason TEXT NOT NULL DEFAULT '',
                    summary TEXT NOT NULL DEFAULT '',
                    confidence REAL NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'open',
                    created_at INTEGER NOT NULL,
                    handled_at INTEGER
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_esc_guild ON escalations(guild_id, created_at DESC)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS gaps (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    guild_id TEXT NOT NULL,
                    question TEXT NOT NULL DEFAULT '',
                    suggested_topic TEXT,
                    suggested_answer TEXT NOT NULL DEFAULT '',
                    insight_id INTEGER,
                    status TEXT NOT NULL DEFAULT 'open',
                    created_at INTEGER NOT NULL
                )""");
            try {
                st.execute("ALTER TABLE gaps ADD COLUMN channel_id TEXT NOT NULL DEFAULT ''");
            } catch (SQLException alreadyThere) {
                // sqlite has no ADD COLUMN IF NOT EXISTS; second boot hits this
            }
            st.execute("CREATE INDEX IF NOT EXISTS idx_gaps_guild ON gaps(guild_id, created_at DESC)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS key_assignments (
                    guild_id TEXT PRIMARY KEY,
                    key_index INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS channel_locks (
                    channel_id TEXT PRIMARY KEY,
                    user_id TEXT,
                    reason TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL
                )""");
        }
    }

    /** Runs work inside one exclusive connection scope. */
    public <T> T tx(SqlWork<T> work) {
        synchronized (lock) {
            try {
                T result = work.run(connection);
                return result;
            } catch (SQLException e) {
                throw new IllegalStateException("database error", e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }
}
