package com.example.auth_service.dto;

import jakarta.persistence.Column;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FileInfo {
    private String id;
    private String ownerId;
    private String url;

    private String contentType;

    private long size;

    private String md5Checksum;

    private String path;


}
