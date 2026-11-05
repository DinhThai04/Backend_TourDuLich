package com.example.tour_service.service;

/** Published after a departure is created or changed so widget caches cannot stay stale. */
public record TourDeparturesChangedEvent() {
}
