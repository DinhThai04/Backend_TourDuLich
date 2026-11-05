package com.example.payment_service.service;

import com.example.payment_service.config.MoMoConfig;
import com.example.payment_service.controller.MoMoController;
import com.example.payment_service.notification.PaymentMailOutboxWriter;
import com.example.payment_service.reservation.LifecycleTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** MoMo AIO posts its IPN as JSON; form-encoded callbacks must keep working. */
class MoMoControllerIpnTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final MoMoConfig config = new MoMoConfig("MOMOPARTNER", "ACCESS", "secret",
            "https://test-payment.momo.vn/v2/gateway/api/create",
            "http://localhost:5173/payment/momo/callback",
            "http://localhost:8083/payment/momo/ipn");
    final Clock clock = Clock.fixed(LocalDateTime.of(2026, 11, 1, 10, 0)
            .atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(), ZoneId.of("Asia/Ho_Chi_Minh"));

    LifecycleTestDatabase db;
    MoMoOrderService orders;
    MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        db = new LifecycleTestDatabase();
        db.jdbc.update("INSERT INTO booking (id,account_id,tour_departure_id,status,created_at) VALUES (1,7,1,'CONFIRMED',?)", LocalDateTime.of(2026, 11, 1, 8, 0));
        db.jdbc.update("INSERT INTO customer (booking_id,booking_type) VALUES (1,'ADULT'),(1,'ADULT')");
        var shared = new VnPayOrderService(db.jdbc, new PaymentMailOutboxWriter(db.jdbc, mapper), clock);
        orders = new MoMoOrderService(shared, db.jdbc, clock);
        mvc = MockMvcBuilders.standaloneSetup(new MoMoController(new MoMoService(orders, shared, config, mapper, clock))).build();
    }

    MoMoOrderService.Order prepare() {
        return db.tx.execute(tx -> orders.prepare(1, 7, "6700000"));
    }

    /** The signature is signed over string values; the JSON body sends the numeric ones as numbers. */
    Map<String, Object> payload(MoMoOrderService.Order order) { return payload(order, "0"); }

    Map<String, Object> payload(MoMoOrderService.Order order, String resultCode) {
        long responseTime = order.createdAt().plusMinutes(1).atZone(clock.getZone()).toInstant().toEpochMilli();
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("partnerCode", "MOMOPARTNER");
        signed.put("orderId", order.orderId());
        signed.put("requestId", order.requestId());
        signed.put("amount", Long.toString(order.amountVnd()));
        signed.put("orderInfo", "Thanh toan booking 1");
        signed.put("orderType", "momo_wallet");
        signed.put("transId", "123456789");
        signed.put("resultCode", resultCode);
        signed.put("message", "0".equals(resultCode) ? "Success" : "Transaction is processing");
        signed.put("payType", "qr");
        signed.put("responseTime", Long.toString(responseTime));
        signed.put("extraData", "");
        signed.put("signature", MoMoSignature.signCallback(config.accessKey(), config.secretKey(), signed));

        Map<String, Object> json = new LinkedHashMap<>(signed);
        json.put("amount", order.amountVnd());
        json.put("transId", 123456789L);
        json.put("resultCode", Long.parseLong(resultCode));
        json.put("responseTime", responseTime);
        return json;
    }

    MockHttpServletResponse postJson(Object body) {
        return db.tx.execute(tx -> {
            try {
                return mvc.perform(MockMvcRequestBuilders.post("/momo/ipn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body))).andReturn().getResponse();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    int status(MockHttpServletResponse response) throws Exception {
        return ((Number) mapper.readValue(response.getContentAsString(), Map.class).get("status")).intValue();
    }

    @Test void jsonIpnWithValidSignatureSettlesTheBooking() throws Exception {
        var order = prepare();
        var response = postJson(payload(order));
        assertEquals(200, response.getStatus());
        assertEquals(0, status(response));
        assertEquals(1, db.count("invoice"));
        assertEquals("PAID", db.jdbc.queryForObject("SELECT status FROM momo_transaction", String.class));
        assertEquals("CONFIRMED", db.jdbc.queryForObject("SELECT status FROM booking WHERE id=1", String.class));
    }

    /** resultCode 9000 is accepted by MoMo but not confirmed: the ack must keep asking for the retry IPN. */
    @Test void pendingIpnKeepsTheOrderRetryableAndStillSettlesOnConfirmation() throws Exception {
        var order = prepare();
        var response = postJson(payload(order, "9000"));
        assertEquals(200, response.getStatus());
        assertEquals(99, status(response));
        assertEquals(0, db.count("invoice"));
        assertEquals(10, db.seats());
        assertEquals("PENDING", db.jdbc.queryForObject("SELECT status FROM momo_transaction", String.class));
        assertEquals(9000, db.jdbc.queryForObject("SELECT result_code FROM momo_transaction", Integer.class));
        assertEquals(123456789L, db.jdbc.queryForObject("SELECT trans_id FROM momo_transaction", Long.class));

        // The retry IPN that MoMo is still allowed to send settles the same order.
        assertEquals(0, status(postJson(payload(order, "0"))));
        assertEquals(1, db.count("invoice"));
        assertEquals("PAID", db.jdbc.queryForObject("SELECT status FROM momo_transaction", String.class));
    }

    @Test void tamperedJsonIpnIsRejectedBeforeSettlement() throws Exception {
        var order = prepare();
        Map<String, Object> body = payload(order);
        // The amount stays valid, so only the signature check can reject this payload.
        body.put("message", "Tampered");
        var response = postJson(body);
        assertEquals(200, response.getStatus());
        assertEquals(99, status(response));
        assertEquals(0, db.count("invoice"));
        assertEquals("PENDING", db.jdbc.queryForObject("SELECT status FROM momo_transaction", String.class));
        assertEquals(10, db.seats());
    }

    @Test void formEncodedIpnStillWorks() throws Exception {
        var order = prepare();
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("partnerCode", "MOMOPARTNER");
        signed.put("orderId", order.orderId());
        signed.put("requestId", order.requestId());
        signed.put("amount", Long.toString(order.amountVnd()));
        signed.put("orderInfo", "Thanh toan booking 1");
        signed.put("orderType", "momo_wallet");
        signed.put("transId", "123456789");
        signed.put("resultCode", "0");
        signed.put("message", "Success");
        signed.put("responseTime", Long.toString(order.createdAt().plusMinutes(1).atZone(clock.getZone()).toInstant().toEpochMilli()));
        signed.put("extraData", "");
        signed.put("signature", MoMoSignature.signCallback(config.accessKey(), config.secretKey(), signed));
        var response = db.tx.execute(tx -> {
            try {
                var request = MockMvcRequestBuilders.post("/momo/ipn")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED);
                signed.forEach(request::param);
                return mvc.perform(request).andReturn().getResponse();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertEquals(0, status(response));
        assertEquals(1, db.count("invoice"));
    }
}
