package com.example.tour_service.service;

import com.example.tour_service.dto.request.VehicleRequest;
import com.example.tour_service.dto.response.VehicleResponse;
import com.example.tour_service.entity.Vehicle;
import com.example.tour_service.enums.LocationType;
import com.example.tour_service.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VehicleService {
    private static final int MAX_REFERENCE_ROWS = 1000;

    private final VehicleRepository vehicleRepository;

    @Transactional
    public VehicleResponse createVehicle(VehicleRequest vehicleRequest) {
        if (vehicleRepository.existsById(vehicleRequest.getId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã phương tiện đã tồn tại");
        try {
            vehicleRepository.insertNew(vehicleRequest.getId(), vehicleRequest.getVehicleType());
        } catch (DataIntegrityViolationException taken) {
            // The id was claimed between the check and the insert; the key, not the caller, decides.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã phương tiện đã tồn tại");
        }
        return VehicleResponse.builder()
                .id(vehicleRequest.getId())
                .name(vehicleRequest.getVehicleType())
                .build();
    }

    public List<VehicleResponse> getAllVehicles() {
        // Reference data is small, but a hard cap keeps a bad seed from exhausting memory.
        return vehicleRepository.findAll(org.springframework.data.domain.PageRequest.of(0, MAX_REFERENCE_ROWS))
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
