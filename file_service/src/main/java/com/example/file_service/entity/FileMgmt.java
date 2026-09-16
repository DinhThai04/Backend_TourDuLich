package com.example.file_service.entity;

import jakarta.persistence.*; // Hoặc javax.persistence.* tùy version
import lombok.*;
import lombok.experimental.FieldDefaults;

@Setter
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity 
@Table(name = "file_mgmt") 
@FieldDefaults(level = AccessLevel.PRIVATE)
public class FileMgmt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    String id;

    @Column(name = "owner_id", nullable = false)
    String ownerId;

    @Column(name = "tour_id", nullable = false)
    String tourId;

    @Column(name = "location_id") // Thêm cột này
    Integer locationId;


    @Column(name = "content_type")
    String contentType;

    @Column(name = "size")
    long size;

    @Column(name = "md5_checksum")
    String md5Checksum;

    @Column(name = "path")
    String path;

    @Column(name = "url")
    String url;
}
