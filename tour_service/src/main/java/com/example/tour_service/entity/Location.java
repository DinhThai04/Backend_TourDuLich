package com.example.tour_service.entity;

import com.example.tour_service.enums.LocationType;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Location {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @Column(nullable = false)
    private String city;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LocationType type;
    @Column(nullable = false)
    private String img;
}
