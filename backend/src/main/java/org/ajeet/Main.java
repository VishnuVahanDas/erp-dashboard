package org.ajeet;

import org.ajeet.attendance.AttendanceStore;
import org.ajeet.auth.AuthStore;
import org.ajeet.config.Env;
import org.ajeet.leave.LeaveStore;
import org.ajeet.mbo.MboStore;
import org.ajeet.sync.SyncRequests;
import org.ajeet.web.ApiServer;

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
        new ApiServer(attendance, auth, sync, leave, mbo).start(port);
    }
}
