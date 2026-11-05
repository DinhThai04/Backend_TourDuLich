package com.example.booking_service.mapper;

import com.example.booking_service.dto.response.CustomerResponse;
import com.example.booking_service.entity.Customer;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CustomerMapper {
    CustomerResponse toCustomerResponse(Customer customer);
    java.util.List<CustomerResponse> toCustomerResponseList(java.util.List<Customer> customers);

    /** The write contract accepts "Male"/"Female"; the read contract must not answer "true"/"false". */
    default String genderText(Boolean male) {
        return male == null ? null : male ? "Male" : "Female";
    }
}
