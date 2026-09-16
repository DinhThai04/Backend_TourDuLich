package com.example.tour_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class TourServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(TourServiceApplication.class, args);
	}

}
