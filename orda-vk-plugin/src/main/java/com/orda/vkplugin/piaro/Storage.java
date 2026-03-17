package com.orda.vkplugin.piaro;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class Storage implements AutoCloseable {
    private final File file;
    private final Logger logger;
    private Connection connection;

    public Storage(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void initSchema() {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("create table if not exists campaigns (id integer primary key autoincrement, created_at text not null)");
                st.executeUpdate("create table if not exists message_variants (id integer primary key autoincrement, campaign_id integer not null, text text not null, used integer not null default 0)");
                st.executeUpdate("create table if not exists targets (id integer primary key autoincrement, group_id integer not null unique, vk_link text, enabled integer not null default 1)");
                st.executeUpdate("create table if not exists outbox (id integer primary key autoincrement, campaign_id integer not null, target_group_id integer not null, text text not null, status text not null, created_at text not null, sent_at text)");
                st.executeUpdate("create table if not exists send_history (id integer primary key autoincrement, group_id integer not null, text_hash text not null, sent_at text not null)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot initialize DB", e);
        }
    }

    public int createCampaign() {
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement("insert into campaigns(created_at) values (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return -1;
    }

    public void saveVariants(int campaignId, List<String> variants) {
        try (PreparedStatement ps = connection.prepareStatement("insert into message_variants(campaign_id,text,used) values(?,?,0)")) {
            for (String variant : variants) {
                ps.setInt(1, campaignId);
                ps.setString(2, variant);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> takeUnusedVariants(int campaignId) {
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("select id,text from message_variants where campaign_id=? and used=0")) {
            ps.setInt(1, campaignId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("text"));
                    markVariantUsed(rs.getInt("id"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return result;
    }

    private void markVariantUsed(int id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("update message_variants set used=1 where id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    public void cleanCampaignData(int campaignId) {
        try (PreparedStatement p1 = connection.prepareStatement("delete from message_variants where campaign_id=?");
             PreparedStatement p2 = connection.prepareStatement("delete from outbox where campaign_id=?");
             PreparedStatement p3 = connection.prepareStatement("delete from campaigns where id=?")) {
            p1.setInt(1, campaignId);
            p1.executeUpdate();
            p2.setInt(1, campaignId);
            p2.executeUpdate();
            p3.setInt(1, campaignId);
            p3.executeUpdate();
        } catch (SQLException e) {
            logger.warning("DB cleanup failed: " + e.getMessage());
        }
    }

    public int enqueueOutbox(int campaignId, int groupId, String text) {
        try (PreparedStatement ps = connection.prepareStatement("insert into outbox(campaign_id,target_group_id,text,status,created_at) values(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, campaignId);
            ps.setInt(2, groupId);
            ps.setString(3, text);
            ps.setString(4, "queued");
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return -1;
    }

    public void updateOutboxStatus(int outboxId, String status) {
        try (PreparedStatement ps = connection.prepareStatement("update outbox set status=?, sent_at=? where id=?")) {
            ps.setString(1, status);
            ps.setString(2, Instant.now().toString());
            ps.setInt(3, outboxId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Cannot update outbox status: " + e.getMessage());
        }
    }

    public boolean sentInLast24h(int groupId, String textHash) {
        String edge = Instant.now().minusSeconds(24 * 3600).toString();
        try (PreparedStatement ps = connection.prepareStatement("select count(*) c from send_history where group_id=? and text_hash=? and sent_at>=?")) {
            ps.setInt(1, groupId);
            ps.setString(2, textHash);
            ps.setString(3, edge);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("c") > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public void addSendHistory(int groupId, String textHash) {
        try (PreparedStatement ps = connection.prepareStatement("insert into send_history(group_id,text_hash,sent_at) values(?,?,?)")) {
            ps.setInt(1, groupId);
            ps.setString(2, textHash);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("Cannot write send_history: " + e.getMessage());
        }
    }

    @Override
    public void close() throws IOException {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new IOException(e);
            }
        }
    }
}
