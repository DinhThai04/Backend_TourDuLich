package com.example.pricing_service.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "prices")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TourPrice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal adultPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal childPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal toddlerPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal infantPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal singleSupplementPrice;

}
