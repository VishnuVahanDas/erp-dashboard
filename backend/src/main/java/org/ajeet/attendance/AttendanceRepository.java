package org.ajeet.attendance;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read side of the attendance data. The Windows sync agent (sdk repo) writes
 * {@code punches} and {@code users}; both projects create the tables with
 * IF NOT EXISTS so either can start first. Shared database contract: keep the
 * DDL identical to the sdk's PunchStore. Created before the other stores
 * (LegacyWiring waits for it), as it always was.
 */
@Repository
class AttendanceRepository {

    private static final String SELECT = """
            SELECT p.enroll_number, COALESCE(u.name,''),
                   to_char(p.punch_time, 'YYYY-MM-DD HH24:MI:SS'), p.verify_label
            FROM punches p LEFT JOIN users u ON u.enroll_number = p.enroll_number
            """;

    private final JdbcClient db;

    AttendanceRepository(JdbcClient db) {
        this.db = db;
        db.sql("""
                CREATE TABLE IF NOT EXISTS users (
                    enroll_number INT PRIMARY KEY,
                    name          TEXT NOT NULL DEFAULT ''
                )""").update();
        db.sql("""
                CREATE TABLE IF NOT EXISTS punches (
                    id            BIGSERIAL PRIMARY KEY,
                    enroll_number INT NOT NULL,
                    punch_time    TIMESTAMP NOT NULL,
                    verify_label  TEXT NOT NULL,
                    UNIQUE (enroll_number, punch_time, verify_label)
                )""").update();
    }

    /** Newest first, capped at 500 rows (the log views). where may use one positional ? param. */
    List<AttendanceService.AttendancePunch> punchRows(String where, Object param) {
        JdbcClient.StatementSpec q = db.sql(SELECT + where + " ORDER BY p.punch_time DESC LIMIT 500");
        if (param != null) q = q.params(param);
        return q.query((rs, n) -> new AttendanceService.AttendancePunch(rs.getInt(1), rs.getString(2),
                rs.getString(3), rs.getString(4), null)).list();
    }

    /** One device user's punches in [from, toExclusive), newest first, up to 5000. */
    List<AttendanceService.AttendancePunch> between(int enrollNumber, LocalDate from, LocalDate toExclusive) {
        return db.sql(SELECT + """
                        WHERE p.enroll_number = ? AND p.punch_time >= ? AND p.punch_time < ?
                        ORDER BY p.punch_time DESC LIMIT 5000""")
                .params(enrollNumber, Date.valueOf(from), Date.valueOf(toExclusive))
                .query((rs, n) -> new AttendanceService.AttendancePunch(rs.getInt(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), null)).list();
    }

    /** Punches of these device users: today only, one date, or all (500 newest). enrolls not empty. */
    List<AttendanceService.AttendancePunch> forTeam(Collection<Integer> enrolls, boolean todayOnly, LocalDate date) {
        String sql = SELECT + "WHERE p.enroll_number IN (:enrolls)" +
                (todayOnly ? " AND p.punch_time::date = current_date"
                        : date != null ? " AND p.punch_time::date = :date" : "") +
                " ORDER BY p.punch_time DESC LIMIT 500";
        JdbcClient.StatementSpec q = db.sql(sql).param("enrolls", List.copyOf(enrolls));
        if (!todayOnly && date != null) q = q.param("date", Date.valueOf(date));
        return q.query((rs, n) -> new AttendanceService.AttendancePunch(rs.getInt(1), rs.getString(2),
                rs.getString(3), rs.getString(4), null)).list();
    }

    /** Device users not yet claimed by any web account - the "link device user" dropdown. */
    Map<Integer, String> unlinkedUsers() {
        Map<Integer, String> out = new LinkedHashMap<>();
        db.sql("""
                        SELECT u.enroll_number, u.name FROM users u
                        LEFT JOIN accounts a ON a.enroll_number = u.enroll_number
                        WHERE a.id IS NULL ORDER BY u.enroll_number""")
                .query(rs -> { out.put(rs.getInt(1), rs.getString(2)); });
        return out;
    }
}
