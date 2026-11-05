package com.example.tour_service;

import com.example.tour_service.repository.httpClient.FileClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.form.spring.SpringFormEncoder;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.mock.web.MockMultipartFile;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class FileGalleryFeignTest {
    @Test void multipartGalleryChangesReachFileServiceWithAndWithoutNewUploads() throws Exception {
        var body=new AtomicReference<String>();var contentType=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/file/media/tour/1/edit",exchange->{
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response="[{\"tourId\":\"1\",\"url\":\"new.jpg\"}]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);
            try(var output=exchange.getResponseBody()){output.write(response);}
        });server.start();
        try {
            var json=new ObjectMapper();
            var client=Feign.builder().contract(new SpringMvcContract()).encoder(new SpringFormEncoder())
                    .decoder((response,type)->json.readValue(response.body().asInputStream(),json.constructType(type)))
                    .target(FileClient.class,"http://127.0.0.1:"+server.getAddress().getPort());
            var file=new MockMultipartFile("files","new.png","image/png","image-fixture".getBytes(StandardCharsets.UTF_8));
            assertEquals("new.jpg",client.editTourImages("1","[\"old.jpg\"]",List.of(file)).getFirst().getUrl());
            assertTrue(contentType.get().startsWith("multipart/form-data;"));
            assertTrue(body.get().contains("name=\"changes\""));assertTrue(body.get().contains("[\"old.jpg\"]"));
            assertTrue(body.get().contains("filename=\"new.png\""));assertTrue(body.get().contains("image-fixture"));
            client.editTourImages("1","[\"old.jpg\"]",null);
            assertTrue(body.get().contains("[\"old.jpg\"]"));assertFalse(body.get().contains("filename="));
        } finally {server.stop(0);}
    }
}
