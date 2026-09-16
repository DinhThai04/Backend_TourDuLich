package com.example.search_service.document;

import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.elasticsearch.annotations.Document;
import java.math.BigDecimal;
import java.util.List;

@Document(indexName = "tours")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TourDocument {
    @Id
    private int id;
    private String tourProgram;
    private String tourTitle;
    private BigDecimal basePrice;
    private String departureLocation;
    private String destinationLocation;
    private String vehicle;
    private List<String> departureDates;
}
