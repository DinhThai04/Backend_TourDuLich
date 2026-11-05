package com.example.tour_service.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfterCommitTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void runsImmediatelyWhenNoTransactionIsActive() {
        List<String> applied = new ArrayList<>();
        AfterCommit.run(() -> applied.add("cache"), "fixture");
        assertEquals(List.of("cache"), applied);
    }

    @Test
    void cacheWriteWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        List<String> applied = new ArrayList<>();
        AfterCommit.run(() -> applied.add("cache"), "fixture");
        assertTrue(applied.isEmpty());
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
        assertEquals(List.of("cache"), applied);
    }

    @Test
    void cacheFailureAfterCommitIsLoggedInsteadOfFailingTheCommittedEdit() {
        assertDoesNotThrow(() -> AfterCommit.run(() -> {
            throw new IllegalStateException("fixture");
        }, "Cannot cache tour 1"));
    }
}
