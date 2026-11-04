package com.example.tour_service.advice;

import com.example.tour_service.dto.request.ApiResponse;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static com.example.tour_service.advice.AdviceModels.*;

@RestController
@RequestMapping("/advice")
public class AdviceController {
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final AdviceService service;
    private final ObjectReader reader;
    public AdviceController(AdviceService service, ObjectMapper mapper) {
        this.service=service;
        ObjectMapper strict=mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        for (var type:List.of(LogicalType.Integer,LogicalType.Float))
            strict.coercionConfigFor(type).setCoercion(CoercionInputShape.String,CoercionAction.Fail);
        reader=strict.readerFor(SearchRequest.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    @PostMapping(value="/search", consumes="application/json")
    public ApiResponse<SearchResult> search(HttpServletRequest rawRequest) {
        SearchRequest request;
        // Read at most one byte over the limit so an oversized body is refused without being buffered.
        byte[] body;
        try (var input = rawRequest.getInputStream()) {
            body = input.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException unreadable) { throw AdviceException.invalid("Dữ liệu tìm tour không đúng hợp đồng."); }
        if (body.length > MAX_BODY_BYTES) throw new AdviceException(413,"Yêu cầu tìm tour quá lớn.");
        try { request=reader.readValue(new String(body, StandardCharsets.UTF_8)); }
        catch (IOException invalid) { throw AdviceException.invalid("Dữ liệu tìm tour không đúng hợp đồng."); }
        return ApiResponse.<SearchResult>builder().message("Đã kiểm tra dữ liệu tư vấn tour").result(service.search(request)).build();
    }
    @GetMapping("/locations")
    public ApiResponse<List<LocationOption>> locations() {
        return ApiResponse.<List<LocationOption>>builder().message("Danh mục địa điểm tư vấn").result(service.locations()).build();
    }
}
