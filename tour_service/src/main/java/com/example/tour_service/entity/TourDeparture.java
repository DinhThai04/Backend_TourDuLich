package com.example.tour_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TourDeparture {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(nullable = false)
    private String tourCode;

    @Column(nullable = false)
    private LocalDateTime departureDate;

    @Column(nullable = false)
    private LocalDateTime returnDate;

    @Column(nullable = false)
    private Short availableSeats;

    private Integer capacity;

    @Builder.Default
    @Column(nullable=false)
    private String operationalStatus="OPEN";

    @Version
    @Column(nullable = false)
    private long version;

    @ManyToOne
    @JoinColumn(nullable = false, name = "tour_id")
    private Tour tour;
}
