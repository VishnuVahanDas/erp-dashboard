package org.ajeet.sync;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;

/**
 * Dashboard side of the link with the Windows sync agent (sdk repo), through
 * the shared database (the agent opens no port):
 * <ul>
 *   <li>{@code sync_agent} — heartbeat row the agent refreshes every few seconds.</li>
 *   <li>{@code sync_requests} — "Sync device" requests queued here and claimed
 *       by the agent.</li>
 * </ul>
 * Ages use the database clock, so clock skew between machines doesn't matter.
 * Keep the DDL in sync with the sdk's AgentQueue.
 */
public class SyncRequests {

    /** Agent counts as online if its heartbeat is newer than this. */
    public static final int ONLINE_SECONDS = 30;

    private final Connection conn;

    public SyncRequests(String url, String user, String password) throws IOException {
        try {
            conn = DriverManager.getConnection(url, user, password);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS sync_agent (
                            id            INT PRIMARY KEY CHECK (id = 1),
                            host          TEXT NOT NULL,
                            device        TEXT NOT NULL,
                            last_seen     TIMESTAMP NOT NULL,
                            last_sync_at  TIMESTAMP,
                            last_appended INT,
                            last_error    TEXT
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS sync_requests (
                            id           BIGSERIAL PRIMARY KEY,
                            requested_at TIMESTAMP NOT NULL DEFAULT now(),
                            requested_by TEXT NOT NULL,
                            since        TIMESTAMP,
                            status       TEXT NOT NULL DEFAULT 'pending',
                            finished_at  TIMESTAMP,
                            appended     INT,
                            error        TEXT
                        )""");
            }
        } catch (SQLException e) {
            throw new IOException("sync queue init failed: " + e.getMessage(), e);
        }
    }

    public synchronized long request(String requestedBy, LocalDateTime since) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sync_requests (requested_by, since) VALUES (?, ?) RETURNING id")) {
            ps.setString(1, requestedBy);
            if (since == null) ps.setNull(2, Types.TIMESTAMP);
            else ps.setTimestamp(2, Timestamp.valueOf(since));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IOException("sync request failed: " + e.getMessage(), e);
        }
    }

    /** Current state of a request: pending, running, done, failed or expired. */
    public synchronized RequestState state(long id) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status, appended, error FROM sync_requests WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new RequestState("missing", 0, null);
                return new RequestState(rs.getString(1), rs.getInt(2), rs.getString(3));
            }
        } catch (SQLException e) {
            throw new IOException("sync state failed: " + e.getMessage(), e);
        }
    }

    /** Heartbeat row, or null if no agent has ever connected. */
    public synchronized AgentStatus status() throws IOException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT host, device,
                            EXTRACT(EPOCH FROM now() - last_seen)::BIGINT,
                            EXTRACT(EPOCH FROM now() - last_sync_at)::BIGINT,
                            last_appended, last_error
                     FROM sync_agent WHERE id = 1""")) {
            if (!rs.next()) return null;
            long seen = rs.getLong(3);
            long synced = rs.getLong(4);
            boolean hasSync = !rs.wasNull();
            int appended = rs.getInt(5);
            return new AgentStatus(rs.getString(1), rs.getString(2), seen,
                    hasSync ? synced : null, hasSync ? appended : null,
                    rs.getString(6), seen <= ONLINE_SECONDS);
        } catch (SQLException e) {
            throw new IOException("agent status failed: " + e.getMessage(), e);
        }
    }

    public record RequestState(String status, int appended, String error) {}

    public record AgentStatus(String host, String device, long lastSeenSecondsAgo,
                              Long lastSyncSecondsAgo, Integer lastAppended,
                              String lastError, boolean online) {}
}
