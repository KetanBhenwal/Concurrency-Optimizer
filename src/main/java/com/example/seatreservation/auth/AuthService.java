package com.example.seatreservation.auth;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.seatreservation.common.DomainException;

@Service
public class AuthService {
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public AuthService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(String userId, String password) {
        List<Account> accounts = jdbcTemplate.query(
                "SELECT user_id, password_hash, role FROM auth_users WHERE user_id = ?",
                (result, rowNumber) -> new Account(result.getString("user_id"),
                        result.getString("password_hash"), result.getString("role")), userId);
        if (accounts.isEmpty() || !passwordEncoder.matches(password, accounts.getFirst().passwordHash())) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "Username or password is incorrect");
        }
        Account account = accounts.getFirst();
        JwtTokenService.IssuedToken token = jwtTokenService.issue(account.userId(), account.role());
        return new LoginResponse(token.accessToken(), "Bearer", token.expiresIn(), account.userId(), account.role());
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn,
                                String userId, String role) {
    }

    private record Account(String userId, String passwordHash, String role) {
    }
}