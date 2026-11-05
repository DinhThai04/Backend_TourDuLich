package com.example.file_service.entity;

@jakarta.persistence.Entity
@jakarta.persistence.Table(name="tour_media_lock")
public class TourMediaLock {
    @jakarta.persistence.Id
    @jakarta.persistence.Column(name="tour_id",length=9)
    private String tourId;
}
