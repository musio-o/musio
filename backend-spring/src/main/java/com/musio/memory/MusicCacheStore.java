package com.musio.memory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class MusicCacheStore {
    private final SQLiteMemoryDatabase database;
    private volatile boolean ftsEnabled;

    @Autowired
    public MusicCacheStore(SQLiteMemoryDatabase database) {
        this.database = database;
        initialize();
    }

    public synchronized void upsert(MusicCacheEntry entry) {
        if (entry == null || entry.cacheType().isBlank() || entry.content().isBlank()) {
            return;
        }
        String sql = """
                INSERT INTO music_cache_entries
                (id, user_id, cache_type, song_id, title, content, evidence, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    user_id = excluded.user_id,
                    cache_type = excluded.cache_type,
                    song_id = excluded.song_id,
                    title = excluded.title,
                    content = excluded.content,
                    evidence = excluded.evidence,
                    updated_at = excluded.updated_at
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entry.id());
            statement.setString(2, entry.userId());
            statement.setString(3, entry.cacheType());
            statement.setString(4, entry.songId());
            statement.setString(5, entry.title());
            statement.setString(6, entry.content());
            statement.setString(7, entry.evidence());
            statement.setString(8, entry.updatedAt().toString());
            statement.executeUpdate();
            upsertFts(connection, entry);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert music cache entry.", e);
        }
    }

    public synchronized List<MusicCacheEntry> search(String userId, List<String> cacheTypes, String query, int limit) {
        List<String> types = normalizeTypes(cacheTypes);
        String normalizedQuery = query == null ? "" : query.strip();
        if (ftsEnabled && !normalizedQuery.isBlank()) {
            try {
                List<MusicCacheEntry> ftsResults = searchFts(userId, types, normalizedQuery, limit);
                if (!ftsResults.isEmpty()) {
                    return ftsResults;
                }
            } catch (SQLException ignored) {
                ftsEnabled = false;
            }
        }
        return searchLike(userId, types, normalizedQuery, limit);
    }

    public synchronized List<MusicCacheEntry> searchBySongId(String userId, List<String> cacheTypes, String songId, int limit) {
        String normalizedSongId = songId == null ? "" : songId.strip();
        if (normalizedSongId.isBlank()) {
            return List.of();
        }
        List<String> types = normalizeTypes(cacheTypes);
        String sql = """
                SELECT id, user_id, cache_type, song_id, title, content, evidence, updated_at
                FROM music_cache_entries
                WHERE user_id = ? AND song_id = ? %s
                ORDER BY updated_at DESC
                LIMIT ?
                """.formatted(typeFilter("", types));
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, safeUserId(userId));
            statement.setString(2, normalizedSongId);
            int index = bindTypes(statement, 3, types);
            statement.setInt(index, Math.max(1, Math.min(20, limit)));
            try (ResultSet resultSet = statement.executeQuery()) {
                return readEntries(resultSet);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to search music cache by song id.", e);
        }
    }

    private void initialize() {
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS music_cache_entries (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        cache_type TEXT NOT NULL,
                        song_id TEXT NOT NULL DEFAULT '',
                        title TEXT NOT NULL DEFAULT '',
                        content TEXT NOT NULL DEFAULT '',
                        evidence TEXT NOT NULL DEFAULT '',
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_music_cache_user_type ON music_cache_entries(user_id, cache_type)");
            try {
                statement.executeUpdate("""
                        CREATE VIRTUAL TABLE IF NOT EXISTS music_cache_fts
                        USING fts5(id UNINDEXED, user_id UNINDEXED, cache_type UNINDEXED, song_id UNINDEXED, title, content)
                        """);
                ftsEnabled = true;
            } catch (SQLException ignored) {
                ftsEnabled = false;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize music cache store.", e);
        }
    }

    private void upsertFts(Connection connection, MusicCacheEntry entry) {
        if (!ftsEnabled) {
            return;
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM music_cache_fts WHERE id = ?");
             PreparedStatement insert = connection.prepareStatement("INSERT INTO music_cache_fts(id, user_id, cache_type, song_id, title, content) VALUES (?, ?, ?, ?, ?, ?)")) {
            delete.setString(1, entry.id());
            delete.executeUpdate();
            insert.setString(1, entry.id());
            insert.setString(2, entry.userId());
            insert.setString(3, entry.cacheType());
            insert.setString(4, entry.songId());
            insert.setString(5, entry.title());
            insert.setString(6, entry.content());
            insert.executeUpdate();
        } catch (SQLException ignored) {
            ftsEnabled = false;
        }
    }

    private List<MusicCacheEntry> searchFts(String userId, List<String> types, String query, int limit) throws SQLException {
        String sql = """
                SELECT e.id, e.user_id, e.cache_type, e.song_id, e.title, e.content, e.evidence, e.updated_at
                FROM music_cache_fts
                JOIN music_cache_entries e ON e.id = music_cache_fts.id
                WHERE music_cache_fts.user_id = ? AND music_cache_fts MATCH ? %s
                ORDER BY rank
                LIMIT ?
                """.formatted(typeFilter("e", types));
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, safeUserId(userId));
            statement.setString(2, ftsQuery(query));
            int index = bindTypes(statement, 3, types);
            statement.setInt(index, Math.max(1, Math.min(20, limit)));
            try (ResultSet resultSet = statement.executeQuery()) {
                return readEntries(resultSet);
            }
        }
    }

    private List<MusicCacheEntry> searchLike(String userId, List<String> types, String query, int limit) {
        String sql = """
                SELECT id, user_id, cache_type, song_id, title, content, evidence, updated_at
                FROM music_cache_entries
                WHERE user_id = ? %s %s
                ORDER BY updated_at DESC
                LIMIT ?
                """.formatted(typeFilter("", types), query.isBlank() ? "" : "AND (title LIKE ? OR content LIKE ?)");
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, safeUserId(userId));
            int index = bindTypes(statement, 2, types);
            if (!query.isBlank()) {
                String pattern = "%" + query + "%";
                statement.setString(index++, pattern);
                statement.setString(index++, pattern);
            }
            statement.setInt(index, Math.max(1, Math.min(20, limit)));
            try (ResultSet resultSet = statement.executeQuery()) {
                return readEntries(resultSet);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to search music cache.", e);
        }
    }

    private List<MusicCacheEntry> readEntries(ResultSet resultSet) throws SQLException {
        List<MusicCacheEntry> entries = new ArrayList<>();
        while (resultSet.next()) {
            entries.add(new MusicCacheEntry(
                    resultSet.getString("id"),
                    resultSet.getString("user_id"),
                    resultSet.getString("cache_type"),
                    resultSet.getString("song_id"),
                    resultSet.getString("title"),
                    resultSet.getString("content"),
                    resultSet.getString("evidence"),
                    Instant.parse(resultSet.getString("updated_at"))
            ));
        }
        return List.copyOf(entries);
    }

    private String typeFilter(String alias, List<String> types) {
        if (types.isEmpty()) {
            return "";
        }
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        return "AND " + prefix + "cache_type IN (" + "?,".repeat(types.size()).replaceFirst(",$", "") + ")";
    }

    private int bindTypes(PreparedStatement statement, int start, List<String> types) throws SQLException {
        int index = start;
        for (String type : types) {
            statement.setString(index++, type);
        }
        return index;
    }

    private List<String> normalizeTypes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                String type = value.strip();
                types.add(type);
                if ("commentSummary".equals(type)) {
                    types.add("comments");
                }
            }
        }
        return List.copyOf(types);
    }

    private String ftsQuery(String query) {
        String normalized = query == null ? "" : query.strip().replace("\"", " ");
        if (normalized.isBlank()) {
            return "\"\"";
        }
        return "\"" + normalized + "\"";
    }

    private String safeUserId(String userId) {
        return userId == null || userId.isBlank() ? "local" : userId.strip();
    }
}
