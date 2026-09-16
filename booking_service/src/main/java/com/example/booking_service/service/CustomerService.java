package com.example.booking_service.service;

import com.example.booking_service.dto.request.CustomerRequest;
import com.example.booking_service.dto.response.CustomerResponse;
import com.example.booking_service.entity.Customer;
import com.example.booking_service.exception.AppException;
import com.example.booking_service.exception.ErrorCode;
import com.example.booking_service.mapper.CustomerMapper;
import com.example.booking_service.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerService {
    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;

    /*****
     *
     * @return
     */
    public List<CustomerResponse> getAllCustomers() {
        List<Customer> customers = customerRepository.findAll();
        return customerMapper.toCustomerResponseList(customers);
    }

    /*****
     *
     * @param id
     * @return
     */
    public List<CustomerResponse> getListOfCustomersByBookingId(Long id){
        List<Customer> customers = customerRepository.findCustomersByBookingId(id);
        return customerMapper.toCustomerResponseList(customers);
    }

    /*****
     *
     * @param id
     * @return
     */
    public CustomerResponse getCustomerById(Long id){
        Customer customer = customerRepository.findById(Math.toIntExact(id))
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        return customerMapper.toCustomerResponse(customer);
    }

    /******
     *
     * @param customerRequests
     * @return
     */
    public List<CustomerResponse> createCustomers(List<CustomerRequest> customerRequests){
        List<Customer> customers = customerRequests.stream()
                .map(customerMapper::toCustomer)
                .toList();

        try {
            List<Customer> savedCustomers = customerRepository.saveAll(customers);
        }catch (DataIntegrityViolationException exception){
            throw exception;
        }
        return customerMapper.toCustomerResponseList(customers);
    }
}
