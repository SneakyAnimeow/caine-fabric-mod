package com.dashie.caine.memory;

import com.dashie.caine.CaineModClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Persistent memory system for CAINE using SQLite.
 * Stores memories about players, events, facts, and preferences.
 * Uses upsert to prevent duplicates and supports full-text content search.
 */
public class MemoryManager {
    private final Connection connection;

    public record Memory(long id, String category, String subject, String content, int importance, long createdAt) {
        public String formatted() {
            String prefix = subject != null && !subject.isEmpty() ? "[" + subject + "] " : "";
            return prefix + content;
        }

        public String formattedWithAge() {
            String prefix = subject != null && !subject.isEmpty() ? "[" + subject + "] " : "";
            return prefix + content + " (" + relativeAge(createdAt) + ")";
        }

        private static String relativeAge(long timestamp) {
            long diffMs = System.currentTimeMillis() - timestamp;
            if (diffMs < 0) return "just now";
            long minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs);
            if (minutes < 1) return "just now";
            if (minutes < 60) return minutes + "m ago";
            long hours = TimeUnit.MILLISECONDS.toHours(diffMs);
            if (hours < 24) return hours + "h ago";
            long days = TimeUnit.MILLISECONDS.toDays(diffMs);
            if (days < 30) return days + "d ago";
            return (days / 30) + "mo ago";
        }
    }

    public record Skill(long id, String name, String description, List<String> commands,
                        List<String> triggerPhrases, String learnedFrom, int timesUsed,
                        long createdAt, long updatedAt) {
        public String formatted() {
            StringBuilder sb = new StringBuilder();
            sb.append("**").append(name).append("**");
            if (description != null && !description.isEmpty()) {
                sb.append(" — ").append(description);
            }
            if (commands != null && !commands.isEmpty()) {
                sb.append(" [").append(commands.size()).append(" commands]");
            }
            if (timesUsed > 0) {
                sb.append(" (used ").append(timesUsed).append("x)");
            }
            if (triggerPhrases != null && !triggerPhrases.isEmpty()) {
                sb.append(" triggers: ").append(String.join(", ", triggerPhrases));
            }
            return sb.toString();
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
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
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

            // Add updated_at column if upgrading from old schema
            try {
                stmt.execute("ALTER TABLE memories ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // Column already exists
            }
            // Backfill updated_at for old rows
            stmt.execute("UPDATE memories SET updated_at = created_at WHERE updated_at = 0");

            // Skills table — learned, reusable procedures
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS skills (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    description TEXT NOT NULL,
                    commands TEXT,
                    trigger_phrases TEXT,
                    learned_from TEXT,
                    times_used INTEGER DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_skills_name ON skills(name COLLATE NOCASE)
            """);
        }
    }

    /**
     * Saves a memory with upsert behavior.
     * If a memory with the same category+subject already exists, updates it
     * (appends new content if different, bumps importance and timestamp).
     * This prevents duplicates from accumulating.
     */
    public void saveMemory(String category, String subject, String content, int importance) {
        int safeImportance = Math.max(1, Math.min(importance, 10));

        // Check for existing memory with same category+subject
        if (subject != null && !subject.isEmpty()) {
            Memory existing = findExact(category, subject);
            if (existing != null) {
                // If content is substantially similar, just bump timestamp + importance
                if (contentOverlaps(existing.content(), content)) {
                    updateMemory(existing.id(), content, Math.max(existing.importance(), safeImportance));
                    CaineModClient.LOGGER.info("Memory updated (merged): [{}] {} — {}", category, subject, content);
                    return;
                }
                // Different content for same subject — merge by appending
                String merged = existing.content() + "; " + content;
                // Keep it from growing unbounded
                if (merged.length() > 500) {
                    merged = merged.substring(merged.length() - 500);
                    // Clean up to nearest semicolon
                    int sc = merged.indexOf("; ");
                    if (sc >= 0) merged = merged.substring(sc + 2);
                }
                updateMemory(existing.id(), merged, Math.max(existing.importance(), safeImportance));
                CaineModClient.LOGGER.info("Memory updated (appended): [{}] {} — {}", category, subject, merged);
                return;
            }
        }

        // New memory — insert
        String sql = "INSERT INTO memories (category, subject, content, importance, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setString(1, category);
            ps.setString(2, subject);
            ps.setString(3, content);
            ps.setInt(4, safeImportance);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
            CaineModClient.LOGGER.info("Memory saved: [{}] {} — {}", category, subject, content);
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to save memory", e);
        }
    }

    /**
     * Updates an existing memory's content, importance, and updated_at timestamp.
     */
    private void updateMemory(long id, String content, int importance) {
        String sql = "UPDATE memories SET content = ?, importance = ?, updated_at = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, content);
            ps.setInt(2, importance);
            ps.setLong(3, System.currentTimeMillis());
            ps.setLong(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to update memory {}", id, e);
        }
    }

    /**
     * Finds an exact match by category + subject.
     */
    private Memory findExact(String category, String subject) {
        String sql = "SELECT id, category, subject, content, importance, created_at FROM memories " +
                "WHERE category = ? AND subject = ? COLLATE NOCASE LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, category);
            ps.setString(2, subject);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return readMemory(rs);
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to find memory", e);
        }
        return null;
    }

    /**
     * Checks if two content strings overlap significantly.
     * Returns true if one contains the other or they share long substrings.
     */
    private boolean contentOverlaps(String existing, String newContent) {
        String a = existing.toLowerCase().trim();
        String b = newContent.toLowerCase().trim();
        if (a.contains(b) || b.contains(a)) return true;
        // Check if >60% of words in new content already appear in existing
        String[] newWords = b.split("\\s+");
        if (newWords.length == 0) return true;
        int matches = 0;
        for (String w : newWords) {
            if (w.length() > 2 && a.contains(w)) matches++;
        }
        return (double) matches / newWords.length > 0.6;
    }

    /**
     * Retrieves memories related to a specific subject (player name, topic, etc).
     */
    public List<Memory> getMemoriesAbout(String subject, int limit) {
        String sql = "SELECT id, category, subject, content, importance, created_at FROM memories " +
                "WHERE subject LIKE ? COLLATE NOCASE ORDER BY importance DESC, updated_at DESC LIMIT ?";
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
     * Searches memories by keyword in content or subject.
     * This enables finding memories by what they contain, not just who they're about.
     */
    public List<Memory> searchMemories(String keyword, int limit) {
        String sql = "SELECT id, category, subject, content, importance, created_at FROM memories " +
                "WHERE (content LIKE ? COLLATE NOCASE OR subject LIKE ? COLLATE NOCASE) " +
                "ORDER BY importance DESC, updated_at DESC LIMIT ?";
        List<Memory> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            String pattern = "%" + keyword + "%";
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            ps.setInt(3, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(readMemory(rs));
            }
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to search memories for: {}", keyword, e);
        }
        return results;
    }

    /**
     * Retrieves the most important and recent memories overall.
     */
    public List<Memory> getTopMemories(int limit) {
        String sql = "SELECT id, category, subject, content, importance, created_at FROM memories " +
                "ORDER BY importance DESC, updated_at DESC LIMIT ?";
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
     * Retrieves memories relevant to the given player names and keywords (for prompt context).
     * Combines player-specific memories, keyword-matched memories, and top general memories.
     */
    public String getMemoriesForPrompt(List<String> relevantPlayers, List<String> keywords, int maxTotal) {
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

        // Search by content keywords extracted from chat
        for (String kw : keywords) {
            if (included.size() >= maxTotal) break;
            List<Memory> kwMems = searchMemories(kw, 3);
            for (Memory m : kwMems) {
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
            sb.append("  - (").append(m.category()).append(") ").append(m.formattedWithAge()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Forgets memories by subject and optionally category.
     * Returns count of memories deleted.
     */
    public int forgetBySubject(String subject, String category) {
        try {
            if (category != null && !category.isEmpty()) {
                String sql = "DELETE FROM memories WHERE subject = ? COLLATE NOCASE AND category = ?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, subject);
                    ps.setString(2, category);
                    int rows = ps.executeUpdate();
                    CaineModClient.LOGGER.info("Forgot {} memories: [{}] {}", rows, category, subject);
                    return rows;
                }
            } else {
                String sql = "DELETE FROM memories WHERE subject = ? COLLATE NOCASE";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, subject);
                    int rows = ps.executeUpdate();
                    CaineModClient.LOGGER.info("Forgot {} memories about: {}", rows, subject);
                    return rows;
                }
            }
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to forget memories about: {}", subject, e);
        }
        return 0;
    }

    /**
     * Forgets memories matching a content keyword.
     * Returns count of memories deleted.
     */
    public int forgetByKeyword(String keyword) {
        String sql = "DELETE FROM memories WHERE content LIKE ? COLLATE NOCASE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "%" + keyword + "%");
            int rows = ps.executeUpdate();
            CaineModClient.LOGGER.info("Forgot {} memories matching keyword: {}", rows, keyword);
            return rows;
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to forget memories by keyword: {}", keyword, e);
        }
        return 0;
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

    // ======================== SKILL SYSTEM ========================

    /**
     * Saves or updates a skill (upsert by name).
     * If a skill with the same name exists, updates its description, commands, and trigger phrases.
     */
    public void saveSkill(String name, String description, List<String> commands,
                          List<String> triggerPhrases, String learnedFrom) {
        if (name == null || name.isBlank()) return;

        Skill existing = getSkill(name);
        long now = System.currentTimeMillis();

        if (existing != null) {
            // Update existing skill
            String sql = "UPDATE skills SET description = ?, commands = ?, trigger_phrases = ?, updated_at = ? WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, description);
                ps.setString(2, toJsonArray(commands));
                ps.setString(3, toJsonArray(triggerPhrases));
                ps.setLong(4, now);
                ps.setLong(5, existing.id());
                ps.executeUpdate();
                CaineModClient.LOGGER.info("Skill updated: {} — {}", name, description);
            } catch (SQLException e) {
                CaineModClient.LOGGER.error("Failed to update skill: {}", name, e);
            }
        } else {
            // Insert new skill
            String sql = "INSERT INTO skills (name, description, commands, trigger_phrases, learned_from, times_used, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, 0, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, name.toLowerCase().replace(' ', '_'));
                ps.setString(2, description);
                ps.setString(3, toJsonArray(commands));
                ps.setString(4, toJsonArray(triggerPhrases));
                ps.setString(5, learnedFrom);
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.executeUpdate();
                CaineModClient.LOGGER.info("Skill learned: {} — {} (from: {})", name, description, learnedFrom);
            } catch (SQLException e) {
                CaineModClient.LOGGER.error("Failed to save skill: {}", name, e);
            }
        }
    }

    /**
     * Retrieves a skill by name (case-insensitive).
     */
    public Skill getSkill(String name) {
        String sql = "SELECT * FROM skills WHERE name = ? COLLATE NOCASE LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return readSkill(rs);
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to get skill: {}", name, e);
        }
        return null;
    }

    /**
     * Returns all learned skills, ordered by usage count (most used first).
     */
    public List<Skill> getAllSkills() {
        List<Skill> skills = new ArrayList<>();
        String sql = "SELECT * FROM skills ORDER BY times_used DESC, updated_at DESC";
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                skills.add(readSkill(rs));
            }
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to list skills", e);
        }
        return skills;
    }

    /**
     * Searches skills by name, description, or trigger phrase keyword.
     */
    public List<Skill> searchSkills(String keyword) {
        List<Skill> skills = new ArrayList<>();
        String sql = "SELECT * FROM skills WHERE name LIKE ? COLLATE NOCASE " +
                "OR description LIKE ? COLLATE NOCASE " +
                "OR trigger_phrases LIKE ? COLLATE NOCASE " +
                "ORDER BY times_used DESC LIMIT 10";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            String pattern = "%" + keyword + "%";
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                skills.add(readSkill(rs));
            }
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to search skills for: {}", keyword, e);
        }
        return skills;
    }

    /**
     * Builds the skills section for the AI prompt context.
     */
    public String getSkillsForPrompt() {
        List<Skill> skills = getAllSkills();
        if (skills.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (Skill s : skills) {
            sb.append("  - ").append(s.formatted()).append("\n");
        }
        sb.append("  Total skills learned: ").append(skills.size()).append("\n");
        return sb.toString();
    }

    /**
     * Increments the usage counter for a skill.
     */
    public void incrementSkillUsage(String name) {
        String sql = "UPDATE skills SET times_used = times_used + 1, updated_at = ? WHERE name = ? COLLATE NOCASE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to increment skill usage: {}", name, e);
        }
    }

    /**
     * Deletes a skill by name. Returns true if deleted.
     */
    public boolean deleteSkill(String name) {
        String sql = "DELETE FROM skills WHERE name = ? COLLATE NOCASE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                CaineModClient.LOGGER.info("Skill forgotten: {}", name);
                return true;
            }
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to delete skill: {}", name, e);
        }
        return false;
    }

    /**
     * Returns total number of skills.
     */
    public int getSkillCount() {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM skills");
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            CaineModClient.LOGGER.error("Failed to count skills", e);
        }
        return 0;
    }

    private Skill readSkill(ResultSet rs) throws SQLException {
        return new Skill(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                fromJsonArray(rs.getString("commands")),
                fromJsonArray(rs.getString("trigger_phrases")),
                rs.getString("learned_from"),
                rs.getInt("times_used"),
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    private static String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        JsonArray arr = new JsonArray();
        for (String s : list) arr.add(s);
        return arr.toString();
    }

    private static List<String> fromJsonArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            List<String> result = new ArrayList<>();
            for (var el : arr) result.add(el.getAsString());
            return result;
        } catch (Exception e) {
            return List.of();
        }
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
