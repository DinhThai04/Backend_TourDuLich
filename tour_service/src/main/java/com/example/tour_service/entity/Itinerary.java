package com.example.tour_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Itinerary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(nullable = false)
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
