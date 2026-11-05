package com.example.ai_service.web;

import com.example.ai_service.security.AiInputPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounded buffering before MVC JSON parsing, including chunked requests without Content-Length. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class ChatBodyLimit extends OncePerRequestFilter {
    private static final int MAX_UTF8_BYTES_PER_POINT=4;
    private static final int ENVELOPE_BYTES=1024;
    /** Must cover every message the code-point policy accepts: 16000 x 4 UTF-8 bytes + the JSON envelope. */
    static final int MAX=AiInputPolicy.MAX_MESSAGE_POINTS*MAX_UTF8_BYTES_PER_POINT+ENVELOPE_BYTES;
    private static final int MAX_SUPPORTED_BYTES=96*1024;
    private final ObjectMapper json;
    public ChatBodyLimit(ObjectMapper json) {
        if(AiInputPolicy.MAX_MESSAGE_POINTS<1 || AiInputPolicy.MAX_MESSAGE_POINTS>AiInputPolicy.MAX_SUPPORTED_MESSAGE_POINTS || MAX>MAX_SUPPORTED_BYTES)
            throw new IllegalStateException("Chat body limit outside supported bounds.");
        this.json=json;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if(!"POST".equals(request.getMethod())) { chain.doFilter(request,response); return; }
        if(request.getContentLengthLong()>MAX) { tooLarge(response); return; }
        byte[] bytes=request.getInputStream().readNBytes(MAX+1);
        if(bytes.length>MAX) { tooLarge(response); return; }
        var wrapped=new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input=new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] b,int off,int len) { return input.read(b,off,len); }
                    @Override public boolean isFinished() { return input.available()==0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous JSON only"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
        };
        chain.doFilter(wrapped,response);
    }
    private void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(),ChatErrors.response(413,"PAYLOAD_TOO_LARGE",false,null,null).getBody());
    }
}
