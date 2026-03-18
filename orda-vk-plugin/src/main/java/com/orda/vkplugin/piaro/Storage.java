package com.orda.vkplugin.piaro;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

public class Storage {
    private final File dbFile;
    private final Logger logger;
    private Connection connection;

    public Storage(File dbFile, Logger logger) {
        this.dbFile = dbFile;
        this.logger = logger;
    }

    public synchronized void initSchema() {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("create table if not exists post_history (id integer primary key autoincrement, rubric text not null, reason text not null, text text not null, image_path text, status text not null, created_at text not null)");
                st.executeUpdate("create table if not exists topic_history (id integer primary key autoincrement, topic text not null, created_at text not null)");
                st.executeUpdate("create table if not exists prompt_log (id integer primary key autoincrement, rubric text not null, prompt text not null, created_at text not null)");
                st.executeUpdate("create table if not exists publication_status (id integer primary key autoincrement, rubric text not null, status text not null, details text, created_at text not null)");
                st.executeUpdate("create table if not exists kv_state (k text primary key, v text not null)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Storage init error", e);
        }
    }

    public synchronized int countPublishedToday() {
        String from = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        try (PreparedStatement ps = connection.prepareStatement("select count(*) c from post_history where status='published' and created_at>=?")) {
            ps.setString(1, from);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("c") : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    public synchronized int countPublishedTodayByRubric(String rubric) {
        String from = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        try (PreparedStatement ps = connection.prepareStatement("select count(*) c from post_history where status='published' and rubric=? and created_at>=?")) {
            ps.setString(1, rubric);
            ps.setString(2, from);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("c") : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    public synchronized boolean hasTopicInLast24h(String topic) {
        String edge = Instant.now().minusSeconds(24 * 3600).toString();
        try (PreparedStatement ps = connection.prepareStatement("select count(*) c from topic_history where topic=? and created_at>=?")) {
            ps.setString(1, topic);
            ps.setString(2, edge);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("c") > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized boolean tooSimilarRecentText(String text) {
        String normalizedInput = normalize(text);
        String edge = Instant.now().minusSeconds(24 * 3600).toString();
        try (PreparedStatement ps = connection.prepareStatement("select text from post_history where created_at>=? order by id desc limit 12")) {
            ps.setString(1, edge);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String prev = rs.getString("text");
                    if (prev == null) continue;
                    if (similarity(normalize(prev), normalizedInput) >= 0.90) {
                        return true;
                    }
                }
            }
        } catch (SQLException ignored) {
        }
        return false;
    }

    public synchronized void savePost(String rubric, String reason, String text, String imagePath, String status) {
        try (PreparedStatement ps = connection.prepareStatement("insert into post_history(rubric,reason,text,image_path,status,created_at) values(?,?,?,?,?,?)")) {
            ps.setString(1, rubric);
            ps.setString(2, reason);
            ps.setString(3, text);
            ps.setString(4, imagePath);
            ps.setString(5, status);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("savePost failed: " + e.getMessage());
        }
    }

    public synchronized void addTopic(String topic) {
        try (PreparedStatement ps = connection.prepareStatement("insert into topic_history(topic,created_at) values(?,?)")) {
            ps.setString(1, topic);
            ps.setString(2, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("addTopic failed: " + e.getMessage());
        }
    }

    public synchronized void logPrompt(String rubric, String prompt) {
        try (PreparedStatement ps = connection.prepareStatement("insert into prompt_log(rubric,prompt,created_at) values(?,?,?)")) {
            ps.setString(1, rubric);
            ps.setString(2, prompt);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("logPrompt failed: " + e.getMessage());
        }
    }

    public synchronized void markSkipped(String rubric, String details) {
        try (PreparedStatement ps = connection.prepareStatement("insert into publication_status(rubric,status,details,created_at) values(?,?,?,?)")) {
            ps.setString(1, rubric);
            ps.setString(2, "skipped");
            ps.setString(3, details);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("markSkipped failed: " + e.getMessage());
        }
    }

    public synchronized String getStateString(String key, String def) {
        try (PreparedStatement ps = connection.prepareStatement("select v from kv_state where k=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("v");
                }
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    public synchronized void setStateString(String key, String value) {
        try (PreparedStatement ps = connection.prepareStatement("insert into kv_state(k,v) values(?,?) on conflict(k) do update set v=excluded.v")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("setStateString failed: " + e.getMessage());
        }
    }

    public synchronized int getStateInt(String key, int def) {
        try (PreparedStatement ps = connection.prepareStatement("select v from kv_state where k=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Integer.parseInt(rs.getString("v"));
                }
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    public synchronized void setStateInt(String key, int value) {
        try (PreparedStatement ps = connection.prepareStatement("insert into kv_state(k,v) values(?,?) on conflict(k) do update set v=excluded.v")) {
            ps.setString(1, key);
            ps.setString(2, String.valueOf(value));
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("setStateInt failed: " + e.getMessage());
        }
    }

    public synchronized void closeSilently() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        Set<String> setA = toTokenSet(a);
        Set<String> setB = toTokenSet(b);
        if (setA.isEmpty() || setB.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(setA);
        inter.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    private Set<String> toTokenSet(String text) {
        String[] parts = normalize(text).split(" ");
        Set<String> set = new HashSet<>();
        for (String part : parts) {
            if (part.length() >= 3) {
                set.add(part);
            }
        }
        return set;
    }

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }
}
