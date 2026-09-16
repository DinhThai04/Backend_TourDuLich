package com.example.tour_service.service;

import com.example.tour_service.dto.request.LocationRequest;
import com.example.tour_service.dto.request.VehicleRequest;
import com.example.tour_service.dto.response.LocationResponse;
import com.example.tour_service.dto.response.VehicleResponse;
import com.example.tour_service.entity.Location;
import com.example.tour_service.entity.Vehicle;
import com.example.tour_service.enums.LocationType;
import com.example.tour_service.repository.LocationRepository;
import com.example.tour_service.repository.VehicleRepository;
import com.example.tour_service.repository.httpClient.FileClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LocationService {
    private final LocationRepository locationRepository;
    private final FileClient fileClient;

    public LocationResponse createLocation(LocationRequest request) {
        String imgUrl = "";

        if (request.getImg() != null && !request.getImg().isEmpty()) {
            try {
                var response = fileClient.uploadLocationImg(null,request.getImg());
                if (response.getUrl() != null) {
                    imgUrl = response.getUrl();
                }
            } catch (Exception e) {
                throw new RuntimeException("Upload image failed");
            }
        }
        Location location = Location.builder()
                .city(request.getCity())
                .img(imgUrl)
                .type(request.getType())
                .build();
        Location saved = locationRepository.save(location);

        return toResponse(saved);
    }

    public LocationResponse updateLocation(int id, LocationRequest request) {
        Location location = locationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Location not found with id: " + id));
        location.setCity(request.getCity());
        location.setType(request.getType());

        if (request.getImg() != null && !request.getImg().isEmpty()) {
            try {
                // Gọi sang File Service để upload
                // Tham số: (File ảnh, locationId, tourId=null)
                var apiResponse = fileClient.uploadLocationImg(id ,request.getImg());

                // Kiểm tra kết quả trả về để lấy URL
                if (apiResponse != null && apiResponse.getUrl() != null) {
                    String newImageUrl = apiResponse.getUrl();

                    // Cập nhật URL mới vào entity
                    location.setImg(newImageUrl);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to upload new location image: " + e.getMessage());
            }
        }

        Location updated = locationRepository.save(location);
        return toResponse(updated);
    }

//    public void deleteLocation(int id) {
//        if (!locationRepository.existsById(id)) {
//            throw new RuntimeException("Location not found with id: " + id);
//        }
//        locationRepository.deleteById(id);
//    }

    public List<LocationResponse> getAllLocation() {
        return locationRepository.findAll()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<LocationResponse> getAllDepartureLocations() {
        return locationRepository.findByType(LocationType.DEPARTURE)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<LocationResponse> getAllDestinationLocations() {
        return locationRepository.findByType(LocationType.DESTINATION)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Tìm kiếm điểm đi (DEPARTURE) dựa trên từ khóa (tên thành phố).
     * Tìm kiếm không phân biệt chữ hoa/thường.
     */
    public List<LocationResponse> searchDepartureLocations(String keyword) {
        // Gọi phương thức repository mới (bạn sẽ cần thêm ở bước sau)
        if (keyword == null || keyword.trim().isEmpty()) {
            System.out.println(">>>>>>>>>>>>>>>>>>>>rỗng");
            return Collections.emptyList(); // Trả về danh sách rỗng
        }
        return locationRepository.findByTypeAndCityContainsIgnoreCase(LocationType.DEPARTURE, keyword)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Tìm kiếm điểm đến (DESTINATION) dựa trên từ khóa (tên thành phố).
     * Tìm kiếm không phân biệt chữ hoa/thường.
     */
    public List<LocationResponse> searchDestinationLocations(String keyword) {
        // Gọi phương thức repository mới (bạn sẽ cần thêm ở bước sau)
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList(); // Trả về danh sách rỗng
        }
        return locationRepository.findByTypeAndCityContainsIgnoreCase(LocationType.DESTINATION, keyword)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }
    public List<LocationResponse> attractiveDestinationLocations() {
        return locationRepository.findRandom7Locations()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }


    private LocationResponse toResponse(Location location) {
        return LocationResponse.builder()
                .id(location.getId())
                .city(location.getCity())
                .img(location.getImg())
                .type(location.getType())
                .build();
    }
}
