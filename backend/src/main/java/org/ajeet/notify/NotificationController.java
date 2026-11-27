package org.ajeet.notify;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.ajeet.attendance.PunchFixService;
import org.ajeet.auth.AuthService;
import org.ajeet.leave.LeaveStore;
import org.ajeet.mbo.MboService;
import org.ajeet.overtime.OvertimeStore;
import org.ajeet.payroll.PayrollStore;
import org.ajeet.web.CurrentAccount;
import org.ajeet.web.Forbidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongPredicate;

/** GET /api/notifications: what's waiting on you in the role you're viewing as (the bell). */
@RestController
class NotificationController {

    private final AuthService auth;
    private final LeaveStore leave;
    private final MboService mbo;
    private final PayrollStore payroll;
    private final PunchFixService punchFixes;
    private final OvertimeStore overtime;

    NotificationController(AuthService auth, LeaveStore leave, MboService mbo, PayrollStore payroll,
                           PunchFixService punchFixes, OvertimeStore overtime) {
        this.auth = auth;
        this.leave = leave;
        this.mbo = mbo;
        this.payroll = payroll;
        this.punchFixes = punchFixes;
        this.overtime = overtime;
    }

    /** One bell item. {@code as} is set only when the item belongs to another role than the one asked. */
    record Item(String kind, long id, String title, String detail, String tab, long at,
                @JsonInclude(JsonInclude.Include.NON_NULL) String as) {}

    record Notifications(int count, List<Item> items) {}

    @GetMapping("/api/notifications")
    Notifications notifications(@CurrentAccount AuthService.Account acct,
                                @RequestParam(required = false) String as) throws IOException {
        if (as == null || as.isBlank()) as = AuthService.EMPLOYEE;
        if (!AuthService.ROLES.contains(as) || !acct.has(as)) throw new Forbidden("you don't have the " + as + " role");
        List<Item> items = items(acct, as);
        return new Notifications(items.size(), items);
    }

    /**
     * Items waiting on this account, newest first. As a director: everything
     * they can decide; as a manager: their reportees'; as an employee: MBOs on
     * their turn plus their own requests decided in the last 7 days. Each item
     * names the tab the dashboard should open.
     */
    private List<Item> items(AuthService.Account acct, String as) throws IOException {
        List<Item> out = new ArrayList<>();
        long me = acct.id();
        long weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        if (as.equals(AuthService.EMPLOYEE)) {
            for (MboService.MboThread t : mbo.forAccount(me))
                if (t.status().equals("open") && "employee".equals(t.waitingOn()))
                    out.add(item("mbo", t.id(), "Objective waiting on you", t.title(), "mbo", t.updatedAt()));
            for (LeaveStore.LeaveRequest r : leave.forAccount(me))
                if (r.decidedAt() != null && r.decidedAt() > weekAgo && !r.status().equals("cancelled"))
                    out.add(item("leave-decided", r.id(), "Leave " + r.status(),
                            r.from() + (r.to().equals(r.from()) ? "" : " to " + r.to()) + " · by " + r.decidedBy(), "leave", r.decidedAt()));
            for (PunchFixService.PunchFix f : punchFixes.mine(me))
                if (f.decidedAt() != null && f.decidedAt() > weekAgo && !f.status().equals("cancelled"))
                    out.add(item("fix-decided", f.id(),
                            "Punch fix " + (f.status().equals("rejected") ? "declined" : f.status()),
                            f.day() + " · by " + f.decidedBy()
                                    + (f.decisionNote() == null || f.decisionNote().isBlank() ? "" : " - \"" + f.decisionNote() + "\""),
                            "log", f.decidedAt()));
            for (OvertimeStore.Claim c : overtime.mine(me))
                if (c.selfRequested() && c.decidedAt() != null && c.decidedAt() > weekAgo && !c.status().equals("cancelled"))
                    out.add(item("overtime-decided", c.id(),
                            "Overtime " + (c.status().equals("rejected") ? "declined" : c.status()),
                            c.day() + " · " + days(c.days()) + " · by " + c.decidedBy()
                                    + (c.decisionNote() == null || c.decisionNote().isBlank() ? "" : " - \"" + c.decisionNote() + "\""),
                            "payslips", c.decidedAt()));
        } else {
            boolean director = as.equals(AuthService.DIRECTOR);
            // a manager-view only covers their reportees, even for someone who is also a director
            Set<Long> team = new HashSet<>();
            if (!director) for (AuthService.TeamMember m : auth.team(me)) team.add(m.id());
            LongPredicate inScope = id -> id != me && (director || team.contains(id));
            for (LeaveStore.LeaveRequest r : director ? leave.all() : leave.forTeam(me))
                if (r.status().equals("pending") && inScope.test(r.accountId()))
                    out.add(item("leave", r.id(), "Leave request · " + r.name(),
                            r.category() + " · " + r.from() + (r.to().equals(r.from()) ? "" : " to " + r.to()), "leave", r.appliedAt()));
            for (PunchFixService.PunchFix f : director ? punchFixes.all() : punchFixes.forTeam(me))
                if (f.status().equals("pending") && inScope.test(f.accountId()))
                    out.add(item("fix", f.id(), "Punch fix · " + f.name(), f.day() + " · " + f.in() + "-" + f.out(), "today", f.createdAt()));
            for (OvertimeStore.Claim c : director ? overtime.all() : overtime.forTeam(me))
                if (c.status().equals("pending") && inScope.test(c.accountId()))
                    out.add(item("overtime", c.id(), "Overtime claim · " + c.name(),
                            c.day() + " · " + c.first() + "-" + c.last() + " · " + days(c.days()), "payroll", c.createdAt()));
            for (PayrollStore.Recommendation r : payroll.recommendations(acct))
                if (r.status().equals("pending") && inScope.test(r.accountId()))
                    out.add(item("recommendation", r.id(), "Payroll recommendation · " + r.name(),
                            "by " + r.recommendedBy() + " — set a salary to approve", "payroll", r.createdAt()));
            for (PayrollStore.Salary s : payroll.salaries(acct))
                if (s.status().equals("pending") && inScope.test(s.accountId()))
                    out.add(item("salary", s.id(), "Salary proposal · " + s.name(), "proposed by " + s.createdBy(), "payroll", s.createdAt()));
            for (MboService.MboThread t : director ? mbo.allExcept(me) : mbo.forTeam(me))
                if (t.status().equals("open") && "manager".equals(t.waitingOn())
                        && (t.managerId() == null || t.managerId() == me))
                    out.add(item("mbo", t.id(), "Objective waiting on you · " + t.employeeName(), t.title(), "mbo", t.updatedAt()));
        }
        // your own incomplete punches, in every role: a manager asks their own reporting manager,
        // so the item opens the employee Punch history where the fix is requested
        for (PunchFixService.MissingDay d : punchFixes.missingDays(me, acct.enrollNumber())) {
            LocalDate day = LocalDate.parse(d.day());
            String what = d.punches().size() == 1
                    ? "only one punch (" + d.punches().get(0) + ")"
                    : d.punches().size() + " punches, the last " + d.punches().get(d.punches().size() - 1);
            // NB: the server's time zone, as before (UTC in Docker); to be fixed after the move
            out.add(new Item("missed-punch", Long.parseLong(d.day().replace("-", "")), "You didn't punch properly",
                    day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
                            + ": " + what + " - ask your manager for a fix", "log",
                    day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    AuthService.EMPLOYEE));
        }
        out.sort(Comparator.comparingLong(Item::at).reversed());
        return out;
    }

    private static String days(double d) {
        return (d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d)) + " day overtime";
    }

    private static Item item(String kind, long id, String title, String detail, String tab, long at) {
        return new Item(kind, id, title, detail, tab, at, null);
    }
}
