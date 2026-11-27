package org.ajeet.sync;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
 * Shared database contract: keep the DDL identical to the sdk's AgentQueue.
 */
@Repository
public class SyncRepository {

    /** Agent counts as online if its heartbeat is newer than this. */
    public static final int ONLINE_SECONDS = 30;

    private final JdbcClient db;

    public SyncRepository(JdbcClient db) {
        this.db = db;
        db.sql("""
                CREATE TABLE IF NOT EXISTS sync_agent (
                    id            INT PRIMARY KEY CHECK (id = 1),
                    host          TEXT NOT NULL,
                    device        TEXT NOT NULL,
                    last_seen     TIMESTAMP NOT NULL,
                    last_sync_at  TIMESTAMP,
                    last_appended INT,
                    last_error    TEXT
                )""").update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS sync_requests (
                    id           BIGSERIAL PRIMARY KEY,
                    requested_at TIMESTAMP NOT NULL DEFAULT now(),
                    requested_by TEXT NOT NULL,
                    since        TIMESTAMP,
                    status       TEXT NOT NULL DEFAULT 'pending',
                    finished_at  TIMESTAMP,
                    appended     INT,
                    error        TEXT
                )""").update();
    }

    /** Queues a sync for the agent; since = only punches from then on (null: the agent's default). */
    public long request(String requestedBy, LocalDateTime since) {
        return db.sql("INSERT INTO sync_requests (requested_by, since) VALUES (:by, :since) RETURNING id")
                .param("by", requestedBy)
                .param("since", since == null ? null : Timestamp.valueOf(since), Types.TIMESTAMP)
                .query(Long.class).single();
    }

    /** Current state of a request: pending, running, done, failed, expired - or missing. */
    public RequestState state(long id) {
        return db.sql("SELECT status, appended, error FROM sync_requests WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new RequestState(rs.getString(1), rs.getInt(2), rs.getString(3)))
                .optional().orElse(new RequestState("missing", 0, null));
    }

    /** Heartbeat row, or null if no agent has ever connected. */
    public AgentStatus status() {
        return db.sql("""
                        SELECT host, device,
                               EXTRACT(EPOCH FROM now() - last_seen)::BIGINT,
                               EXTRACT(EPOCH FROM now() - last_sync_at)::BIGINT,
                               last_appended, last_error
                        FROM sync_agent WHERE id = 1""")
                .query((rs, n) -> {
                    long seen = rs.getLong(3);
                    long synced = rs.getLong(4);
                    boolean hasSync = !rs.wasNull();
                    int appended = rs.getInt(5);
                    return new AgentStatus(rs.getString(1), rs.getString(2), seen,
                            hasSync ? synced : null, hasSync ? appended : null,
                            rs.getString(6), seen <= ONLINE_SECONDS);
                })
                .optional().orElse(null);
    }

    public record RequestState(String status, int appended, String error) {}

    public record AgentStatus(String host, String device, long lastSeenSecondsAgo,
                              Long lastSyncSecondsAgo, Integer lastAppended,
                              String lastError, boolean online) {}
}
