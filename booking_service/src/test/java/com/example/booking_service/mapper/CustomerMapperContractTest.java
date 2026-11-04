package com.example.booking_service.mapper;

import com.example.booking_service.entity.BookingType;
import com.example.booking_service.entity.Customer;
import com.example.booking_service.entity.CustomerStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The booking form writes "Male"/"Female"; reading a customer back must use the same words. */
class CustomerMapperContractTest {
    private final CustomerMapper mapper = new CustomerMapperImpl();

    @Test void genderBooleanIsReturnedAsTheRequestVocabulary() {
        assertEquals("Male", mapper.toCustomerResponse(customer(true)).getGender());
        assertEquals("Female", mapper.toCustomerResponse(customer(false)).getGender());
        assertNull(mapper.toCustomerResponse(customer(null)).getGender());
    }

    private static Customer customer(Boolean male) {
        return Customer.builder().fullName("Nguyễn Văn A").dateOfBirth(LocalDate.of(1998, 5, 12))
                .gender(male).address("Hà Nội").bookingType(BookingType.ADULT)
                .status(CustomerStatus.BOOKED).build();
    }
}
