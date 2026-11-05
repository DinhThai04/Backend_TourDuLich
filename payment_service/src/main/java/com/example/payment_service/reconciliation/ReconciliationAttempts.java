package com.example.payment_service.reconciliation;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** Retry whole rolled-back decisions, never individual SQL writes inside an existing transaction. */
@Service
@RequiredArgsConstructor
public class ReconciliationAttempts {
    private static final int MAX_ATTEMPTS=3;
    private final ReconciliationService service;

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Map<String,Object> resolve(String txnRef,ReconciliationService.Decision input) {
        for(int attempt=1;attempt<=MAX_ATTEMPTS;attempt++) {
            try {return service.resolve(txnRef,input);}
            catch(ConcurrencyFailureException conflict) {
                if(attempt==MAX_ATTEMPTS)throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Đối soát đang được thao tác đồng thời. Vui lòng tải lại lịch sử và thử lại.",conflict);
            }
        }
        throw new IllegalStateException("Unreachable reconciliation retry state");
    }
}
