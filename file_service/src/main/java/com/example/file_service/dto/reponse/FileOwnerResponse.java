package com.example.file_service.dto.reponse;

/** Public avatar projection: never expose storage path, checksum or internal ids. */
public record FileOwnerResponse(String url, String contentType, long size) {
}
