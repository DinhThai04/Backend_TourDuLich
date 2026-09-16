package com.example.booking_service.mapper;

import com.example.booking_service.dto.request.CustomerRequest;
import com.example.booking_service.dto.response.CustomerResponse;
import com.example.booking_service.entity.Customer;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CustomerMapper {
    Customer toCustomer(CustomerRequest request);
    CustomerResponse toCustomerResponse(Customer customer);
    java.util.List<CustomerResponse> toCustomerResponseList(java.util.List<Customer> customers);

}
