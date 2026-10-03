package com.example.seatreservation.reservations;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/shows/{showId}/reserve")
public class ReservationController {
    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID showId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReserveRequest request) {
        String userId = jwt.getSubject();
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