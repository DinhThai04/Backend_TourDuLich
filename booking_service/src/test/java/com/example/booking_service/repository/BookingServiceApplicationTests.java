package com.example.booking_service.repository;

import com.example.booking_service.entity.*;
import com.example.booking_service.repository.BookingRepository;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.annotation.Order;
import org.springframework.test.annotation.Rollback;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@DataJpaTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BookingServiceApplicationTests {

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void contextLoads() {
        Assertions.assertNotNull(bookingRepository, "Repository chưa được khởi tạo");
    }

    void testGetAllBooking(){

    }

    @Test
    void testCreateAndFindBooking() {
        // --- Tạo booking ---
        Booking booking = new Booking();
        booking.setCreatedAt(LocalDateTime.now());
        booking.setAccountId(1);
        booking.setTourDepartureId(1);
        booking.setStatus(BookingStatus.UNCONFIRMED);

        // --- Tạo list customer ---
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

        // --- Lưu xuống DB ---
        Booking saved = bookingRepository.save(booking);

        Assertions.assertNotNull(saved.getId(), "Booking phải có ID sau khi lưu");

        // --- Truy vấn lại ---
        Booking found = bookingRepository.findById(Long.parseLong(String.valueOf(saved.getId()))).orElse(null);
        Assertions.assertNotNull(found, "Không tìm thấy booking vừa lưu");

        // --- Kiểm tra dữ liệu ---
        Assertions.assertEquals(2, found.getCustomers().size());
        Assertions.assertEquals("Nguyễn Văn A", found.getCustomers().get(0).getFullName());
    }
//
//    /**
//     * ❌ Test case: Lưu booking với khách hàng thiếu tên -> mong đợi lỗi
//     */
//    @Test
//    void testFailWhenConstraintIsViolated() {
//        Booking booking = new Booking();
//        booking.setCreatedAt(LocalDateTime.now());
//        booking.setAccountId(1);
//        booking.setTourDepartureId(3);
//        booking.setStatus(BookingStatus.UNPAID);
//
//        List<Customer> customers = new ArrayList<>();
//        customers.add(Customer.builder()
//                .fullName(null) // ❌ Vi phạm constraint
//                .dateOfBirth(null)
//                .gender(true)
//                .address("Hà Nội")
//                .bookingType(null)
//                .status(CustomerStatus.BOOKED)
//                .booking(booking)
//                .build());
//
//        booking.setCustomers(customers);
//
//        // ❌ Không được truy vấn trước khi lưu
//        // ✅ Thay bằng kiểm tra lỗi saveAndFlush
//        Assertions.assertThrows(Exception.class, () -> {
//            bookingRepository.saveAndFlush(booking);
//        }, "Phải ném lỗi khi khách hàng thiếu tên (fullName = null)");
//    }


    /**
     * ✅ Trường hợp 1: Transaction COMMIT (dữ liệu được lưu)
     */
    @Test
    @Order(1)
    @Transactional
    @Rollback(false) // Cho phép lưu thật để kiểm chứng
    void testTransactionCommit_Success() {
        long before = bookingRepository.count();
        System.out.println("🟢 [COMMIT] Số booking trước khi lưu: " + before);
        Booking booking = new Booking();
        booking.setCreatedAt(LocalDateTime.now());
        booking.setAccountId(1);
        booking.setTourDepartureId(10);
        booking.setStatus(BookingStatus.UNCONFIRMED);
        List<Customer> customers = new ArrayList<>();
        customers.add(Customer.builder()
                .fullName("Nguyễn Văn A")
                .dateOfBirth(LocalDate.of(1990, 5, 20))
                .gender(true)
                .address("Hà Nội")
                .bookingType(BookingType.ADULT)
                .status(CustomerStatus.BOOKED)
                .booking(booking)
                .build());

        booking.setCustomers(customers);

        bookingRepository.save(booking);

        long after = bookingRepository.count();
        System.out.println("✅ [COMMIT] Số booking sau khi lưu: " + after);

        System.out.println("📋 Danh sách booking hiện tại sau commit:");
        bookingRepository.findAll().forEach(b ->
                System.out.println(" - Booking ID=" + b.getId() + ", Account=" + b.getAccountId()));

        Assertions.assertEquals(before + 1, after, "Phải có thêm 1 booking sau khi commit");
        System.out.println("✅ [COMMIT TRANSACTION] ");

        verifyDatabaseAfterRollback();
    }

    /**
     * ❌ Trường hợp 2: Transaction ROLLBACK (dữ liệu không lưu)
     */
    @Test
    @Order(2)
    @Transactional
    void testTransactionRollback_Failure() {
        long before = bookingRepository.count();
        System.out.println("🟠 [ROLLBACK] Số booking trước khi lưu: " + before);

        Booking booking = new Booking();
        booking.setCreatedAt(LocalDateTime.now());
        booking.setAccountId(100);
        booking.setTourDepartureId(100);
        booking.setStatus(BookingStatus.UNCONFIRMED);

        List<Customer> customers = new ArrayList<>();
        customers.add(Customer.builder()
                .fullName("Le Thi B")
                .dateOfBirth(LocalDate.of(1995, 3, 12))
                .gender(false)
                .address("Đà Nẵng")
                .bookingType(BookingType.ADULT)
                .status(CustomerStatus.BOOKED)
                .booking(booking)
                .build());

        booking.setCustomers(customers);
        bookingRepository.save(booking);

        // Giả lập lỗi khiến transaction rollback
        System.out.println("❌ [ROLLBACK] Giả lập lỗi — rollback transaction ngay sau khi lưu tạm...");
        Assertions.fail("Lỗi giả lập — rollback toàn bộ transaction");

        // (Dòng dưới không bao giờ chạy do fail)
        long after = bookingRepository.count();
        System.out.println("⚠️ [ROLLBACK] Số booking sau rollback: " + after);
    }

    /**
     * ❌ Trường hợp 2: Transaction rollback do lỗi nghiệp vụ (ví dụ: quá số khách cho phép)
     */
    @Test
    @Order(3)
    @Transactional
    @Rollback()
    void testTransactionRollback_BusinessError() {
        long before = bookingRepository.count();
        System.out.println("🟠 [ROLLBACK] Số booking trước khi thử lưu: " + before);

        Booking booking = new Booking();
        booking.setCreatedAt(LocalDateTime.now());
        booking.setAccountId(5);
        booking.setTourDepartureId(99);
        booking.setStatus(BookingStatus.UNCONFIRMED);

        List<Customer> customers = new ArrayList<>();

        // ❌ Giả lập nghiệp vụ: Tour chỉ cho phép tối đa 5 khách, nhưng thêm tới 6 khách
        for (int i = 1; i <= 6; i++) {
            customers.add(Customer.builder()
                    .fullName("Khách hàng " + i)
                    .dateOfBirth(LocalDate.of(1990, 1, 1).plusYears(i))
                    .gender(i % 2 == 0)
                    .address("Đà Nẵng")
                    .bookingType(BookingType.ADULT)
                    .status(CustomerStatus.BOOKED)
                    .booking(booking)
                    .build());
        }

        booking.setCustomers(customers);

        bookingRepository.save(booking);

        // 🔴 Giả lập logic nghiệp vụ: nếu vượt quá 5 khách → ném lỗi
        if (booking.getCustomers().size() > 5) {
            System.out.println("❌ [ROLLBACK] Quá số lượng khách cho phép — rollback transaction");
            throw new RuntimeException("Lỗi nghiệp vụ: Mỗi booking chỉ được phép tối đa 5 khách hàng");
        }

        System.out.println("⚠️ [ROLLBACK] Nếu in dòng này nghĩa là rollback không hoạt động!");
    }

    /**
     * 🔍 Kiểm tra sau rollback hoặc commit: số lượng booking vẫn giữ nguyên
     */

    void verifyDatabaseAfterRollback() {
        long count = bookingRepository.count();
        System.out.println("🔎 [VERIFY] Số booking hiện tại trong DB: " + count);

        List<Booking> all = bookingRepository.findAll();
        System.out.println("📋 [VERIFY] Danh sách booking thực tế sau khi rollback test:");
        all.forEach(b ->
                System.out.println(" - Booking ID=" + b.getId()
                        + ", Account=" + b.getAccountId()
                        + ", TourDeparture=" + b.getTourDepartureId()));

        Assertions.assertTrue(count >= 1, "Sau rollback, dữ liệu trước đó vẫn còn — không bị xóa");
    }
}
