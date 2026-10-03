package com.example.seatreservation.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.seatreservation.common.DomainException;
import com.example.seatreservation.shows.ShowController;
import com.example.seatreservation.shows.ShowService;

@WebMvcTest(controllers = {AuthController.class, ShowController.class},
        properties = "security.jwt.secret=test-secret-at-least-32-bytes-long-for-tests")
@Import({JwtConfiguration.class, SecurityConfiguration.class})
class AuthenticationSecurityTest {
    @Autowired
    private MockMvc mockMvc;

        @Autowired
        private JwtEncoder jwtEncoder;

    @MockBean
    private AuthService authService;

    @MockBean
    private ShowService showService;

    @Test
    void loginEndpointReturnsBearerTokenMetadata() throws Exception {
        when(authService.login("admin01", "example-password")).thenReturn(
                new AuthService.LoginResponse("signed-token", "Bearer", 3600, "admin01", "ADMIN"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin01\",\"password\":\"example-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").value("signed-token"))
                .andExpect(jsonPath("$.user_id").value("admin01"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void invalidCredentialsReturnUnauthorized() throws Exception {
        when(authService.login("user01", "incorrect-password")).thenThrow(
                new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Username or password is incorrect"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"user01\",\"password\":\"incorrect-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void expiredAndTamperedJwtTokensAreRejected() throws Exception {
        Instant now = Instant.now();
        String expiredToken = createToken(now.minusSeconds(7200), now.minusSeconds(3600));
        String validToken = createToken(now.minusSeconds(1), now.plusSeconds(3600));
        char finalCharacter = validToken.charAt(validToken.length() - 1);
        String tamperedToken = validToken.substring(0, validToken.length() - 1)
                + (finalCharacter == 'A' ? 'B' : 'A');

        for (String token : List.of(expiredToken, tamperedToken)) {
            MockHttpServletRequestBuilder request = get("/shows/{showId}", UUID.randomUUID())
                    .header("Authorization", "Bearer " + token);
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void showCreationRequiresAdminJwt() throws Exception {
        when(showService.create(any())).thenReturn(new ShowController.ShowResponse(
                UUID.randomUUID(), "protected-show", 100, 1,
                new ShowController.SeatCounts(1, 1, 0, 0),
                List.of(new ShowController.SeatView("A1", "available"))));
        String request = "{\"name\":\"protected-show\",\"seats\":[\"A1\"]}";

        mockMvc.perform(post("/shows").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/shows").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(post("/shows").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated());
    }

    private String createToken(Instant issuedAt, Instant expiresAt) {
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .subject("user01")
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt)
                        .claim("roles", "USER")
                        .build())).getTokenValue();
    }
}