package com.example.seatreservation.auth;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResponse response = authService.login(request.username(), request.password());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LoginResponse(
                response.accessToken(), response.tokenType(), response.expiresIn(), response.userId(), response.role()));
    }

    public record LoginRequest(@NotBlank @Size(max = 32) String username,
                               @NotBlank @Size(max = 128) String password) {
    }

    public record LoginResponse(@JsonProperty("access_token") String accessToken,
                                @JsonProperty("token_type") String tokenType,
                                @JsonProperty("expires_in") long expiresIn,
                                @JsonProperty("user_id") String userId,
                                String role) {
    }
}