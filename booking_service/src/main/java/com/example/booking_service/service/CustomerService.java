package com.example.booking_service.service;

import com.example.booking_service.dto.request.CustomerRequest;
import com.example.booking_service.dto.response.CustomerResponse;
import com.example.booking_service.entity.Customer;
import com.example.booking_service.mapper.CustomerMapper;
import com.example.booking_service.paging.PageSlice;
import com.example.booking_service.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@org.springframework.transaction.annotation.Transactional(readOnly = true)
@RequiredArgsConstructor
public class CustomerService {
    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;
    private final com.example.booking_service.repository.BookingRepository bookingRepository;
    private final com.example.booking_service.reservation.BookingAccess access;

    /** {@code mine} keeps the profile page on the caller's own rows even for a staff account. */
    public List<CustomerResponse> getAllCustomers(Integer page, Integer size, boolean mine) {
        Pageable pageable = PageSlice.of(page, size).pageable(Sort.by(Sort.Direction.DESC, "id"));
        Page<Customer> customers = !mine && (access.can("CUSTOMERS_GET_ALL") || access.can("BOOKINGS_GET_ALL"))
                ? customerRepository.findAllWithBooking(pageable)
                : customerRepository.findByBookingAccountId(access.accountId(), pageable);
        return customerMapper.toCustomerResponseList(customers.getContent());
    }

    public List<CustomerResponse> getListOfCustomersByBookingId(Long id){
        var booking = bookingRepository.findById(identifier(id))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy booking"));
        if (!access.can("BOOKINGS_GET_ALL") && !access.can("BOOKINGS_GET_DETAIL"))
            access.requireReadOwner(booking.getAccountId(), "CUSTOMERS_GET_ALL");
        List<Customer> customers = customerRepository.findCustomersByBookingId(id);
        return customerMapper.toCustomerResponseList(customers);
    }

    public CustomerResponse getCustomerById(Long id){
        Customer customer = customerRepository.findById(identifier(id))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy hành khách"));
        if (!access.can("BOOKINGS_GET_ALL") && !access.can("CUSTOMERS_GET_DETAIL"))
            access.requireReadOwner(customer.getBooking().getAccountId(), "CUSTOMERS_GET_ALL");
        return customerMapper.toCustomerResponse(customer);
    }

    public List<CustomerResponse> createCustomers(List<CustomerRequest> customerRequests){
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                "Hành khách phải được thêm qua luồng đặt tour để kiểm tra và giữ đúng số chỗ");
    }

    /** A Long outside the entity's int range cannot exist, so answer 404 instead of a 500. */
    private static int identifier(Long id) {
        try {
            return Math.toIntExact(id);
        } catch (ArithmeticException outOfRange) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy dữ liệu");
        }
    }
}
