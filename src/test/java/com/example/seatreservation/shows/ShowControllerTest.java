package com.example.seatreservation.shows;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShowControllerTest {
    @Test
    void createShowDoesNotRequireAuthorizationHeader() throws Exception {
        ShowService showService = mock(ShowService.class);
        when(showService.create(any())).thenReturn(new ShowController.ShowResponse(
                UUID.randomUUID(),
                "public-demo",
                25000,
                2,
                new ShowController.SeatCounts(1, 1, 0, 0),
                List.of(new ShowController.SeatView("A1", "available"))));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ShowController(showService)).build();

        mockMvc.perform(post("/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"public-demo\",\"seats\":[\"A1\"],\"price_paise\":25000,\"per_user_limit\":2}"))
                .andExpect(status().isCreated());
    }
}