package com.example.seatreservation.reservations;

import com.example.seatreservation.common.DomainException;
import com.example.seatreservation.reservations.ReservationController.ReservationResponse;
import com.example.seatreservation.reservations.CancellationController.CancellationResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ReservationService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final MeterRegistry meterRegistry;

    public ReservationService(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbc,
                              MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = namedJdbc;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ReservationResult reserve(UUID showId, String userId, String idempotencyKey, List<String> requestedSeats) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "A valid Idempotency-Key header is required");
        }
        List<String> seats = requestedSeats.stream().distinct().sorted().toList();
        if (seats.size() != requestedSeats.size()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Seat numbers must be unique");
        }

        ShowRow show = lockUserShow(showId, userId);
        String fingerprint = fingerprint(showId, seats);
        ExistingReservation existing = findReservation(showId, userId, idempotencyKey);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                decline("idempotency_key_reused");
                throw new DomainException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "The idempotency key was already used with a different request");
            }
            meterRegistry.counter("reservations_idempotent_replays_total").increment();
                return new ReservationResult(new ReservationResponse(existing.id(), showId, userId,
                    reservationSeats(existing.id()), existing.amountPaise(), existing.status().toLowerCase()), true);
        }

        List<SeatRow> lockedSeats = namedJdbc.query(
                "SELECT id, seat_number, status FROM seats " +
                        "WHERE show_id = :showId AND seat_number IN (:seatNumbers) " +
                        "ORDER BY seat_number FOR UPDATE",
                new MapSqlParameterSource().addValue("showId", showId).addValue("seatNumbers", seats),
                (result, rowNumber) -> new SeatRow(result.getObject("id", UUID.class),
                        result.getString("seat_number"), result.getString("status")));
        if (lockedSeats.size() != seats.size()) {
            decline("seat_not_found");
            throw new DomainException(HttpStatus.NOT_FOUND, "SEAT_NOT_FOUND", "One or more seats do not exist");
        }
        if (lockedSeats.stream().anyMatch(seat -> !seat.status().equals("AVAILABLE"))) {
            decline("seat_taken");
            throw new DomainException(HttpStatus.CONFLICT, "SEAT_TAKEN",
                    "One or more requested seats are already unavailable");
        }

        Integer activeSeatCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seats s JOIN reservations r ON r.id = s.reservation_id " +
                        "WHERE s.show_id = ? AND r.user_id = ? AND r.status = 'CONFIRMED'",
                Integer.class, showId, userId);
        if (activeSeatCount + seats.size() > show.perUserLimit()) {
            decline("per_user_limit");
            throw new DomainException(HttpStatus.CONFLICT, "PER_USER_LIMIT_EXCEEDED",
                    "The per-user seat limit would be exceeded");
        }

        long amountPaise;
        try {
            amountPaise = Math.multiplyExact(show.pricePaise(), seats.size());
        } catch (ArithmeticException exception) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "AMOUNT_OVERFLOW",
                    "The requested reservation amount exceeds the supported range");
        }
        UUID reservationId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO reservations " +
                        "(id, show_id, user_id, status, amount_paise, idempotency_key, request_fingerprint) " +
                        "VALUES (?, ?, ?, 'CONFIRMED', ?, ?, ?)",
                reservationId, showId, userId, amountPaise, idempotencyKey, fingerprint);
        for (SeatRow seat : lockedSeats) {
            jdbcTemplate.update("UPDATE seats SET status = 'CONFIRMED', reservation_id = ? WHERE id = ?",
                    reservationId, seat.id());
            jdbcTemplate.update("INSERT INTO reservation_seats (reservation_id, seat_id) VALUES (?, ?)",
                reservationId, seat.id());
        }
        meterRegistry.counter("reservations_confirmed_total").increment();
        return new ReservationResult(toResponse(reservationId, showId, userId, seats, amountPaise), false);
    }

    @Transactional
    public CancellationResponse cancel(UUID reservationId, String userId) {
        List<LockedReservation> reservationRows = jdbcTemplate.query(
                "SELECT show_id, user_id, status FROM reservations WHERE id = ? FOR UPDATE",
                (result, rowNumber) -> new LockedReservation(result.getObject("show_id", UUID.class),
                        result.getString("user_id"), result.getString("status")), reservationId);
        if (reservationRows.isEmpty()) {
            throw new DomainException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "Reservation was not found");
        }
        LockedReservation reservation = reservationRows.getFirst();
        if (!reservation.userId().equals(userId)) {
            throw new DomainException(HttpStatus.FORBIDDEN, "RESERVATION_NOT_OWNED",
                    "Only the reservation owner may cancel it");
        }
        if (reservation.status().equals("CANCELLED")) {
            return new CancellationResponse(reservationId, "cancelled");
        }

        lockUserShow(reservation.showId(), userId);
        jdbcTemplate.query("SELECT s.id FROM seats s JOIN reservation_seats rs ON rs.seat_id = s.id " +
                        "WHERE rs.reservation_id = ? ORDER BY s.seat_number FOR UPDATE",
                (result, rowNumber) -> result.getObject("id", UUID.class), reservationId);
        jdbcTemplate.update("UPDATE seats SET status = 'AVAILABLE', reservation_id = NULL " +
                "WHERE reservation_id = ?", reservationId);
        jdbcTemplate.update("UPDATE reservations SET status = 'CANCELLED' WHERE id = ?", reservationId);
        meterRegistry.counter("reservations_cancelled_total").increment();
        return new CancellationResponse(reservationId, "cancelled");
    }

    private ShowRow lockUserShow(UUID showId, String userId) {
        try {
            jdbcTemplate.queryForObject("SELECT id FROM shows WHERE id = ?", UUID.class, showId);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw new DomainException(HttpStatus.NOT_FOUND, "SHOW_NOT_FOUND", "Show was not found");
        }
        jdbcTemplate.update("INSERT INTO user_show_locks (show_id, user_id) VALUES (?, ?) " +
                "ON CONFLICT (show_id, user_id) DO NOTHING", showId, userId);
        jdbcTemplate.queryForObject("SELECT user_id FROM user_show_locks " +
                "WHERE show_id = ? AND user_id = ? FOR UPDATE", String.class, showId, userId);
        return jdbcTemplate.queryForObject("SELECT price_paise, per_user_limit FROM shows WHERE id = ?",
                (result, rowNumber) -> new ShowRow(result.getLong("price_paise"), result.getInt("per_user_limit")),
                showId);
    }

    private ExistingReservation findReservation(UUID showId, String userId, String idempotencyKey) {
        List<ExistingReservation> reservations = jdbcTemplate.query(
                "SELECT id, request_fingerprint, amount_paise, status FROM reservations " +
                        "WHERE show_id = ? AND user_id = ? AND idempotency_key = ?",
                (result, rowNumber) -> new ExistingReservation(result.getObject("id", UUID.class),
                        result.getString("request_fingerprint").trim(), result.getLong("amount_paise"),
                        result.getString("status")),
                showId, userId, idempotencyKey);
        return reservations.isEmpty() ? null : reservations.getFirst();
    }

    private List<String> reservationSeats(UUID reservationId) {
        return jdbcTemplate.query("SELECT s.seat_number FROM seats s JOIN reservation_seats rs ON rs.seat_id = s.id " +
                "WHERE rs.reservation_id = ? ORDER BY s.seat_number",
            (result, rowNumber) -> result.getString("seat_number"), reservationId);
    }

    private ReservationResponse toResponse(UUID reservationId, UUID showId, String userId,
                                            List<String> seats, long amountPaise) {
        return new ReservationResponse(reservationId, showId, userId, seats, amountPaise, "confirmed");
    }

    static String fingerprint(UUID showId, List<String> seats) {
        String canonical = showId + "\n" + String.join("\n", seats.stream().sorted().toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void decline(String reason) {
        meterRegistry.counter("reservations_declined_total", "reason", reason).increment();
    }

    public record ReservationResult(ReservationResponse response, boolean replayed) {
    }

    private record ShowRow(long pricePaise, int perUserLimit) {
    }

    private record SeatRow(UUID id, String seatNumber, String status) {
    }

    private record ExistingReservation(UUID id, String fingerprint, long amountPaise, String status) {
    }

    private record LockedReservation(UUID showId, String userId, String status) {
    }
}