package org.ajeet.attendance;

import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Punches from the device (read only), with approved missed-punch fixes merged in where a view shows them. */
@Service
public class AttendanceService {

    /**
     * One punch row for the portal: name resolved via the users join.
     * addedBy is null for device punches; for an approved missed-punch fix
     * (see PunchFixService#approvedPunches) it names who approved it.
     */
    public record AttendancePunch(int enrollNumber, String name, String time, String verifyLabel, String addedBy) {}

    private final AttendanceRepository repo;

    AttendanceService(AttendanceRepository repo) {
        this.repo = repo;
    }

    /** Punches for a single device user, newest first (the employee's own view). */
    public List<AttendancePunch> punchesFor(int enrollNumber) {
        return repo.punchRows("WHERE p.enroll_number = ?", enrollNumber);
    }

    /**
     * One device user's punches in [from, toExclusive), newest first - a
     * person's month in the People details. Own range filter, so a busy
     * month isn't cut off by the 500-row cap of the log views.
     */
    public List<AttendancePunch> punchesBetween(int enrollNumber, LocalDate from, LocalDate toExclusive) {
        return repo.between(enrollNumber, from, toExclusive);
    }

    /** Everyone who punched today - the director dashboard card. */
    public List<AttendancePunch> punchesToday() {
        return repo.punchRows("WHERE p.punch_time::date = current_date", null);
    }

    /**
     * All punches, newest first - the director full log. With {@code date},
     * only that day (so any past day is reachable despite the 500-row cap).
     */
    public List<AttendancePunch> punchesAll(LocalDate date) {
        return date == null ? repo.punchRows("", null) : repo.punchRows("WHERE p.punch_time::date = ?", Date.valueOf(date));
    }

    /** Punches for a set of device users (a manager's team), newest first. */
    public List<AttendancePunch> punchesForTeam(Collection<Integer> enrolls, boolean todayOnly, LocalDate date) {
        if (enrolls.isEmpty()) return List.of();
        return repo.forTeam(enrolls, todayOnly, date);
    }

    /** Device users not yet claimed by any web account - the "link device user" dropdown. */
    public Map<Integer, String> unlinkedUsers() {
        return repo.unlinkedUsers();
    }

    /**
     * Device punches plus approved fixes, newest first. A fix time within a
     * minute of a device punch of the same person is left out (it repeats
     * the punch that was there), so the log shows only what the fix added.
     */
    public static List<AttendancePunch> withFixes(List<AttendancePunch> device, List<AttendancePunch> fixes) {
        if (fixes.isEmpty()) return device;
        Map<Integer, List<LocalDateTime>> seen = new HashMap<>();
        for (AttendancePunch p : device)
            seen.computeIfAbsent(p.enrollNumber(), k -> new ArrayList<>()).add(at(p));
        List<AttendancePunch> out = new ArrayList<>(device);
        for (AttendancePunch f : fixes) {
            LocalDateTime t = at(f);
            boolean dup = seen.getOrDefault(f.enrollNumber(), List.of()).stream()
                    .anyMatch(d -> Math.abs(Duration.between(d, t).toSeconds()) <= 60);
            if (!dup) out.add(f);
        }
        out.sort((a, b) -> b.time().compareTo(a.time()));
        return out;
    }

    private static LocalDateTime at(AttendancePunch p) {
        return LocalDateTime.parse(p.time().replace(' ', 'T'));
    }
}
