package com.example.tour_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "vehicles")
// Tour pages reference the same vehicles repeatedly; one batched select beats one per tour.
@BatchSize(size = 50)
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
