package com.example.file_service;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.mock;

/**
 * Runs the callback immediately with synchronization active, then reports a fixed completion status.
 * Lets unit tests exercise the commit/rollback behaviour without a real database.
 */
final class TestTransactionTemplate extends TransactionTemplate {
    private final int completionStatus;

    TestTransactionTemplate(int completionStatus) {
        super(mock(PlatformTransactionManager.class));
        this.completionStatus = completionStatus;
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            T result = action.doInTransaction(mock(TransactionStatus.class));
            complete();
            return result;
        } catch (RuntimeException | Error failure) {
            complete();
            throw failure;
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void complete() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations())
            synchronization.afterCompletion(completionStatus);
    }
}
