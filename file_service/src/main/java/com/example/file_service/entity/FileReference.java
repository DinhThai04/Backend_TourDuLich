package com.example.file_service.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;

@Entity
@Table(name = "file_reference", uniqueConstraints = {
    @UniqueConstraint(name = "uk_file_owner", columnNames = {"file_id", "owner_type", "owner_id"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class FileReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    Long id;

    @Column(name = "file_id", nullable = false, length = 36)
    String fileId;

    @Column(name = "owner_type", nullable = false, length = 20)
    String ownerType;

    @Column(name = "owner_id", nullable = false, length = 80)
    String ownerId;

    @Column(name = "created_at", nullable = false)
    LocalDateTime createdAt;
}