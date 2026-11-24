package com.example.file_service.entity;

@jakarta.persistence.Entity
@jakarta.persistence.Table(name="avatar_media_lock")
public class AvatarMediaLock {
    @jakarta.persistence.Id
    @jakarta.persistence.Column(name="owner_id",length=19)
    private String ownerId;

    @jakarta.persistence.Column(name="deleted_at")
    private java.time.LocalDateTime deletedAt;

    @jakarta.persistence.Column(name="avatar_version", nullable=false, columnDefinition="BIGINT NOT NULL DEFAULT 0")
    private long avatarVersion;
}
