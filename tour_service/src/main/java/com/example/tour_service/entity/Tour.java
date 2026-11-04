
package com.example.tour_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Entity
// Departure lists navigate back to their tour; batch those loads like the catalogue does.
// Read queries fetch the three display associations explicitly (see TourRepository), so they stay LAZY.
@BatchSize(size = 50)
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

    @ManyToOne(fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @JoinColumn(nullable = false, name = "departure_location_id")
    private Location departureLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @JoinColumn(nullable = false, name = "destination_id")
    private Location destinationLocation;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal basePrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @JoinColumn(nullable = false, name = "vehicle_id")
    private Vehicle vehicle;

    @Column(nullable = false, name = "price_id")
    private Long tourPriceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "price_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private TourPriceView priceView;

    @OneToMany(mappedBy = "tour", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TourDeparture> tourDepartures;

}
