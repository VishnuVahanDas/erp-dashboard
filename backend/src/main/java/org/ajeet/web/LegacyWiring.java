package org.ajeet.web;

import org.ajeet.attendance.AttendanceStore;
import org.ajeet.attendance.PunchFixStore;
import org.ajeet.auth.AuthStore;
import org.ajeet.leave.LeaveStore;
import org.ajeet.mbo.MboStore;
import org.ajeet.overtime.OvertimeStore;
import org.ajeet.payroll.PayrollStore;
import org.ajeet.sync.SyncRequests;
import org.ajeet.web.routes.AttendanceRoutes;
import org.ajeet.web.routes.AuthRoutes;
import org.ajeet.web.routes.LeaveRoutes;
import org.ajeet.web.routes.MboRoutes;
import org.ajeet.web.routes.NotificationRoutes;
import org.ajeet.web.routes.OvertimeRoutes;
import org.ajeet.web.routes.PayrollRoutes;
import org.ajeet.web.routes.PeopleRoutes;
import org.ajeet.web.routes.PunchFixRoutes;
import org.ajeet.web.routes.SyncRoutes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * What Main used to do: open the stores (each creates its own tables, in this order because of the
 * foreign keys between them), seed the first director and list the route modules in the order they
 * were always matched. Each ported area takes its store and routes out of here.
 */
@Configuration
class LegacyWiring {

    @Bean
    ApiServer legacyApi(@Value("${spring.datasource.url}") String pgUrl,
                        @Value("${spring.datasource.username}") String pgUser,
                        @Value("${spring.datasource.password}") String pgPass,
                        @Value("${ADMIN_EMAIL:}") String adminEmail,
                        @Value("${ADMIN_PASSWORD:}") String adminPassword) throws Exception {
        AttendanceStore attendance = new AttendanceStore(pgUrl, pgUser, pgPass);
        System.out.println("Connected to PostgreSQL: " + pgUrl + " (" + attendance.describe() + ")");

        AuthStore auth = new AuthStore(pgUrl, pgUser, pgPass);
        if (!adminEmail.isBlank()) {
            auth.seedDirector(adminEmail, adminPassword);
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
        return new ApiServer(http, List.of(
                new SyncRoutes(http, attendance, sync),
                new AuthRoutes(http, auth),
                new AttendanceRoutes(http, attendance, auth, punchFixes),
                new PeopleRoutes(http, auth, attendance),
                new LeaveRoutes(http, leave, auth),
                new MboRoutes(http, mbo),
                new NotificationRoutes(http, auth, leave, mbo, payroll, punchFixes, overtime),
                new PunchFixRoutes(http, punchFixes),
                new PayrollRoutes(http, payroll),
                new OvertimeRoutes(http, overtime)));
    }
}
