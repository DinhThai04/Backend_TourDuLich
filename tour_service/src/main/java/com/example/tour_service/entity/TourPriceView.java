package com.example.tour_service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import java.math.BigDecimal;

/** Read projection of the shared prices table; all price writes remain in pricing_service. */
@Entity @Table(name = "prices") @Immutable @Getter
public class TourPriceView {
    @Id private Long id;
    @Column(name = "adult_price", precision = 15, scale = 2) private BigDecimal adultPrice;
}
