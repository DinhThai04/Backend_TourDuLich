package com.example.search_service.document;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import java.math.BigDecimal;
import java.util.List;

@Document(indexName = "tours")
@Data
@NoArgsConstructor
public class TourDocument {
    @Id
    private int id;
    private String tourProgram;
    private String tourTitle;
    private BigDecimal basePrice;

    // Filters compare these fields exactly, so they must be keyword, not analyzed text.
    @Field(type = FieldType.Keyword)
    private String departureLocation;
    @Field(type = FieldType.Keyword)
    private String destinationLocation;
    @Field(type = FieldType.Keyword)
    private String vehicle;
    @Field(type = FieldType.Keyword)
    private List<String> departureDates;

    /** Zero-padded ID so keyword ordering matches numeric order; the @Id field is not sortable. */
    @Field(type = FieldType.Keyword)
    private String sortKey;

    public TourDocument(int id, String tourProgram, String tourTitle, BigDecimal basePrice,
                        String departureLocation, String destinationLocation, String vehicle,
                        List<String> departureDates) {
        this.id = id;
        this.tourProgram = tourProgram;
        this.tourTitle = tourTitle;
        this.basePrice = basePrice;
        this.departureLocation = departureLocation;
        this.destinationLocation = destinationLocation;
        this.vehicle = vehicle;
        this.departureDates = departureDates;
        this.sortKey = sortKey(id);
    }

    public static String sortKey(int id) { return String.format("%010d", id); }
}
