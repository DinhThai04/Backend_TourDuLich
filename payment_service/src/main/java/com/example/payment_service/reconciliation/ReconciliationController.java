package com.example.payment_service.reconciliation;

import com.example.payment_service.dto.request.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/reconciliation") @RequiredArgsConstructor
public class ReconciliationController {
    private final ReconciliationService service;
    private final ReconciliationAttempts attempts;
    @GetMapping public ApiResponse<?> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="ACTIONABLE") String view){return ok(service.list(page,view));}
    @GetMapping("/{txnRef}/history") public ApiResponse<?> history(@PathVariable String txnRef){return ok(service.history(txnRef));}
    @PostMapping("/{txnRef}/resolve") public ApiResponse<?> resolve(@PathVariable String txnRef,@RequestBody ReconciliationService.Decision request){return ok(attempts.resolve(txnRef,request));}
    private ApiResponse<?> ok(Object result){return ApiResponse.builder().result(result).build();}
}
