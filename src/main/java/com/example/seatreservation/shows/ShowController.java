package com.example.seatreservation.shows;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/shows")
public class ShowController {
    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowResponse create(@Valid @RequestBody CreateShowRequest request) {
        return showService.create(request);
    }

    @GetMapping("/{showId}")
    public ShowResponse get(@PathVariable UUID showId) {
        return showService.get(showId);
    }

    public record CreateShowRequest(
            @NotBlank @Size(max = 120) String name,
            @NotEmpty @Size(max = 5000) List<@NotBlank @Size(max = 32) String> seats,
            @com.fasterxml.jackson.annotation.JsonProperty("price_paise")
            @com.fasterxml.jackson.annotation.JsonAlias("pricePaise") @PositiveOrZero Long pricePaise,
            @com.fasterxml.jackson.annotation.JsonProperty("per_user_limit")
            @com.fasterxml.jackson.annotation.JsonAlias("perUserLimit") @Positive Integer perUserLimit) {
    }

    public record ShowResponse(
            UUID id,
            String name,
            @com.fasterxml.jackson.annotation.JsonProperty("price_paise") long pricePaise,
            @com.fasterxml.jackson.annotation.JsonProperty("per_user_limit") int perUserLimit,
            SeatCounts counts,
            List<SeatView> seats) {
    }

    public record SeatCounts(int total, int available, int held, int confirmed) {
    }

    public record SeatView(String seat, String status) {
    }
}