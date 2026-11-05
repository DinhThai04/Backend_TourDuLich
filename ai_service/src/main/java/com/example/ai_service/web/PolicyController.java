package com.example.ai_service.web;

import com.example.ai_service.advisor.PolicyCatalog;
import com.example.ai_service.conversation.ChatFailure;
import com.example.ai_service.conversation.ChatModels.Envelope;
import org.springframework.web.bind.annotation.*;

@RestController
public class PolicyController {
    private final PolicyCatalog policies;
    public PolicyController(PolicyCatalog policies) { this.policies=policies; }
    @GetMapping("/policies")
    public Envelope<PolicyCatalog.Source> policies() {
        return Envelope.ok("Chính sách SGU Tour",policies.published().orElseThrow(()->new ChatFailure(503,"POLICIES_UNAVAILABLE")));
    }
}
