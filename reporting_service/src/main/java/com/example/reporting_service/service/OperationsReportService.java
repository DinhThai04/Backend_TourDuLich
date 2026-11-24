package com.example.reporting_service.service;

import com.example.reporting_service.dto.OperationsProjectionRequests;
import com.example.reporting_service.repository.OperationsReadRepository;
import com.example.reporting_service.repository.OperationsReadRepository.OperationsReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class OperationsReportService {

    private final OperationsReadRepository repository;

    @Transactional(readOnly = true, timeout = 5)
    public OperationsReport report(LocalDate from, LocalDate through) {
        if (from == null || through == null || through.isBefore(from) || through.isAfter(from.plusDays(30)))
            throw new IllegalArgumentException("Report range must be between 1 and 31 days");
        return repository.report(from, through);
    }

    @Transactional
    public void project(OperationsProjectionRequests.Booking request) {
        repository.upsert(request);
    }

    @Transactional
    public void project(OperationsProjectionRequests.Payment request) {
        repository.upsert(request);
    }

    @Transactional
    public void project(OperationsProjectionRequests.Invoice request) {
        repository.upsert(request);
    }

    @Transactional
    public void project(OperationsProjectionRequests.Tour request) {
        repository.upsert(request);
    }

    @Transactional
    public void project(OperationsProjectionRequests.User request) {
        repository.upsert(request);
    }

    @Transactional
    public void project(OperationsProjectionRequests.Refund request) {
        repository.upsert(request);
    }
}
