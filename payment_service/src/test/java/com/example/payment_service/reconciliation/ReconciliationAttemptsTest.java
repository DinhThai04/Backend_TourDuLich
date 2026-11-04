package com.example.payment_service.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconciliationAttemptsTest {
    private final ReconciliationService service=mock(ReconciliationService.class);
    private final ReconciliationAttempts attempts=new ReconciliationAttempts(service);
    private final ReconciliationService.Decision input=new ReconciliationService.Decision(
            ReconciliationService.Action.UNPAID_CONFIRMED,BigDecimal.ONE,"statement-1","checked",null,true);

    @Test void transientLockFailureRetriesTheWholeServiceCall() {
        when(service.resolve("txn",input)).thenThrow(new CannotAcquireLockException("fixture"))
                .thenReturn(Map.of("status","CLOSED_UNPAID"));
        assertEquals("CLOSED_UNPAID",attempts.resolve("txn",input).get("status"));
        verify(service,times(2)).resolve("txn",input);
    }
    @Test void lockRetryIsBoundedAndReturnsRecoverableConflict() {
        when(service.resolve("txn",input)).thenThrow(new CannotAcquireLockException("fixture"));
        var failure=assertThrows(ResponseStatusException.class,()->attempts.resolve("txn",input));
        assertEquals(HttpStatus.CONFLICT,failure.getStatusCode());verify(service,times(3)).resolve("txn",input);
    }
    @Test void businessValidationFailuresAreNotRetried() {
        var failure=new ResponseStatusException(HttpStatus.BAD_REQUEST,"fixture");
        when(service.resolve("txn",input)).thenThrow(failure);
        assertSame(failure,assertThrows(ResponseStatusException.class,()->attempts.resolve("txn",input)));
        verify(service).resolve("txn",input);
    }
}
