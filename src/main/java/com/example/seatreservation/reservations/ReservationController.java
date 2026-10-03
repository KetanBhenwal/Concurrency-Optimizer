package com.example.seatreservation.reservations;

import com.example.seatreservation.auth.RequestAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/shows/{showId}/reserve")
public class ReservationController {
    private final ReservationService reservationService;
    private final String adminToken;

    public ReservationController(ReservationService reservationService,
                                 @Value("${reservation.admin-token}") String adminToken) {
        this.reservationService = reservationService;
        this.adminToken = adminToken;
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID showId,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReserveRequest request) {
        String userId = RequestAuth.requireUser(authorization, adminToken);
        ReservationService.ReservationResult result = reservationService.reserve(
                showId, userId, idempotencyKey, request.seats());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.response());
    }

    public record ReserveRequest(@NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 32) String> seats) {
    }

    public record ReservationResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("reservation_id") UUID reservationId,
            @com.fasterxml.jackson.annotation.JsonProperty("show_id") UUID showId,
            @com.fasterxml.jackson.annotation.JsonProperty("user_id") String userId,
            List<String> seats,
            @com.fasterxml.jackson.annotation.JsonProperty("amount_paise") long amountPaise,
            String status) {
    }

}