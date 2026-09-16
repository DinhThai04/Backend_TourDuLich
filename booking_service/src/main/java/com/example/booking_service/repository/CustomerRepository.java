package com.example.booking_service.repository;

import com.example.booking_service.entity.Customer;
import feign.Param;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Integer> {
    @Query("SELECT c FROM Customer c WHERE c.booking.id = :bookingId")
    List<Customer> findCustomersByBookingId(@Param("bookingId") Long bookingId);
}
