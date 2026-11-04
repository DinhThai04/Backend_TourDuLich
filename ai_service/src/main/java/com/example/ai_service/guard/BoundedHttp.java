package com.example.ai_service.guard;

import com.example.ai_service.model.ModelFailure;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;

public final class BoundedHttp {
    private static final int MAX_HTTP_ATTEMPTS=5;

    private BoundedHttp() {}
    public static ClientHttpRequestFactory requests(HttpClient client,Duration maximumRead) {
        return (uri,method)->{
            var trace=TurnTrace.current();
            if(trace!=null && trace.httpCalls.incrementAndGet()>MAX_HTTP_ATTEMPTS)
                throw new ModelFailure(ModelFailure.Type.AI_INVALID_OUTPUT);
            var factory=new JdkClientHttpRequestFactory(client);
            factory.setReadTimeout(TurnExecutor.remaining(maximumRead));
            return factory.createRequest(uri,method);
        };
    }
    public static ClientHttpRequestInterceptor responseLimit(int maxBytes) {
        return (request,body,execution)->new Limited(execution.execute(request,body),maxBytes);
    }
    private static final class Limited implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final int maximum;
        private InputStream bounded;
        private Limited(ClientHttpResponse delegate,int maximum) { this.delegate=delegate;this.maximum=maximum; }
        public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        public String getStatusText() throws IOException { return delegate.getStatusText(); }
        public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        public void close() { delegate.close(); }
        public InputStream getBody() throws IOException {
            if(bounded==null) {
                var source=delegate.getBody();
                bounded=new InputStream() {
                    int read;
                    private int count(int size) throws IOException { if(size>0 && (read+=size)>maximum) throw new IOException("Upstream body exceeds local limit");TurnExecutor.check();return size; }
                    public int read() throws IOException { TurnExecutor.check();int value=source.read();count(value<0?0:1);return value; }
                    public int read(byte[] bytes,int off,int length) throws IOException { TurnExecutor.check();return count(source.read(bytes,off,Math.min(length,maximum-read+1))); }
                    public void close() throws IOException { source.close(); }
                };
            }
            return bounded;
        }
    }
}
