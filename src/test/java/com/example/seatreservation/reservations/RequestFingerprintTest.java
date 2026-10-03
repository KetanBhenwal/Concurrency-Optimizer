package com.example.seatreservation.reservations;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RequestFingerprintTest {
    @Test
    void fingerprintIsIndependentOfSeatOrder() throws Exception {
        UUID showId = UUID.randomUUID();

        String forward = ReservationService.fingerprint(showId, List.of("A1", "A2"));
        String reverse = ReservationService.fingerprint(showId, List.of("A2", "A1"));

        assertEquals(forward, reverse);
        assertEquals(64, forward.length());
    }

    @Test
    void fingerprintIncludesShowIdentity() throws Exception {
        UUID showId = UUID.randomUUID();
        UUID otherShowId = UUID.randomUUID();

        String fingerprintForShow = ReservationService.fingerprint(showId, List.of("A1"));
        String fingerprintForOtherShow = ReservationService.fingerprint(otherShowId, List.of("A1"));

        assertNotEquals(fingerprintForShow, fingerprintForOtherShow);
    }
}