package com.example.tour_service.dto.request;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class TourSearchRequest {
    private String title;
    private String dest; // Điểm đến
    private String dep; // Điểm khởi hành
    private String veh; // Phương tiện
    private String sort;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) // Định dạng ngày: yyyy-MM-dd
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate depD; // Ngày khởi hành

    private BigDecimal minP; // Giá thấp nhất
    private BigDecimal maxP; // Giá cao nhất
}
