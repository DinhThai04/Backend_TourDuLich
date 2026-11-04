package com.example.booking_service.repository;

import com.example.booking_service.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Integer> {
    /** Fetch the owning booking in the same query, so listing passengers does not run one query per row. */
    @EntityGraph(attributePaths = "booking")
    @Query("SELECT c FROM Customer c")
    Page<Customer> findAllWithBooking(Pageable pageable);

    @EntityGraph(attributePaths = "booking")
    Page<Customer> findByBookingAccountId(int accountId, Pageable pageable);

    @EntityGraph(attributePaths = "booking")
    @Query("SELECT c FROM Customer c WHERE c.booking.id = :bookingId")
    List<Customer> findCustomersByBookingId(@Param("bookingId") Long bookingId);
}
