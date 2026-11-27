package com.example.auth_service.controller;

import com.example.auth_service.dto.request.ApiResponse;
import com.example.auth_service.dto.response.TourGuideOption;
import com.example.auth_service.service.TourGuideDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class TourGuideDirectoryController {
    private final TourGuideDirectoryService directory;

    @GetMapping("/users/tour-guides")
    @PreAuthorize("hasAuthority('SGU_SUPER_ADMIN')")
    public ApiResponse<List<TourGuideOption>> guides(@RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) Long selectedId) {
        if (query.length() > 100 || selectedId != null && selectedId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số tra cứu không hợp lệ.");
        }
        return ApiResponse.<List<TourGuideOption>>builder().result(directory.find(query, selectedId)).build();
    }
}
