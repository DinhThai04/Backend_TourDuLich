package com.example.booking_service.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "booking")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @NotNull(message = "accountId không được null")
    @Column(name = "account_id", nullable = false)
    private int accountId;

    @NotNull(message = "tourDepartureId không được null")
    @Column(name = "tour_departure_id", nullable = false)
    private int tourDepartureId;

    @NotNull(message = "Thời gian tạo booking không được null")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @NotNull(message = "Trạng thái booking không được null")
    @Column(name = "status", length = 20, nullable = false)
    private BookingStatus status;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Customer> customers = new ArrayList<>();

    public void addCustomer(Customer customer) {
        customers.add(customer);
        customer.setBooking(this);
    }

    public void removeCustomer(Customer customer) {
        customers.remove(customer);
        customer.setBooking(null);
    }
}

