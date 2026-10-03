package com.example.seatreservation.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SeatAvailabilityMetricsTest {
    @Test
    void reportsAvailableSeatsFromTheDatabase() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE status = 'AVAILABLE'", Long.class)).thenReturn(7L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new SeatAvailabilityMetrics(jdbcTemplate).bindTo(registry);

        assertEquals(7.0, registry.get("seats_available").gauge().value());
    }
}