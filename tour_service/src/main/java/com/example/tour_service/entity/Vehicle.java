package com.example.tour_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "vehicles")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Vehicle {
    @Id
    private String id;

    @Column(nullable = false)
    private String vehicleType;

}
