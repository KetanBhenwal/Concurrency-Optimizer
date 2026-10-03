package com.example.seatreservation.metrics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

@Component
public class SeatAvailabilityMetrics implements MeterBinder {
    private final JdbcTemplate jdbcTemplate;

    public SeatAvailabilityMetrics(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("seats_available", this, SeatAvailabilityMetrics::availableSeats)
                .description("Number of seats currently available across all shows")
                .register(registry);
    }

    private double availableSeats() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE status = 'AVAILABLE'", Long.class);
        return count == null ? 0 : count.doubleValue();
    }
}