package com.example.seatreservation.common;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;

class RequestIdFilterTest {
    @Test
    void preservesSafeRequestId() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health/live");
        request.addHeader("X-Request-ID", "assessment-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertDoesNotThrow(() -> filter().doFilter(request, response, new MockFilterChain()));

        assertEquals("assessment-123", response.getHeader("X-Request-ID"));
    }

    @Test
    void replacesUnsafeRequestIdWithUuid() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health/live");
        request.addHeader("X-Request-ID", "bad\nid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, new MockFilterChain());

        String requestId = (String) response.getHeader("X-Request-ID");
        assertNotEquals("bad\nid", requestId);
        assertEquals(requestId, request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        assertDoesNotThrow(() -> UUID.fromString(requestId));
    }

    private RequestIdFilter filter() {
        return new RequestIdFilter();
    }
}