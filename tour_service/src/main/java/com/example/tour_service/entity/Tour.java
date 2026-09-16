
package com.example.tour_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Tour {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(nullable = false)
    private String tourProgram;

    @Column(nullable = false)
    private String tourTitle;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private int duration;

    @ManyToOne
    @JoinColumn(nullable = false, name = "departure_location_id")
    private Location departureLocation;

    @ManyToOne
    @JoinColumn(nullable = false, name = "destination_id")
    private Location destinationLocation;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal basePrice;

    @ManyToOne
    @JoinColumn(nullable = false, name = "vehicle_id")
    private Vehicle vehicle;

    @Column(nullable = false, name = "price_id")
    private Long tourPriceId;

    @ElementCollection
    @Column(name = "image_id")
    private List<String> imageIds;

    @OneToMany(mappedBy = "tour", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TourDeparture> tourDepartures;

}
