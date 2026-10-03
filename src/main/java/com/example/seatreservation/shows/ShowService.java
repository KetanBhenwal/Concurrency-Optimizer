package com.example.seatreservation.shows;

import com.example.seatreservation.common.DomainException;
import com.example.seatreservation.shows.ShowController.CreateShowRequest;
import com.example.seatreservation.shows.ShowController.SeatCounts;
import com.example.seatreservation.shows.ShowController.SeatView;
import com.example.seatreservation.shows.ShowController.ShowResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class ShowService {
    private final JdbcTemplate jdbcTemplate;

    public ShowService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ShowResponse create(CreateShowRequest request) {
        List<String> seats = request.seats().stream().distinct().sorted().toList();
        if (seats.size() != request.seats().size()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Seat numbers must be unique");
        }
        UUID showId = UUID.randomUUID();
        long pricePaise = request.pricePaise() == null ? 0 : request.pricePaise();
        int perUserLimit = request.perUserLimit() == null ? 4 : request.perUserLimit();
        jdbcTemplate.update("INSERT INTO shows (id, name, price_paise, per_user_limit) VALUES (?, ?, ?, ?)",
                showId, request.name().trim(), pricePaise, perUserLimit);
        jdbcTemplate.batchUpdate("INSERT INTO seats (id, show_id, seat_number) VALUES (?, ?, ?)", seats,
                seats.size(), (statement, seat) -> {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, showId);
                    statement.setString(3, seat);
                });
        return get(showId);
    }

    @Transactional(readOnly = true)
    public ShowResponse get(UUID showId) {
        ShowRow show;
        try {
            show = jdbcTemplate.queryForObject(
                    "SELECT id, name, price_paise, per_user_limit FROM shows WHERE id = ?",
                    (result, rowNumber) -> new ShowRow(result.getObject("id", UUID.class), result.getString("name"),
                            result.getLong("price_paise"), result.getInt("per_user_limit")), showId);
        } catch (org.springframework.dao.EmptyResultDataAccessException exception) {
            throw new DomainException(HttpStatus.NOT_FOUND, "SHOW_NOT_FOUND", "Show was not found");
        }
        List<SeatView> seats = jdbcTemplate.query(
                "SELECT seat_number, status FROM seats WHERE show_id = ? ORDER BY seat_number",
                (result, rowNumber) -> new SeatView(result.getString("seat_number"),
                        result.getString("status").toLowerCase()), showId);
        int available = (int) seats.stream().filter(seat -> seat.status().equals("available")).count();
        int confirmed = (int) seats.stream().filter(seat -> seat.status().equals("confirmed")).count();
        int held = 0;
        SeatCounts counts = new SeatCounts(seats.size(), available, held, confirmed);
        return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(), counts, seats);
    }

    private record ShowRow(UUID id, String name, long pricePaise, int perUserLimit) {
    }
}