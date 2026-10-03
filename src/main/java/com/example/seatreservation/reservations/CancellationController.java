package com.example.seatreservation.reservations;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reservations")
public class CancellationController {
    private final ReservationService reservationService;

    public CancellationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/{reservationId}/cancel")
    public CancellationResponse cancel(@PathVariable UUID reservationId,
                                       @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return reservationService.cancel(reservationId, userId);
    }

    public record CancellationResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("reservation_id") UUID reservationId,
            String status) {
    }
}