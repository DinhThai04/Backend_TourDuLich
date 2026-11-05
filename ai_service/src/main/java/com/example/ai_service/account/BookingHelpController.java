package com.example.ai_service.account;

import com.example.ai_service.conversation.ChatModels.Envelope;
import com.example.ai_service.guard.TurnExecutor;
import com.example.ai_service.security.AiInputPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class BookingHelpController {
    private static final String HELP_PATH="/booking-help";
    private static final String HELP_MESSAGE="Tra cứu booking";
    public record Request(@NotBlank String message,@Min(1) Integer selectedBookingId) {
        @Override public String toString() { return "BookingHelpRequest[REDACTED]"; }
    }
    private final BookingHelpService service;
    private final TurnExecutor turns;
    public BookingHelpController(BookingHelpService service,TurnExecutor turns) { this.service=service;this.turns=turns; }
    @PostMapping(value=HELP_PATH,consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Envelope<BookingHelpService.Help>> help(@Valid @RequestBody Request body,
            @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization) {
        AiInputPolicy.requireMessage(body.message());
        var result=turns.execute(TurnExecutor.Lane.BOOKING_HELP,()->service.help(body.message(),body.selectedBookingId(),authorization));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Envelope.ok(HELP_MESSAGE,result));
    }
}
