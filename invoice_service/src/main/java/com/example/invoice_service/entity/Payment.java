package com.example.invoice_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "payment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = "method", nullable = true, length = 20)
    private Method method;
}
