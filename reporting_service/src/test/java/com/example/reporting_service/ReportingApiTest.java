package com.example.reporting_service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportingApiTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        for (String table : java.util.List.of("reporting_inbox", "operation_audit", "ops_refund", "ops_booking", "ops_payment",
                "ops_invoice", "ops_tour", "ops_user")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void internalEndpointsRequireKey() throws Exception {
        mvc.perform(get("/internal/reports/operations")
                        .param("from", "2026-11-01")
                        .param("through", "2026-11-30"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void recordsAndReadsAudit() throws Exception {
        mvc.perform(post("/internal/reports/audit")
                        .header("X-Internal-Reporting-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorId":"7","action":"BOOKING_CONFIRMED","entityType":"booking",
                                 "entityId":"100","detail":{"status":"CONFIRMED"},
                                 "correlationId":"corr-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());

        mvc.perform(get("/internal/reports/audit")
                        .header("X-Internal-Reporting-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actorId").value("7"))
                .andExpect(jsonPath("$.content[0].action").value("BOOKING_CONFIRMED"));
    }

    @Test
    void aggregatesReadModelAndUpsertsWithoutDuplicates() throws Exception {
        project("tours", """
                {"tourId":1,"tourCode":"DN-HA","departureId":10,
                 "departureDate":"2026-11-20T06:00:00","capacity":20,"availableSeats":12,
                 "operationalStatus":"OPEN","bookedSeats":8,"heldSeats":0,
                 "lastUpdated":"2026-11-01T00:00:00"}
                """);
        project("bookings", """
                {"bookingId":100,"accountId":7,"departureId":10,"tourId":1,"tourCode":"DN-HA",
                 "status":"CONFIRMED","seats":3,"amount":3000000,"createdAt":"2026-11-01T08:00:00",
                 "confirmedAt":"2026-11-01T08:05:00","lastUpdated":"2026-11-01T08:05:00"}
                """);
        project("bookings", """
                {"bookingId":101,"accountId":8,"departureId":10,"tourId":1,"tourCode":"DN-HA",
                 "status":"EXPIRED","seats":2,"amount":2000000,"createdAt":"2026-11-02T08:00:00",
                 "lastUpdated":"2026-11-02T08:10:00"}
                """);
        project("payments", """
                {"paymentId":900,"bookingId":100,"accountId":7,"provider":"VNPAY","status":"PAID",
                 "amount":3000000,"txnRef":"TXN-1","paidAt":"2026-11-02T10:00:00",
                 "createdAt":"2026-11-02T09:55:00"}
                """);
        project("invoices", """
                {"invoiceId":500,"bookingId":100,"accountId":7,"status":"PAID","amount":3000000,
                 "issuedAt":"2026-11-02T10:01:00","paidAt":"2026-11-02T10:01:00"}
                """);
        project("refunds", """
                {"refundId":"r-schedule","bookingId":100,"source":"SCHEDULE","amount":500000,
                 "status":"REFUNDED","refundedAt":"2026-11-03T10:00:00","createdAt":"2026-11-03T09:00:00"}
                """);
        project("refunds", """
                {"refundId":"r-recon","bookingId":100,"source":"RECONCILIATION","amount":200000,
                 "status":"REFUNDED","refundedAt":"2026-11-03T11:00:00","createdAt":"2026-11-03T10:00:00"}
                """);

        project("bookings", """
                {"bookingId":100,"accountId":7,"departureId":10,"tourId":1,"tourCode":"DN-HA",
                 "status":"CONFIRMED","seats":3,"amount":3000000,"createdAt":"2026-11-01T08:00:00",
                 "confirmedAt":"2026-11-01T08:05:00","lastUpdated":"2026-11-01T09:00:00"}
                """);

        mvc.perform(get("/internal/reports/operations")
                        .header("X-Internal-Reporting-Key", KEY)
                        .param("from", "2026-11-01")
                        .param("through", "2026-11-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookings").value(2))
                .andExpect(jsonPath("$.expired").value(1))
                .andExpect(jsonPath("$.collected").value(3000000))
                .andExpect(jsonPath("$.schedule_refunds").value(500000))
                .andExpect(jsonPath("$.reconciliation_refunds").value(200000))
                .andExpect(jsonPath("$.confirmed_passengers").value(3));
    }

    @Test
    void rejectsReportRangeLongerThan31Days() throws Exception {
        mvc.perform(get("/internal/reports/operations")
                        .header("X-Internal-Reporting-Key", KEY)
                        .param("from", "2026-11-01")
                        .param("through", "2026-12-05"))
                .andExpect(status().isBadRequest());
    }

    private void project(String type, String body) throws Exception {
        mvc.perform(post("/internal/reports/projections/" + type)
                        .header("X-Internal-Reporting-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());
    }
}
