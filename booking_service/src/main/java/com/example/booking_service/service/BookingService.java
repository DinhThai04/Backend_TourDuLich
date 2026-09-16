package com.example.booking_service.service;

import com.example.booking_service.client.TourDepartureClient;
import com.example.booking_service.client.UserClient;
import com.example.booking_service.dto.request.BookingRequest;
import com.example.booking_service.dto.response.*;
import com.example.booking_service.entity.*;
import com.example.booking_service.exception.AppException;
import com.example.booking_service.exception.ErrorCode;
import com.example.booking_service.mapper.BookingMapper;
import com.example.booking_service.mapper.CustomerMapper;
import com.example.booking_service.repository.BookingRepository;
import com.example.booking_service.repository.CustomerRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingService {
    private final UserClient userClient;
    private final TourDepartureClient tourDepartureClient;
    private final BookingRepository bookingRepository;
    private final CustomerRepository customerRepository;
    private final BookingMapper bookingMapper;
    private final CustomerMapper customerMapper;

    /*****
     *
     * @return
     */
    public List<BookingResponse> getAllBookings() {

        List<Booking> bookings = bookingRepository.findAll();
        return bookingMapper.toBookingResponseList(bookings);
    }

    /*****
     *
     * @param id
     * @return
     */
    public BookingResponse getBookingById(Long id){
        Booking booking = bookingRepository.findById(id.intValue())
                .orElseThrow(() -> new AppException(ErrorCode.BOOKING_NOT_EXISTED));
        return bookingMapper.toBookingResponse(booking);
    }

    /***
     *
     * @param bookingRequest
     * @return
     */
    public BookingResponse createBooking(BookingRequest bookingRequest) {
        System.err.println(bookingRequest);

        try {
            /*****
             * SEND REQUEST TO USER API AND TOUR DEPARTURE API FOR GETTING ID
             */
            ApiResponse<UserResponse> userResponse = userClient.getUserById(
                    Long.parseLong(bookingRequest.getUserId()));

            ApiResponse<TourDepartureResponse> departureResponse = tourDepartureClient.getTourDepartureById(
                    Long.parseLong(bookingRequest.getTourDepartureId()));


            if (userResponse == null || userResponse.getResult() == null) {
                throw new IllegalArgumentException("User không tồn tại với ID: " + bookingRequest.getUserId());
            }

            if (departureResponse == null || departureResponse.getResult() == null) {
                throw new IllegalArgumentException("TourDeparture không tồn tại với ID: " + bookingRequest.getTourDepartureId());
            }

            /*****
             * CHECKING FOR THE REST OF TOUR DEPARTURE TICKET
             */
            int availableSeats = departureResponse.getResult().getAvailableSeats();
            int requestedSeats = bookingRequest.getListOfCustomers().size();

            if(availableSeats == 0){
                return BookingResponse.builder()
                        .message("Tour đã hết chỗ.")
                        .build();
            }

            if (requestedSeats > availableSeats) {
                return BookingResponse.builder()
                        .message("Tour chỉ còn lại: " + availableSeats)
                        .build();
            }

            /*****
             * GENERATE BOOKING INSTANCE FOR SAVING TO DATABASE
             */
            Booking booking = bookingMapper.toBooking(bookingRequest);
            booking.setStatus(BookingStatus.CONFIRMED);
            booking.setAccountId(Integer.parseInt(bookingRequest.getUserId()));
            booking.setCreatedAt(LocalDateTime.now());
            booking.setTourDepartureId(Integer.parseInt(bookingRequest.getTourDepartureId()));

            /*****
             * GENERATE LIST OF CUSTOMERS FOR SAVING TO BOOKING INSTANCE
             */
            List<Customer> customers = new ArrayList<>();

            for (var cusReq : bookingRequest.getListOfCustomers()) {
                LocalDate birthdate = LocalDate.parse(cusReq.getBirthdate());
                int age = Period.between(birthdate, LocalDate.now()).getYears();
                boolean genderValue = cusReq.getGender().equalsIgnoreCase("Male");

                BookingType bookingType;
                if (age < 2) {
                    bookingType = BookingType.INFANT;
                } else if (age <=4) {
                    bookingType = BookingType.TODDLER;
                } else if (age <= 11) {
                    bookingType = BookingType.CHILD;
                } else {
                    bookingType = BookingType.ADULT;
                }

                Customer customer = Customer.builder()
                        .fullName(cusReq.getFullName())
                        .address(cusReq.getAddress())
                        .dateOfBirth(birthdate)
                        .status(CustomerStatus.BOOKED)
                        .gender(genderValue)
                        .bookingType(bookingType)
                        .booking(booking)
                        .build();

                booking.getCustomers().add(customer);
                customers.add(customer);

            }

            bookingRepository.save(booking);


            /*****
             * RETURN BOOKING GENERATED RESULT
             */
            return BookingResponse.builder()
                    .id(String.valueOf(booking.getId()))
                    .createdAt(String.valueOf(booking.getCreatedAt()))
                    .accountId(String.valueOf(booking.getAccountId()))
                    .status(String.valueOf(booking.getStatus()))
                    .message("Đặt tour thành công")
                    .tourDepartureId(bookingRequest.getTourDepartureId())
                    .build();

        } catch (DataIntegrityViolationException exception) {
            throw new RuntimeException("Lỗi dữ liệu không hợp lệ trong DB", exception);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ID không hợp lệ", e);
        } catch (FeignException.NotFound e) {
            throw new IllegalArgumentException("Không tìm thấy tài nguyên: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi lấy dữ liệu người dùng hoặc tour", e);
        }
    }

    /****
     *
     * @param id
     * @return
     */
    public BookingResponse updateBookingStatus(Long id) {
        /*****
         * GET CURRENT BOOKING BY ID
         */
        Booking booking = bookingRepository.findById(id.intValue())
                .orElseThrow(() -> new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION));

        /*****
         * SAVE CURRENT BOOKING INSTANCE
         */
        booking.setStatus(BookingStatus.UNCONFIRMED);
        booking.setCreatedAt(LocalDateTime.now());

        bookingRepository.save(booking);

        return BookingResponse.builder()
                .id(String.valueOf(booking.getId()))
                .accountId(String.valueOf(booking.getAccountId()))
                .status(String.valueOf(booking.getStatus()))
                .createdAt(String.valueOf(booking.getCreatedAt()))
                .message("Cập nhật trạng thái booking thành công.")
                .build();
    }

    public BookingResponse deleteBooking(Long id){
        /*****
         * GET CURRENT BOOKING BY ID
         */
        Booking booking = bookingRepository.findById(id.intValue())
                .orElseThrow(() -> new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION));

        /*****
         * DELETE CURRENT BOOKING INSTANCE
         */
        bookingRepository.deleteById(id.intValue());

        return BookingResponse.builder()
                .message("Xóa booking thành công.")
                .build();
    }

}
