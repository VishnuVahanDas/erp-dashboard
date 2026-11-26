package org.ajeet;

import org.ajeet.attendance.AttendanceStore;
import org.ajeet.attendance.PunchFixStore;
import org.ajeet.auth.AuthStore;
import org.ajeet.config.Env;
import org.ajeet.leave.LeaveStore;
import org.ajeet.mbo.MboStore;
import org.ajeet.overtime.OvertimeStore;
import org.ajeet.payroll.PayrollStore;
import org.ajeet.sync.SyncRequests;
import org.ajeet.web.ApiServer;
import org.ajeet.web.Http;
import org.ajeet.web.routes.AttendanceRoutes;
import org.ajeet.web.routes.AuthRoutes;
import org.ajeet.web.routes.LeaveRoutes;
import org.ajeet.web.routes.MboRoutes;
import org.ajeet.web.routes.NotificationRoutes;
import org.ajeet.web.routes.OvertimeRoutes;
import org.ajeet.web.routes.PayrollRoutes;
import org.ajeet.web.routes.PeopleRoutes;
import org.ajeet.web.routes.PunchFixRoutes;
import org.ajeet.web.routes.Routes;
import org.ajeet.web.routes.SyncRoutes;

import java.util.List;

/** Web dashboard backend: REST API for the React UI, backed by PostgreSQL. */
public class Main {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(Env.get("PORT", "8080"));
        String pgUrl = Env.get("PG_URL", null);
        if (pgUrl == null || pgUrl.isBlank())
            throw new IllegalStateException("PG_URL is required (see .env.example)");
        String pgUser = Env.get("PG_USER", "postgres");
        String pgPass = Env.get("PG_PASSWORD", "");

        AttendanceStore attendance = new AttendanceStore(pgUrl, pgUser, pgPass);
        System.out.println("Connected to PostgreSQL: " + pgUrl);

        AuthStore auth = new AuthStore(pgUrl, pgUser, pgPass);
        String adminEmail = Env.get("ADMIN_EMAIL", null);
        if (adminEmail != null && !adminEmail.isBlank()) {
            auth.seedDirector(adminEmail, Env.get("ADMIN_PASSWORD", ""));
            System.out.println("Director account ready: " + adminEmail);
        } else {
            System.out.println("ADMIN_EMAIL not set -> no director seeded (set it in .env)");
        }

        SyncRequests sync = new SyncRequests(pgUrl, pgUser, pgPass);
        // after AuthStore: leave_requests references accounts
        LeaveStore leave = new LeaveStore(pgUrl, pgUser, pgPass);
        MboStore mbo = new MboStore(pgUrl, pgUser, pgPass);
        // after LeaveStore: payroll reads leave_requests and punches
        PayrollStore payroll = new PayrollStore(pgUrl, pgUser, pgPass);
        // after AuthStore: punch_fixes references accounts
        PunchFixStore punchFixes = new PunchFixStore(pgUrl, pgUser, pgPass);
        // after PunchFixStore and PayrollStore: overtime reads punch_fixes and payroll_runs
        OvertimeStore overtime = new OvertimeStore(pgUrl, pgUser, pgPass);

        // route modules, asked in this order (the order the routes were always matched in)
        Http http = new Http(auth);
        List<Routes> routes = List.of(
                new SyncRoutes(http, attendance, sync),
                new AuthRoutes(http, auth),
                new AttendanceRoutes(http, attendance, auth, punchFixes),
                new PeopleRoutes(http, auth, attendance),
                new LeaveRoutes(http, leave, auth),
                new MboRoutes(http, mbo),
                new NotificationRoutes(http, auth, leave, mbo, payroll, punchFixes, overtime),
                new PunchFixRoutes(http, punchFixes),
                new PayrollRoutes(http, payroll),
                new OvertimeRoutes(http, overtime));
        new ApiServer(http, routes, attendance.describe()).start(port);
    }
}
