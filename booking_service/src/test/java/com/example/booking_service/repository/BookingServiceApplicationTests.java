package com.example.booking_service.repository;

import com.example.booking_service.entity.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@DataJpaTest
class BookingServiceApplicationTests {

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void contextLoads() {
        Assertions.assertNotNull(bookingRepository, "Repository chưa được khởi tạo");
    }

    @Test
    void testCreateAndFindBooking() {
        Booking booking = new Booking();
        booking.setCreatedAt(LocalDateTime.now());
        booking.setAccountId(1);
        booking.setTourDepartureId(1);
        booking.setStatus(BookingStatus.CANCELLED);

        List<Customer> customers = new ArrayList<>();
        customers.add(Customer.builder()
                .fullName("Nguyễn Văn A")
                .dateOfBirth(LocalDate.of(1998, 5, 12))
                .gender(true)
                .address("Hà Nội")
                .bookingType(BookingType.ADULT)
                .status(CustomerStatus.BOOKED)
                .booking(booking)
                .build());
        customers.add(Customer.builder()
                .fullName("Trần Thị B")
                .dateOfBirth(LocalDate.of(2000, 3, 22))
                .gender(false)
                .address("TP. Hồ Chí Minh")
                .bookingType(BookingType.ADULT)
                .status(CustomerStatus.BOOKED)
                .booking(booking)
                .build());

        booking.setCustomers(customers);

        Booking saved = bookingRepository.save(booking);

        Assertions.assertNotNull(saved.getId(), "Booking phải có ID sau khi lưu");

        Booking found = bookingRepository.findById(saved.getId()).orElse(null);
        Assertions.assertNotNull(found, "Không tìm thấy booking vừa lưu");
        Assertions.assertEquals(2, found.getCustomers().size());
        Assertions.assertEquals("Nguyễn Văn A", found.getCustomers().get(0).getFullName());
    }
}
