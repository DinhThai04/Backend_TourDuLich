package com.example.api_gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

@Component
public final class AiAdmissionFilter implements GlobalFilter,Ordered {
    /**
     * Stable per-caller key consumed by the AI service. The gateway owns this header: any
     * value supplied by the client is replaced below, never trusted.
     */
    static final String OWNER_HEADER = "X-Ai-Owner";
    private static final String BEARER = "Bearer ";

    private final AiTrafficLimiter limiter;
    private final String prefix;
    public AiAdmissionFilter(AiTrafficLimiter limiter,@Value("${app.api-prefix:/api/v1}") String prefix){this.limiter=limiter;this.prefix=prefix+"/ai/";}
    @Override public int getOrder(){return -2;}
    @Override public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain){
        String path=exchange.getRequest().getURI().getPath();
        if(exchange.getRequest().getMethod()!=HttpMethod.POST || !path.startsWith(prefix))return chain.filter(exchange);
        String action=path.substring(prefix.length());
        if(!Set.of("conversations","chat","conversations/reset","booking-help").contains(action))return chain.filter(exchange);
        // Same trusted-proxy rule as login throttling: behind nginx the socket is the proxy, so the
        // caller must be read from the headers nginx overwrites.
        String peer=ClientAddress.of(exchange.getRequest());
        long retry=limiter.admit(peer,action);
        if(retry>0){
            var response=exchange.getResponse();response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);response.getHeaders().setCacheControl("no-store");
            response.getHeaders().set(HttpHeaders.RETRY_AFTER,Long.toString(retry));
            byte[] body="{\"code\":1429,\"message\":\"Bạn thao tác quá nhanh. Vui lòng thử lại sau.\",\"error\":{\"type\":\"AI_RATE_LIMITED\",\"retryable\":true}}".getBytes(StandardCharsets.UTF_8);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        }
        // The ai_service route strips Authorization, so the caller identity travels separately:
        // rate admission stays keyed by peer address, while session grouping uses the token hash
        // when a bearer is present and the peer address otherwise.
        String owner=owner(exchange.getRequest(),peer);
        ServerHttpRequest request=exchange.getRequest().mutate()
                .headers(headers->{headers.remove(OWNER_HEADER);headers.set(OWNER_HEADER,owner);})
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private static String owner(ServerHttpRequest request,String peer){
        String authorization=request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if(authorization!=null && authorization.startsWith(BEARER) && authorization.length()>BEARER.length())
            return "t:"+sha256(authorization.substring(BEARER.length()));
        return "p:"+peer;
    }

    private static String sha256(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}
    }
}
