package com.example.booking_service.entity;

import jakarta.persistence.*;
import jakarta.validation.OverridesAttribute;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "customer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @NotNull(message = "Tên khách hàng không được để trống")
    @Column(name = "full_name", nullable = false)
    private String fullName;

    @NotNull(message = "Ngày sinh không được để trống")
    @Column(name = "birthdate", length = 20, nullable = false)
    private LocalDate dateOfBirth;

    @NotNull(message = "Giới tính không được để trống")
    @Column(name = "gender", nullable = true)
    private Boolean gender;

    @Column(name = "address", nullable = true)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_type", nullable = false)
    private BookingType bookingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CustomerStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

}
