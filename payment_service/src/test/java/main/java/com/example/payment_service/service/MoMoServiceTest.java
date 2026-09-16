// package main.java.com.example.payment_service.service;

// import main.java.com.example.payment_service.config.MoMoConfig;
// import main.java.com.example.payment_service.dto.response.MoMoResponse;
// import static org.junit.jupiter.api.Assertions.*;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.ArgumentMatchers.eq;
// import static org.mockito.Mockito.when;

// import java.nio.charset.StandardCharsets;
// import java.util.Base64;

// import javax.crypto.Mac;
// import javax.crypto.spec.SecretKeySpec;

// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.extension.ExtendWith;
// import org.mockito.Mock;
// import org.mockito.junit.jupiter.MockitoExtension;
// import org.springframework.web.client.RestTemplate;

// import com.example.payment_service.config.MoMoConfig;
// import com.fasterxml.jackson.databind.ObjectMapper;

// import com.example.payment_service.dto.response.MoMoPaymentResult;
// import com.example_payment_service.dto.response.MoMoResponse;

// @ExtendWith(MockitoExtension.class)
// public class MoMoServiceTest {

//     @Mock
//     private MoMoConfig moMoConfig;

//     @Mock
//     private RestTemplate restTemplate;

//     private final ObjectMapper objectMapper = new ObjectMapper();

//     @Test
//     public void createPayment_shouldReturnQrBase64_whenQrUrlProvided() throws Exception {
//         when(moMoConfig.getAccessKey()).thenReturn("accessKey");
//         when(moMoConfig.getPartnerCode()).thenReturn("partner");
//         when(moMoConfig.getNotifyUrl()).thenReturn("http://notify.example.com");
//         when(moMoConfig.getReturnUrl()).thenReturn("http://return.example.com");
//         when(moMoConfig.getSecretKey()).thenReturn("secretKey123");
//         when(moMoConfig.getPaymentEndpoint()).thenReturn("https://api.momo.test/v2/gateway/api/create");

//         main.java.com.example_payment_service.dto.response.MoMoResponse momoResp = new main.java.com.example_payment_service.dto.response.MoMoResponse();
//         momoResp.setPayUrl("https://pay.example.com");
//         momoResp.setQrCodeUrl("https://example.com/qr.png");
//         momoResp.setDeeplink("momo://pay/example");

//         when(restTemplate.postForObject(eq(moMoConfig.getPaymentEndpoint()), any(), eq(main.java.com.example_payment_service.dto.response.MoMoResponse.class)))
//                 .thenReturn(momoResp);

//         // fake qr bytes
//         byte[] qrBytes = new byte[] { (byte)0x89, 0x50, 0x4E, 0x47 }; // PNG header fragment
//         when(restTemplate.getForObject(eq(momoResp.getQrCodeUrl()), eq(byte[].class))).thenReturn(qrBytes);

//         // create service instance (inject mocks manually)
//         main.java.com.example_payment_service.service.MoMoService service = new main.java.com.example_payment_service.service.MoMoService(moMoConfig, restTemplate, objectMapper);

//         MoMoPaymentResult result = service.createPayment("order-1", 10000L, "test");

//         assertNotNull(result);
//         assertEquals(momoResp.getPayUrl(), result.getPayUrl());
//         assertEquals(momoResp.getQrCodeUrl(), result.getQrCodeUrl());
//         assertNotNull(result.getQrCodeBase64());
//         assertTrue(result.getQrCodeBase64().startsWith("data:image/png;base64,"));
//         // validate base64 decodes to our bytes
//         String base64Part = result.getQrCodeBase64().substring("data:image/png;base64,".length());
//         byte[] decoded = Base64.getDecoder().decode(base64Part);
//         assertArrayEquals(qrBytes, decoded);
//     }

//     @Test
//     public void verifyMoMoSignature_shouldReturnTrue_forValidSignature() throws Exception {
//         when(moMoConfig.getSecretKey()).thenReturn("my-secret");
//         main.java.com.example_payment_service.service.MoMoService service = new main.java.com.example_payment_service.service.MoMoService(moMoConfig, restTemplate, objectMapper);

//         String raw = "partnerCode=abc&orderId=1&amount=100";

//         Mac sha256Hmac = Mac.getInstance("HmacSHA256");
//         SecretKeySpec keySpec = new SecretKeySpec("my-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
//         sha256Hmac.init(keySpec);
//         byte[] h = sha256Hmac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
//         String expected = Base64.getEncoder().encodeToString(h);

//         assertTrue(service.verifyMoMoSignature(expected, raw));
//     }
// }
