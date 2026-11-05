package com.example.tour_service.service;

import com.example.tour_service.dto.request.LocationRequest;
import com.example.tour_service.dto.response.LocationResponse;
import com.example.tour_service.entity.Location;
import com.example.tour_service.enums.LocationType;
import com.example.tour_service.repository.LocationRepository;
import com.example.tour_service.repository.TourRepository;
import com.example.tour_service.repository.httpClient.FileClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LocationService {
    private static final int MAX_REFERENCE_ROWS = 1000;

    private final LocationRepository locationRepository;
    private final TourRepository tours;
    private final FileClient fileClient;

    public LocationResponse createLocation(LocationRequest request) {
        // Save first so the uploaded image can be linked to the new location. Uploading before the
        // row exists leaves the file in the detached pool, where new location images would slowly
        // consume the 500-image cleanup quota.
        Location location = locationRepository.save(Location.builder()
                .city(request.getCity())
                .img("")
                .type(request.getType())
                .build());
        if (request.getImg() != null && !request.getImg().isEmpty()) {
            try {
                location.setImg(uploadImage(request.getImg(), location.getId()));
            } catch (RuntimeException failure) {
                // The caller sees a failed create, so the placeholder row must not survive.
                locationRepository.delete(location);
                throw failure;
            }
            location = locationRepository.save(location);
        }
        return toResponse(location);
    }

    public LocationResponse updateLocation(int id, LocationRequest request) {
        Location location = locationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy địa điểm"));
        // The tour forms pick places from the DEPARTURE/DESTINATION lists, so a location that tours
        // still point at cannot be re-typed without those tours disappearing from the pickers.
        if (location.getType() != request.getType() && tours.countReferencingLocation(id) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Địa điểm đang được tour sử dụng; không đổi loại khi còn tham chiếu");
        location.setCity(request.getCity());
        location.setType(request.getType());
        if (request.getImg() != null && !request.getImg().isEmpty()) {
            // file-service can answer 200 without a URL; never overwrite a working image with "".
            String uploaded = uploadImage(request.getImg(), id);
            if (!uploaded.isBlank()) {
                location.setImg(uploaded);
            }
        }
        return toResponse(locationRepository.save(location));
    }

    /**
     * Validates and stores a location image; returns the current URL or an empty string when none is sent.
     */
    private String uploadImage(MultipartFile image, Integer locationId) {
        if (image == null || image.isEmpty()) return "";
        ImageUploadPolicy.validate(List.of(image));
        try {
            var response = fileClient.uploadLocationImg(locationId, image);
            return response == null || response.url() == null ? "" : response.url();
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Không thể tải ảnh địa điểm lên, vui lòng thử lại", failure);
        }
    }

    public List<LocationResponse> getAllLocation() {
        // Reference data is small, but a hard cap keeps a bad seed from exhausting memory.
        return locationRepository.findAll(org.springframework.data.domain.PageRequest.of(0, MAX_REFERENCE_ROWS))
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
