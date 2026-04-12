package com.dashie.caine.memory;

import com.dashie.caine.CaineModClient;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent memory system for CAINE using SQLite.
 * Stores memories about players, events, facts, and preferences.
 */
public class MemoryManager {
    private final Connection connection;

    public record Memory(long id, String category, String subject, String content, int importance, long createdAt) {
        public String formatted() {
            String prefix = subject != null && !subject.isEmpty() ? "[" + subject + "] " : "";
            return prefix + content;
        }
    }

    public MemoryManager(Path dbPath) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            connection.setAutoCommit(true);
            initDatabase();
            CaineModClient.LOGGER.info("Memory database initialized at: {}", dbPath);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize CAINE memory database", e);
        }
    }

    private void initDatabase() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category TEXT NOT NULL,
                    subject TEXT,
                    content TEXT NOT NULL,
                    importance INTEGER DEFAULT 5,
                    created_at INTEGER NOT NULL
                )
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_memories_subject ON memories(subject COLLATE NOCASE)
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_memories_category ON memories(category)
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_memories_importance ON memories(importance DESC)
            """);
        }
    }

    /**
     * Saves a new memory.
     */
    public void saveMemory(String category, String subject, String content, int importance) {
        String sql = "INSERT INTO memories (category, subject, content, importance, created_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, category);
            ps.setString(2, subject);
            ps.setString(3, content);
            ps.setInt(4, Math.max(1, Math.min(importance, 10)));
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            CaineModClient.LOGGER.info("Memory saved: [{}] {} — {}", category, subject, content);
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to save memory", e);
        }
    }

    /**
     * Retrieves memories related to a specific subject (player name, topic, etc).
     */
    public List<Memory> getMemoriesAbout(String subject, int limit) {
        String sql = "SELECT id, category, subject, content, importance, created_at FROM memories " +
                "WHERE subject LIKE ? COLLATE NOCASE ORDER BY importance DESC, created_at DESC LIMIT ?";
        List<Memory> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "%" + subject + "%");
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(readMemory(rs));
            }
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to query memories about: {}", subject, e);
        }
        return results;
    }

    /**
     * Retrieves the most important and recent memories overall.
     */
    public List<Memory> getTopMemories(int limit) {
        String sql = "SELECT id, category, subject, content, importance, created_at FROM memories " +
                "ORDER BY importance DESC, created_at DESC LIMIT ?";
        List<Memory> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(readMemory(rs));
            }
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to query top memories", e);
        }
        return results;
    }

    /**
     * Retrieves memories relevant to the given player names (for prompt context).
     * Combines player-specific memories with top general memories.
     */
    public String getMemoriesForPrompt(List<String> relevantPlayers, int maxTotal) {
        StringBuilder sb = new StringBuilder();
        List<Memory> included = new ArrayList<>();

        // Get player-specific memories first
        for (String player : relevantPlayers) {
            List<Memory> playerMems = getMemoriesAbout(player, 5);
            for (Memory m : playerMems) {
                if (included.size() >= maxTotal) break;
                if (included.stream().noneMatch(existing -> existing.id() == m.id())) {
                    included.add(m);
                }
            }
        }

        // Fill remaining slots with top general memories
        int remaining = maxTotal - included.size();
        if (remaining > 0) {
            List<Memory> top = getTopMemories(remaining + included.size());
            for (Memory m : top) {
                if (included.size() >= maxTotal) break;
                if (included.stream().noneMatch(existing -> existing.id() == m.id())) {
                    included.add(m);
                }
            }
        }

        if (included.isEmpty()) {
            return "";
        }

        for (Memory m : included) {
            sb.append("  - (").append(m.category()).append(") ").append(m.formatted()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Deletes a memory by id.
     */
    public boolean forgetMemory(long id) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM memories WHERE id = ?")) {
            ps.setLong(1, id);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                CaineModClient.LOGGER.info("Memory {} forgotten", id);
                return true;
            }
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to forget memory {}", id, e);
        }
        return false;
    }

    /**
     * Returns total number of memories.
     */
    public int getMemoryCount() {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM memories");
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to count memories", e);
        }
        return 0;
    }

    private Memory readMemory(ResultSet rs) throws SQLException {
        return new Memory(
                rs.getLong("id"),
                rs.getString("category"),
                rs.getString("subject"),
                rs.getString("content"),
                rs.getInt("importance"),
                rs.getLong("created_at")
        );
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to close memory database", e);
        }
    }
}
