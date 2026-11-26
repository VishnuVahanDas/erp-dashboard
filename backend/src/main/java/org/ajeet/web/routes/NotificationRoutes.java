package org.ajeet.web.routes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.ajeet.attendance.PunchFixStore;
import org.ajeet.auth.AuthStore;
import org.ajeet.leave.LeaveStore;
import org.ajeet.mbo.MboStore;
import org.ajeet.overtime.OvertimeStore;
import org.ajeet.payroll.PayrollStore;
import org.ajeet.web.Http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** /notifications: what's waiting on you in the role you're viewing as (the bell). */
public class NotificationRoutes implements Routes {

    private final Http http;
    private final ObjectMapper mapper;
    private final AuthStore auth;
    private final LeaveStore leave;
    private final MboStore mbo;
    private final PayrollStore payroll;
    private final PunchFixStore punchFixes;
    private final OvertimeStore overtime;

    public NotificationRoutes(Http http, AuthStore auth, LeaveStore leave, MboStore mbo,
                              PayrollStore payroll, PunchFixStore punchFixes, OvertimeStore overtime) {
        this.http = http;
        this.mapper = http.mapper();
        this.auth = auth;
        this.leave = leave;
        this.mbo = mbo;
        this.payroll = payroll;
        this.punchFixes = punchFixes;
        this.overtime = overtime;
    }

    @Override
    public boolean handle(HttpExchange ex, String method, String path) throws Exception {
        if (method.equals("GET") && path.equals("/notifications")) {
            AuthStore.Account acct = http.requireAccount(ex);
            String as = http.query(ex, "as");
            if (as == null || as.isBlank()) as = AuthStore.EMPLOYEE;
            if (!AuthStore.ROLES.contains(as) || !acct.has(as)) throw new Http.ForbiddenException("you don't have the " + as + " role");
            ArrayNode items = notifications(acct, as);
            http.send(ex, 200, mapper.createObjectNode().put("count", items.size()).set("items", items));
        } else {
            return false;
        }
        return true;
    }

    /**
     * Items waiting on this account, newest first. As a director: everything
     * they can decide; as a manager: their reportees'; as an employee: MBOs on
     * their turn plus their own requests decided in the last 7 days. Each item
     * names the tab the dashboard should open.
     */
    private ArrayNode notifications(AuthStore.Account acct, String as) throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        long me = acct.id();
        long weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        if (as.equals(AuthStore.EMPLOYEE)) {
            for (MboStore.MboThread t : mbo.forAccount(me))
                if (t.status().equals("open") && "employee".equals(t.waitingOn()))
                    out.add(item("mbo", t.id(), "MBO waiting on you", t.title(), "mbo", t.updatedAt()));
            for (LeaveStore.LeaveRequest r : leave.forAccount(me))
                if (r.decidedAt() != null && r.decidedAt() > weekAgo && !r.status().equals("cancelled"))
                    out.add(item("leave-decided", r.id(), "Leave " + r.status(),
                            r.from() + (r.to().equals(r.from()) ? "" : " to " + r.to()) + " · by " + r.decidedBy(), "leave", r.decidedAt()));
            for (PunchFixStore.PunchFix f : punchFixes.mine(me))
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
            boolean director = as.equals(AuthStore.DIRECTOR);
            // a manager-view only covers their reportees, even for someone who is also a director
            Set<Long> team = new java.util.HashSet<>();
            if (!director) for (AuthStore.TeamMember m : auth.team(me)) team.add(m.id());
            java.util.function.LongPredicate inScope = id -> id != me && (director || team.contains(id));
            for (LeaveStore.LeaveRequest r : director ? leave.all() : leave.forTeam(me))
                if (r.status().equals("pending") && inScope.test(r.accountId()))
                    out.add(item("leave", r.id(), "Leave request · " + r.name(),
                            r.category() + " · " + r.from() + (r.to().equals(r.from()) ? "" : " to " + r.to()), "leave", r.appliedAt()));
            for (PunchFixStore.PunchFix f : director ? punchFixes.all() : punchFixes.forTeam(me))
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
            for (MboStore.MboThread t : director ? mbo.allExcept(me) : mbo.forTeam(me))
                if (t.status().equals("open") && "manager".equals(t.waitingOn())
                        && (t.managerId() == null || t.managerId() == me))
                    out.add(item("mbo", t.id(), "MBO waiting on you · " + t.employeeName(), t.title(), "mbo", t.updatedAt()));
        }
        // your own incomplete punches, in every role: a manager asks their own reporting manager,
        // so the item opens the employee Punch history where the fix is requested
        for (PunchFixStore.MissingDay d : punchFixes.missingDays(me, acct.enrollNumber())) {
            java.time.LocalDate day = java.time.LocalDate.parse(d.day());
            String what = d.punches().size() == 1
                    ? "only one punch (" + d.punches().get(0) + ")"
                    : d.punches().size() + " punches, the last " + d.punches().get(d.punches().size() - 1);
            out.add(item("missed-punch", Long.parseLong(d.day().replace("-", "")), "You didn't punch properly",
                    day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))
                            + ": " + what + " - ask your manager for a fix", "log",
                    day.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                    .put("as", AuthStore.EMPLOYEE));
        }
        out.sort((a, b) -> Long.compare(b.path("at").asLong(), a.path("at").asLong()));
        ArrayNode arr = mapper.createArrayNode();
        out.forEach(arr::add);
        return arr;
    }

    private static String days(double d) {
        return (d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d)) + " day overtime";
    }

    private ObjectNode item(String kind, long id, String title, String detail, String tab, long at) {
        return mapper.createObjectNode().put("kind", kind).put("id", id).put("title", title)
                .put("detail", detail).put("tab", tab).put("at", at);
    }
}
