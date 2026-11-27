package org.ajeet.web;

import org.ajeet.attendance.AttendanceService;
import org.ajeet.auth.AuthService;
import org.ajeet.leave.LeaveService;
import org.ajeet.overtime.OvertimeStore;
import org.ajeet.payroll.PayrollService;
import org.ajeet.web.routes.OvertimeRoutes;
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
    record LegacyStores(OvertimeStore overtime) {}

    /**
     * After attendance (users / punches), accounts (AuthService, which also seeds the first
     * director) and leave: the old stores' tables reference accounts, payroll reads leave.
     */
    @Bean
    LegacyStores legacyStores(AttendanceService attendance, AuthService auth, LeaveService leave, PayrollService payroll,
                              @Value("${spring.datasource.url}") String pgUrl,
                              @Value("${spring.datasource.username}") String pgUser,
                              @Value("${spring.datasource.password}") String pgPass) throws Exception {
        System.out.println("Connected to PostgreSQL: " + pgUrl);
        // after PayrollService: overtime reads payroll_runs (and punch_fixes, created by PunchFixRepository)
        OvertimeStore overtime = new OvertimeStore(pgUrl, pgUser, pgPass);
        return new LegacyStores(overtime);
    }

    @Bean OvertimeStore overtimeStore(LegacyStores s) { return s.overtime(); }

    /** Old route modules, asked in this order (the order the routes were always matched in). */
    @Bean
    ApiServer legacyApi(LegacyStores s, AuthService auth) {
        Http http = new Http(auth);
        return new ApiServer(http, List.of(
                new OvertimeRoutes(http, s.overtime())));
    }
}
