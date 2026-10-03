package com.example.seatreservation.auth;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DemoAccountSeeder implements ApplicationRunner {
    private static final List<DemoAccount> ACCOUNTS = List.of(
            new DemoAccount("admin01", "SeatAdmin-2026!01", "ADMIN"),
            new DemoAccount("admin02", "SeatAdmin-2026!02", "ADMIN"),
            new DemoAccount("user01", "SeatUser-2026!01", "USER"),
            new DemoAccount("user02", "SeatUser-2026!02", "USER"),
            new DemoAccount("user03", "SeatUser-2026!03", "USER"),
            new DemoAccount("user04", "SeatUser-2026!04", "USER"),
            new DemoAccount("user05", "SeatUser-2026!05", "USER"),
            new DemoAccount("user06", "SeatUser-2026!06", "USER"),
            new DemoAccount("user07", "SeatUser-2026!07", "USER"),
            new DemoAccount("user08", "SeatUser-2026!08", "USER"),
            new DemoAccount("user09", "SeatUser-2026!09", "USER"),
            new DemoAccount("user10", "SeatUser-2026!10", "USER"));

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public DemoAccountSeeder(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (DemoAccount account : ACCOUNTS) {
            jdbcTemplate.update("""
                    INSERT INTO auth_users (user_id, password_hash, role)
                    VALUES (?, ?, ?)
                    ON CONFLICT (user_id) DO NOTHING
                    """, account.userId(), passwordEncoder.encode(account.password()), account.role());
        }
    }

    private record DemoAccount(String userId, String password, String role) {
    }
}