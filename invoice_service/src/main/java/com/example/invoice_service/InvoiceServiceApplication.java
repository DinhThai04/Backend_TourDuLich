package com.example.invoice_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients(basePackages = "com.example.invoice_service.client")
public class InvoiceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InvoiceServiceApplication.class, args);
    }
}
