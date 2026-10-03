package com.example.seatreservation.auth;

import com.example.seatreservation.common.DomainException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

public final class RequestAuth {
    private static final Pattern USER_TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private RequestAuth() {
    }

    public static String requireUser(String authorization, String adminToken) {
        String token = bearerToken(authorization);
        if (MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), adminToken.getBytes(StandardCharsets.UTF_8))) {
            throw new DomainException(HttpStatus.FORBIDDEN, "USER_TOKEN_REQUIRED", "A user token is required");
        }
        if (!USER_TOKEN.matcher(token).matches()) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A valid bearer token is required");
        }
        return token;
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A bearer token is required");
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A bearer token is required");
        }
        return token;
    }
}