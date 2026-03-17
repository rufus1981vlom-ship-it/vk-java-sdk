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
import java.util.logging.Logger;

public class Storage {
    private final File dbFile;
    private final Logger logger;
    private Connection connection;

    public Storage(File dbFile, Logger logger) {
        this.dbFile = dbFile;
        this.logger = logger;
    }

    public void initSchema() {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("create table if not exists post_history (id integer primary key autoincrement, rubric text not null, reason text not null, text text not null, image_path text, status text not null, created_at text not null)");
                st.executeUpdate("create table if not exists topic_history (id integer primary key autoincrement, topic text not null, created_at text not null)");
                st.executeUpdate("create table if not exists prompt_log (id integer primary key autoincrement, rubric text not null, prompt text not null, created_at text not null)");
                st.executeUpdate("create table if not exists publication_status (id integer primary key autoincrement, rubric text not null, status text not null, details text, created_at text not null)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Storage init error", e);
        }
    }

    public int countPublishedToday() {
        String from = LocalDate.now().atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toString();
        try (PreparedStatement ps = connection.prepareStatement("select count(*) c from post_history where status='published' and created_at>=?")) {
            ps.setString(1, from);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("c") : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    public boolean hasTopicInLast24h(String topic) {
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

    public boolean tooSimilarRecentText(String text) {
        String edge = Instant.now().minusSeconds(24 * 3600).toString();
        try (PreparedStatement ps = connection.prepareStatement("select text from post_history where created_at>=? order by id desc limit 8")) {
            ps.setString(1, edge);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String prev = rs.getString("text");
                    if (prev != null && similarity(prev, text) >= 0.92) {
                        return true;
                    }
                }
            }
        } catch (SQLException ignored) {
        }
        return false;
    }

    public void savePost(String rubric, String reason, String text, String imagePath, String status) {
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

    public void addTopic(String topic) {
        try (PreparedStatement ps = connection.prepareStatement("insert into topic_history(topic,created_at) values(?,?)")) {
            ps.setString(1, topic);
            ps.setString(2, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("addTopic failed: " + e.getMessage());
        }
    }

    public void logPrompt(String rubric, String prompt) {
        try (PreparedStatement ps = connection.prepareStatement("insert into prompt_log(rubric,prompt,created_at) values(?,?,?)")) {
            ps.setString(1, rubric);
            ps.setString(2, prompt);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("logPrompt failed: " + e.getMessage());
        }
    }

    public void markSkipped(String rubric, String details) {
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

    public void closeSilently() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int min = Math.min(a.length(), b.length());
        int same = 0;
        for (int i = 0; i < min; i++) {
            if (a.charAt(i) == b.charAt(i)) same++;
        }
        return min == 0 ? 0 : (double) same / min;
    }
}
