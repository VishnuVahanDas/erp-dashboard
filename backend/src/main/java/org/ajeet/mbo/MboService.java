package org.ajeet.mbo;

import org.ajeet.auth.AuthStore;
import org.ajeet.web.Forbidden;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * MBOs: a recorded back-and-forth between an employee and their manager side
 * about an objective, so the history can back up promotion decisions.
 *
 * A thread is between one employee and one named manager (manager_id). A
 * manager or director starting a thread is that manager; an employee picks
 * one of their reporting managers or any director. The manager side can be
 * answered by that manager or by any director. (Threads from before
 * manager_id existed have it null: any reporting manager or director answers.)
 * Either side may start a thread with a proposal; after that the turn
 * alternates. Whoever's turn it is can:
 *   - acknowledge: agree with the latest proposal -> thread is closed as acknowledged
 *   - deny:        refuse it (reason required)     -> thread is closed as denied
 *   - suggest:     counter with changes            -> turn passes to the other side
 * Nobody answers their own side's proposal. Entries are never edited or
 * deleted - the trail is the point.
 *
 * The UI calls a thread an "objective" and its back-and-forth a "conversation";
 * the API paths and tables keep the mbo name.
 *
 * MBOs are weekly (week_start = that Monday; a week belongs to the month its
 * Monday is in, so a year is 12 months of 4-5 weeks). Each employee has up to
 * MAX_PER_WEEK per manager per week (two managers -> twice that), and only the
 * current week can be started. A thread still open when its week is over is
 * closed as expired (no entry is added or changed). Threads from before weekly
 * MBOs have week_start null: they're "earlier objectives" and never expire.
 */
@Service
public class MboService {

    public static final String EMPLOYEE = "employee";
    public static final String MANAGER = "manager";
    /** Objectives per employee per manager per week, whatever their status. */
    public static final int MAX_PER_WEEK = 6;
    private static final int MAX_TITLE = 200;
    private static final int MAX_BODY = 4000;

    /** One message in a thread. kind: proposal | suggest | acknowledge | deny. side: employee | manager. */
    public record Entry(long id, Long authorId, String authorName, String side, String kind,
                        String body, long createdAt) {}

    /** Someone an employee can address an MBO to. relation: manager (reports to them) | director. */
    public record Counterpart(long id, String name, String email, String relation) {}

    /**
     * A thread as the portal shows it. status: open | acknowledged | denied | expired.
     * waitingOn is the side whose turn it is (null once closed). Times are epoch millis.
     * weekStart is the Monday of its week (yyyy-MM-dd), null for an earlier MBO.
     */
    public record MboThread(long id, long employeeId, String employeeName, String employeeEmail,
                            Long managerId, String managerName, String title, String status, String waitingOn, String startedBy,
                            long createdAt, long updatedAt, String weekStart, List<Entry> entries) {}

    private final MboRepository repo;

    MboService(MboRepository repo) {
        this.repo = repo;
    }

    // ----------------------------------------------------------------- reads

    public List<MboThread> forAccount(long accountId) {
        repo.expireEnded();
        return repo.threads("t.employee_id = ?", accountId);
    }

    /** Threads of everyone who reports to this manager (never the manager's own). */
    public List<MboThread> forTeam(long managerId) {
        repo.expireEnded();
        return repo.threads("""
                t.employee_id <> ? AND t.employee_id IN
                    (SELECT employee_id FROM reporting_lines WHERE manager_id = ?)""", managerId, managerId);
    }

    /** Everyone's threads except the director's own (those live in their employee view). */
    public List<MboThread> allExcept(long accountId) {
        repo.expireEnded();
        return repo.threads("t.employee_id <> ?", accountId);
    }

    /** Who this account may address an MBO to: their reporting managers, then active directors. */
    public List<Counterpart> counterparts(long accountId) {
        return repo.counterparts(accountId);
    }

    // ---------------------------------------------------------------- writes

    /**
     * Start this week's thread. employeeId null or == who.id -> the employee raises it
     * themselves and must name managerId (one of {@link #counterparts});
     * otherwise who must be on that employee's manager side and is the manager.
     * Up to MAX_PER_WEEK per employee and manager per week (expired ones count too);
     * a lock on the pair for the transaction keeps two requests from both taking the last place.
     */
    @Transactional(rollbackFor = Exception.class)
    public long start(AuthStore.Account who, Long employeeId, Long managerId, String title, String body) {
        long emp = employeeId == null || employeeId == 0 ? who.id() : employeeId;
        String side = emp == who.id() ? EMPLOYEE : MANAGER;
        long mgr;
        if (side.equals(MANAGER)) {
            if (!canManage(who, emp))
                throw new Forbidden("only their reporting manager or a director can start an objective for this person");
            mgr = who.id();
        } else {
            if (managerId == null || managerId == 0) throw new IllegalArgumentException("pick the manager to send it to");
            mgr = managerId;
            if (counterparts(who.id()).stream().noneMatch(c -> c.id() == mgr))
                throw new IllegalArgumentException("you can only send an objective to one of your reporting managers or a director");
        }
        String t = clean(title);
        if (t.isEmpty()) throw new IllegalArgumentException("please give it a title");
        if (t.length() > MAX_TITLE) throw new IllegalArgumentException("title is too long (max " + MAX_TITLE + " characters)");
        String b = body(body, true);
        repo.lockPair(emp, mgr);
        repo.expireEnded();
        requireRoomThisWeek(emp, mgr, side);

        long id = repo.insertThread(emp, mgr, t, other(side), side);
        repo.addEntry(id, who.id(), side, "proposal", b);
        return id;
    }

    /** action: acknowledge | deny | suggest. Only the side whose turn it is may answer. */
    @Transactional(rollbackFor = Exception.class)
    public void respond(AuthStore.Account who, long threadId, String action, String body) {
        repo.expireEnded();
        MboThread t = require(threadId);
        String side;
        if (t.employeeId() == who.id()) side = EMPLOYEE;
        else if (answersFor(who, t)) side = MANAGER;
        else if (canManage(who, t.employeeId()))
            throw new Forbidden("this objective is with " + t.managerName() + " - only they or a director can respond");
        else throw new Forbidden("you're not part of this conversation");
        if (t.status().equals("expired")) throw new IllegalArgumentException("this week is over - the objective expired");
        if (!t.status().equals("open")) throw new IllegalArgumentException("this objective is already " + t.status());
        if (!side.equals(t.waitingOn()))
            throw new IllegalArgumentException("waiting for the " + t.waitingOn() + " to respond - you can't answer your own side's proposal");

        String b = body(body, !action.equals("acknowledge"));
        String newStatus = switch (action) {
            case "acknowledge" -> "acknowledged";
            case "deny" -> "denied";
            case "suggest" -> "open";
            default -> throw new IllegalArgumentException("action must be acknowledge, deny or suggest");
        };
        if (repo.answer(threadId, newStatus, newStatus.equals("open") ? other(side) : null, side) == 0)
            throw new IllegalArgumentException("someone else just responded - reload to see it");
        repo.addEntry(threadId, who.id(), side, action, b);
    }

    // --------------------------------------------------------------- helpers

    /**
     * Refuses a new thread once this employee and manager already have MAX_PER_WEEK this week,
     * naming the other person and the Monday the next one can be started.
     */
    private void requireRoomThisWeek(long emp, long mgr, String side) {
        MboRepository.Room room = repo.roomThisWeek(emp, mgr, side.equals(EMPLOYEE) ? mgr : emp);
        if (room.count() < MAX_PER_WEEK) return;
        String name = room.otherName() != null ? room.otherName()
                : side.equals(EMPLOYEE) ? "that manager" : "that person";
        throw new IllegalArgumentException("there are already " + MAX_PER_WEEK + " objectives this week with "
                + name + " - the next one can be started on " + room.nextMonday());
    }

    /** May answer on the manager side: the named manager, or any director. */
    private boolean answersFor(AuthStore.Account who, MboThread t) {
        if (who.id() == t.employeeId()) return false;
        if (t.managerId() == null) return canManage(who, t.employeeId()); // pre-manager_id thread
        return who.id() == t.managerId() || who.has(AuthStore.DIRECTOR);
    }

    private boolean canManage(AuthStore.Account who, long employeeId) {
        if (who.id() == employeeId) return false;
        if (who.has(AuthStore.DIRECTOR)) return true;
        if (!who.has(AuthStore.MANAGER)) return false;
        return repo.reportsTo(employeeId, who.id());
    }

    private MboThread require(long id) {
        List<MboThread> list = repo.threads("t.id = ?", id);
        if (list.isEmpty()) throw new IllegalArgumentException("objective not found");
        return list.get(0);
    }

    private static String body(String s, boolean required) {
        String b = clean(s);
        if (required && b.isEmpty()) throw new IllegalArgumentException("please write something");
        if (b.length() > MAX_BODY) throw new IllegalArgumentException("message is too long (max " + MAX_BODY + " characters)");
        return b;
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }

    private static String other(String side) { return side.equals(EMPLOYEE) ? MANAGER : EMPLOYEE; }
}
