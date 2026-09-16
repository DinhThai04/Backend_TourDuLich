package com.example.tour_service.service;

import com.example.tour_service.dto.request.VehicleRequest;
import com.example.tour_service.dto.response.VehicleResponse;
import com.example.tour_service.entity.Vehicle;
import com.example.tour_service.enums.LocationType;
import com.example.tour_service.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VehicleService {
    private final VehicleRepository vehicleRepository;

    public VehicleResponse createVehicle(VehicleRequest vehicleRequest) {
        Vehicle vehicle = Vehicle.builder()
                .id(vehicleRequest.getId())
                .vehicleType(vehicleRequest.getVehicleType())
                .build();
        Vehicle savedVehicle = vehicleRepository.save(vehicle);

        return toResponse(savedVehicle);
    }

    public List<VehicleResponse> getAllVehicles() {
        return vehicleRepository.findAll()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }


    private VehicleResponse toResponse(Vehicle vehicle) {
        return VehicleResponse.builder()
                .id(vehicle.getId())
                .name(vehicle.getVehicleType())
                .build();
    }
}
