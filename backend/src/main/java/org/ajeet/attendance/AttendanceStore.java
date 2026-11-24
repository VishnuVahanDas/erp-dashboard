package org.ajeet.attendance;

import java.time.LocalDate;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read side of the attendance data. The Windows sync agent (sdk repo) writes
 * {@code punches} and {@code users}; both projects create the tables with
 * IF NOT EXISTS so either can start first. Keep the DDL in sync with the
 * sdk's PunchStore.
 */
public class AttendanceStore {

    private final Connection conn;
    private final String url;

    public AttendanceStore(String url, String user, String password) throws IOException {
        this.url = url;
        try {
            this.conn = DriverManager.getConnection(url, user, password);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS users (
                            enroll_number INT PRIMARY KEY,
                            name          TEXT NOT NULL DEFAULT ''
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS punches (
                            id            BIGSERIAL PRIMARY KEY,
                            enroll_number INT NOT NULL,
                            punch_time    TIMESTAMP NOT NULL,
                            verify_label  TEXT NOT NULL,
                            UNIQUE (enroll_number, punch_time, verify_label)
                        )""");
            }
        } catch (SQLException e) {
            throw new IOException("PostgreSQL init failed: " + e.getMessage(), e);
        }
    }

    /** One punch row for the portal: name resolved via the users join. */
    public record AttendancePunch(int enrollNumber, String name, String time, String verifyLabel) {}

    /** Punches for a single device user, newest first (the employee's own view). */
    public synchronized List<AttendancePunch> punchesFor(int enrollNumber) throws IOException {
        return punchRows("WHERE p.enroll_number = ?", enrollNumber);
    }

    /**
     * One device user's punches in [from, toExclusive), newest first - a
     * person's month in the People details. Own range filter, so a busy
     * month isn't cut off by the 500-row cap of the log views.
     */
    public synchronized List<AttendancePunch> punchesBetween(int enrollNumber, LocalDate from,
                                                             LocalDate toExclusive) throws IOException {
        List<AttendancePunch> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.enroll_number, COALESCE(u.name,''),
                       to_char(p.punch_time, 'YYYY-MM-DD HH24:MI:SS'), p.verify_label
                FROM punches p LEFT JOIN users u ON u.enroll_number = p.enroll_number
                WHERE p.enroll_number = ? AND p.punch_time >= ? AND p.punch_time < ?
                ORDER BY p.punch_time DESC LIMIT 5000""")) {
            ps.setInt(1, enrollNumber);
            ps.setObject(2, from);
            ps.setObject(3, toExclusive);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AttendancePunch(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("person attendance query failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** Everyone who punched today - the director dashboard card. */
    public synchronized List<AttendancePunch> punchesToday() throws IOException {
        return punchRows("WHERE p.punch_time::date = current_date", null);
    }

    /**
     * All punches, newest first - the director full log. With {@code date},
     * only that day (so any past day is reachable despite the 500-row cap).
     */
    public synchronized List<AttendancePunch> punchesAll(LocalDate date) throws IOException {
        return date == null ? punchRows("", null) : punchRows("WHERE p.punch_time::date = ?", date);
    }

    /** Punches for a set of device users (a manager's team), newest first. */
    public synchronized List<AttendancePunch> punchesForTeam(Collection<Integer> enrolls,
                                                             boolean todayOnly,
                                                             LocalDate date) throws IOException {
        if (enrolls.isEmpty()) return List.of();
        List<AttendancePunch> out = new ArrayList<>();
        String sql = """
                SELECT p.enroll_number, COALESCE(u.name,''),
                       to_char(p.punch_time, 'YYYY-MM-DD HH24:MI:SS'), p.verify_label
                FROM punches p LEFT JOIN users u ON u.enroll_number = p.enroll_number
                WHERE p.enroll_number = ANY(?)""" +
                (todayOnly ? " AND p.punch_time::date = current_date"
                        : date != null ? " AND p.punch_time::date = ?" : "") +
                " ORDER BY p.punch_time DESC LIMIT 500";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("integer", enrolls.toArray()));
            if (!todayOnly && date != null) ps.setObject(2, date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AttendancePunch(rs.getInt(1), rs.getString(2),
                            rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("team attendance query failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** Device users not yet claimed by any web account - the "link device user" dropdown. */
    public synchronized Map<Integer, String> unlinkedUsers() throws IOException {
        Map<Integer, String> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT u.enroll_number, u.name FROM users u
                     LEFT JOIN accounts a ON a.enroll_number = u.enroll_number
                     WHERE a.id IS NULL ORDER BY u.enroll_number""")) {
            while (rs.next()) out.put(rs.getInt(1), rs.getString(2));
        } catch (SQLException e) {
            throw new IOException("unlinked users failed: " + e.getMessage(), e);
        }
        return out;
    }

    private List<AttendancePunch> punchRows(String where, Object param) throws IOException {
        List<AttendancePunch> out = new ArrayList<>();
        String sql = """
                SELECT p.enroll_number, COALESCE(u.name,''),
                       to_char(p.punch_time, 'YYYY-MM-DD HH24:MI:SS'), p.verify_label
                FROM punches p LEFT JOIN users u ON u.enroll_number = p.enroll_number
                """ + where + " ORDER BY p.punch_time DESC LIMIT 500";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (param != null) ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AttendancePunch(rs.getInt(1), rs.getString(2),
                            rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("attendance query failed: " + e.getMessage(), e);
        }
        return out;
    }

    public String describe() {
        return "PostgreSQL (" + url + ")";
    }
}
