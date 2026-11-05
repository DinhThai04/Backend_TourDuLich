package com.example.tour_service.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Defers in-memory cache side effects until the surrounding transaction commits, so a reader can
 * never see image or catalogue cache state that the database has not accepted yet.
 */
@Slf4j
final class AfterCommit {

    private AfterCommit() {
    }

    /** Runs the action now when no transaction is active, otherwise right after a successful commit. */
    static void run(Runnable action, String failureMessage) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSafely(action, failureMessage);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // The edit is already durable; a cache failure must not turn it into a failed request.
                runSafely(action, failureMessage);
            }
        });
    }

    /** Warns when the surrounding transaction rolls back, so an already-applied side effect is visible in logs. */
    static void onRollback(String message) {
        onRollback(null, message);
    }

    /** Runs a recovery action after rollback, e.g. to resync a remote side effect that was already applied. */
    static void onRollback(Runnable recovery, String failureMessage) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                if (recovery == null) log.warn(failureMessage);
                else runSafely(recovery, failureMessage);
            }
        });
    }

    private static void runSafely(Runnable action, String failureMessage) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            log.warn("{}: {}", failureMessage, failure.toString());
        }
    }
}
