package com.example.seatreservation.reservations;

import com.example.seatreservation.auth.RequestAuth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/reservations")
public class CancellationController {
    private final ReservationService reservationService;
    private final String adminToken;

    public CancellationController(ReservationService reservationService,
                                  @Value("${reservation.admin-token}") String adminToken) {
        this.reservationService = reservationService;
        this.adminToken = adminToken;
    }

    @PostMapping("/{reservationId}/cancel")
    public CancellationResponse cancel(@PathVariable UUID reservationId,
                                       @RequestHeader("Authorization") String authorization) {
        String userId = RequestAuth.requireUser(authorization, adminToken);
        return reservationService.cancel(reservationId, userId);
    }

    public record CancellationResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("reservation_id") UUID reservationId,
            String status) {
    }
}