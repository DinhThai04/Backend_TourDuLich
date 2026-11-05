package com.example.file_service.repository;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CloudinaryImageReaderTest {
    final String url="https://res.cloudinary.com/demo/image/upload/v1/tour.png";
    @Test void rejectsOtherOriginsCredentialsTraversalAndRedirectEndpoints() {
        for(String bad:new String[]{"http://res.cloudinary.com/demo/image/upload/a.png","https://res.cloudinary.com/other/image/upload/a.png","https://localhost/a.png","https://res.cloudinary.com@evil.test/demo/image/upload/a.png","https://res.cloudinary.com/demo/image/upload/../fetch/a.png","https://res.cloudinary.com/demo/image/upload/%2e%2e/a.png",url+"?url=http://localhost"})
            assertThrows(IllegalArgumentException.class,()->CloudinaryImageReader.validateUrl(bad,"demo"));
        assertEquals("res.cloudinary.com",CloudinaryImageReader.validateUrl(url,"demo").getHost());
    }
    @Test void missingLengthCannotBypassStreamingLimit() {
        var endless=new InputStream(){public int read(){return 1;}public int read(byte[] b){java.util.Arrays.fill(b,(byte)1);return b.length;}};
        assertThrows(IOException.class,()->CloudinaryImageReader.readBounded(endless));
    }
    @Test void redirectsAndOversizedResponsesAreNotRead() throws Exception {
        var connection=mock(HttpURLConnection.class);
        var reader=new CloudinaryImageReader(){protected HttpURLConnection open(URI ignored){return connection;}};
        when(connection.getResponseCode()).thenReturn(302);assertThrows(IOException.class,()->reader.read(url,"demo"));
        verify(connection,never()).getInputStream();verify(connection).setInstanceFollowRedirects(false);
        when(connection.getResponseCode()).thenReturn(200);when(connection.getContentType()).thenReturn("image/png");when(connection.getContentLengthLong()).thenReturn(6L*1024*1024);
        assertThrows(IOException.class,()->reader.read(url,"demo"));verify(connection,never()).getInputStream();
    }
    @Test void validResponseHasTimeoutsAndIsClosed() throws Exception {
        var connection=mock(HttpURLConnection.class);var input=spy(new ByteArrayInputStream(new byte[]{1,2,3}));
        when(connection.getResponseCode()).thenReturn(200);when(connection.getContentType()).thenReturn("image/png");when(connection.getContentLengthLong()).thenReturn(-1L);when(connection.getInputStream()).thenReturn(input);
        var reader=new CloudinaryImageReader(){protected HttpURLConnection open(URI ignored){return connection;}};
        assertArrayEquals(new byte[]{1,2,3},reader.read(url,"demo"));verify(connection).setConnectTimeout(3000);verify(connection).setReadTimeout(5000);verify(connection).disconnect();verify(input).close();
    }
}
