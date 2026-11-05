package com.example.ai_service.web;

import com.example.ai_service.conversation.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import static com.example.ai_service.conversation.ChatModels.*;

@RestController
public class ChatController {
    static final String OWNER_HEADER = "X-Ai-Owner";
    private static final String OWNER_PATTERN = "[A-Za-z0-9:._-]{1,100}";
    private final ConversationService conversations;
    public ChatController(ConversationService conversations) { this.conversations=conversations; }
    @PostMapping(value="/conversations",consumes="application/json") @ResponseStatus(HttpStatus.CREATED)
    public Envelope<Created> create(@Valid @RequestBody CreateRequest body, HttpServletRequest request) {
        return Envelope.ok("Đã tạo phiên tư vấn",conversations.create(owner(request)));
    }
    @PostMapping(value="/conversations/reset",consumes="application/json")
    public Envelope<Created> reset(@Valid @RequestBody ResetRequest body) {
        return Envelope.ok("Đã bắt đầu phiên mới",conversations.reset(body.conversationId()));
    }
    @PostMapping(value="/chat",consumes="application/json")
    public Envelope<ChatResult> chat(@Valid @RequestBody ChatRequest body,HttpServletRequest request) {
        request.setAttribute("ai.requestId",body.requestId());
        return Envelope.ok("Đã xử lý tư vấn",conversations.chat(body));
    }

    /**
     * Groups sessions per caller. Behind the gateway the owner key is derived from the
     * bearer token (or peer address for anonymous callers) and injected as a trusted
     * header. The header is only honoured from a loopback/private peer, so a caller that
     * reaches the service directly cannot pick another user's owner key.
     */
    private static String owner(HttpServletRequest request) {
        String remote=request.getRemoteAddr();
        String owner=request.getHeader(OWNER_HEADER);
        if(owner!=null && owner.matches(OWNER_PATTERN) && trustedProxy(remote)) return owner;
        return remote==null || remote.isBlank() ? null : "d:"+remote;
    }

    private static boolean trustedProxy(String address) {
        if(address==null || address.isBlank()) return false;
        try {
            var parsed=java.net.InetAddress.getByName(address);
            return parsed.isLoopbackAddress() || parsed.isSiteLocalAddress();
        } catch (java.net.UnknownHostException invalid) {
            return false;
        }
    }
}
