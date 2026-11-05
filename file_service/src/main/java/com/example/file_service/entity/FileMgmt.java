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
// One active avatar per account, one linked image per location; the loser of a concurrent upload is rejected here.
@Table(name = "file_mgmt", uniqueConstraints = {
    @UniqueConstraint(name="ux_file_avatar_owner",columnNames="avatar_owner_id"),
    @UniqueConstraint(name="ux_file_location",columnNames="location_id")})
@FieldDefaults(level = AccessLevel.PRIVATE)
public class FileMgmt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    String id;

    @Column(name = "owner_id", nullable = false)
    String ownerId;

    // Only an active avatar sets this key. Generic files can share owner_id.
    @Column(name = "avatar_owner_id", length = 19)
    String avatarOwnerId;

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
