package com.example.file_service.dto.reponse;

import org.springframework.core.io.Resource;

public record FileData(String contentType, Resource resource) {
}