package com.example.file_service.entity;

@jakarta.persistence.Entity
@jakarta.persistence.Table(name="avatar_media_lock")
public class AvatarMediaLock {
    @jakarta.persistence.Id
    @jakarta.persistence.Column(name="owner_id",length=19)
    private String ownerId;
}
