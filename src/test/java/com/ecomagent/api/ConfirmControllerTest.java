package com.ecomagent.api;

import com.ecomagent.agent.ConfirmationConflictException;
import com.ecomagent.agent.ConfirmationService;
import com.ecomagent.agent.PendingAction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 坐席确认台接口契约测试（M4）。
 */
@WebMvcTest(ConfirmController.class)
class ConfirmControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConfirmationService confirmationService;

    private PendingAction pending(String status) {
        return new PendingAction("p1", "conv-1", "default", "refund",
                "{\"orderId\":\"ORD-1001\"}", status, "key123", null, null, null, null,
                Instant.now(), Instant.now().plusSeconds(300));
    }

    @Test
    void confirmReturnsOk() throws Exception {
        when(confirmationService.confirm(eq("p1"), any(), eq("agent01")))
                .thenReturn(pending(PendingAction.STATUS_CONFIRMED));

        mockMvc.perform(post("/confirm/p1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"agent01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("confirmed"));
    }

    @Test
    void confirmConflictReturns409() throws Exception {
        when(confirmationService.confirm(eq("p1"), any(), any()))
                .thenThrow(new ConfirmationConflictException("already handled"));

        mockMvc.perform(post("/confirm/p1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void modifyAndConfirmReturnsOk() throws Exception {
        when(confirmationService.confirm(eq("p1"), anyMap(), eq("agent01")))
                .thenReturn(pending(PendingAction.STATUS_CONFIRMED));

        mockMvc.perform(put("/confirm/p1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"amount\":99.0},\"operator\":\"agent01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("confirmed"));
    }

    @Test
    void rejectReturnsOk() throws Exception {
        when(confirmationService.reject(eq("p1"), eq("agent01")))
                .thenReturn(pending(PendingAction.STATUS_REJECTED));

        mockMvc.perform(post("/confirm/p1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"agent01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("rejected"));
    }

    @Test
    void cancelReturnsOk() throws Exception {
        when(confirmationService.cancel(eq("p1")))
                .thenReturn(pending(PendingAction.STATUS_CANCELLED));

        mockMvc.perform(post("/confirm/p1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("cancelled"));
    }

    @Test
    void getNotFoundReturns404() throws Exception {
        when(confirmationService.get(eq("missing"))).thenReturn(null);

        mockMvc.perform(get("/confirm/missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
}
