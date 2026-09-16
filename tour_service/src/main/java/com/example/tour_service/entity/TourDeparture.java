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

    @ManyToOne
    @JoinColumn(nullable = false, name = "tour_id")
    private Tour tour;
}
