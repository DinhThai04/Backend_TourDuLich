package com.example.tour_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "itinerary", uniqueConstraints = @UniqueConstraint(
        name = "ux_itinerary_tour_day", columnNames = {"tour_id", "day_number"}))
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Itinerary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "day_number", nullable = false)
    private int dayNumber;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String description;

    @Column(nullable = false)
    private String meal;

    @ManyToOne
    @JoinColumn(nullable = false, name = "tour_id")
    private Tour tour;
}
