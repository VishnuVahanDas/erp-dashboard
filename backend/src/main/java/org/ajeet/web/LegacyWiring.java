package org.ajeet.web;

import org.ajeet.attendance.AttendanceStore;
import org.ajeet.attendance.PunchFixStore;
import org.ajeet.auth.AuthStore;
import org.ajeet.leave.LeaveStore;
import org.ajeet.mbo.MboStore;
import org.ajeet.overtime.OvertimeStore;
import org.ajeet.payroll.PayrollStore;
import org.ajeet.web.routes.AttendanceRoutes;
import org.ajeet.web.routes.AuthRoutes;
import org.ajeet.web.routes.LeaveRoutes;
import org.ajeet.web.routes.MboRoutes;
import org.ajeet.web.routes.OvertimeRoutes;
import org.ajeet.web.routes.PayrollRoutes;
import org.ajeet.web.routes.PeopleRoutes;
import org.ajeet.web.routes.PunchFixRoutes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * What Main used to do for the stores not yet ported: open them (each creates its own tables, in
 * this order because of the foreign keys between them), seed the first director, and list the old
 * route modules in the order they were always matched. Each store is also a bean, so ported
 * controllers share the same instance. A ported area takes its store and routes out of here.
 */
@Configuration
class LegacyWiring {

    /** The old stores, opened together so their creation order (and so their tables') stays fixed. */
    record LegacyStores(AttendanceStore attendance, AuthStore auth, LeaveStore leave, MboStore mbo,
                        PayrollStore payroll, PunchFixStore punchFixes, OvertimeStore overtime) {}

    @Bean
    LegacyStores legacyStores(@Value("${spring.datasource.url}") String pgUrl,
                              @Value("${spring.datasource.username}") String pgUser,
                              @Value("${spring.datasource.password}") String pgPass,
                              @Value("${ADMIN_EMAIL:}") String adminEmail,
                              @Value("${ADMIN_PASSWORD:}") String adminPassword) throws Exception {
        AttendanceStore attendance = new AttendanceStore(pgUrl, pgUser, pgPass);
        System.out.println("Connected to PostgreSQL: " + pgUrl);

        AuthStore auth = new AuthStore(pgUrl, pgUser, pgPass);
        if (!adminEmail.isBlank()) {
            auth.seedDirector(adminEmail, adminPassword);
            System.out.println("Director account ready: " + adminEmail);
        } else {
            System.out.println("ADMIN_EMAIL not set -> no director seeded (set it in .env)");
        }

        // after AuthStore: leave_requests references accounts
        LeaveStore leave = new LeaveStore(pgUrl, pgUser, pgPass);
        MboStore mbo = new MboStore(pgUrl, pgUser, pgPass);
        // after LeaveStore: payroll reads leave_requests and punches
        PayrollStore payroll = new PayrollStore(pgUrl, pgUser, pgPass);
        // after AuthStore: punch_fixes references accounts
        PunchFixStore punchFixes = new PunchFixStore(pgUrl, pgUser, pgPass);
        // after PunchFixStore and PayrollStore: overtime reads punch_fixes and payroll_runs
        OvertimeStore overtime = new OvertimeStore(pgUrl, pgUser, pgPass);
        return new LegacyStores(attendance, auth, leave, mbo, payroll, punchFixes, overtime);
    }

    @Bean AttendanceStore attendanceStore(LegacyStores s) { return s.attendance(); }
    @Bean AuthStore authStore(LegacyStores s) { return s.auth(); }
    @Bean LeaveStore leaveStore(LegacyStores s) { return s.leave(); }
    @Bean MboStore mboStore(LegacyStores s) { return s.mbo(); }
    @Bean PayrollStore payrollStore(LegacyStores s) { return s.payroll(); }
    @Bean PunchFixStore punchFixStore(LegacyStores s) { return s.punchFixes(); }
    @Bean OvertimeStore overtimeStore(LegacyStores s) { return s.overtime(); }

    /** Old route modules, asked in this order (the order the routes were always matched in). */
    @Bean
    ApiServer legacyApi(LegacyStores s) {
        Http http = new Http(s.auth());
        return new ApiServer(http, List.of(
                new AuthRoutes(http, s.auth()),
                new AttendanceRoutes(http, s.attendance(), s.auth(), s.punchFixes()),
                new PeopleRoutes(http, s.auth(), s.attendance()),
                new LeaveRoutes(http, s.leave(), s.auth()),
                new MboRoutes(http, s.mbo()),
                new PunchFixRoutes(http, s.punchFixes()),
                new PayrollRoutes(http, s.payroll()),
                new OvertimeRoutes(http, s.overtime())));
    }
}
