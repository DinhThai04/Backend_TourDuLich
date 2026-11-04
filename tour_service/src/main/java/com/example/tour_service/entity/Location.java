package com.example.tour_service.entity;

import com.example.tour_service.enums.LocationType;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

@Entity
// Tour pages reference many locations at once; batch their proxies instead of one select each.
@BatchSize(size = 50)
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
