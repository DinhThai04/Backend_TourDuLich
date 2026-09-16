package com.example.auth_service.controller;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnConnect;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.example.auth_service.dto.response.ReviewResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SocketHandler {
    SocketIOServer server;

    @OnConnect
    public void clientConnected(SocketIOClient client) {
        log.info("Client connected: {}", client.getSessionId());
    }

    @OnDisconnect
    public void clientDisconnected(SocketIOClient client) {
        log.info("Client disconnected: {}", client.getSessionId());
    }

    @OnEvent("join_room")
    public void onJoinRoom(SocketIOClient client, String room) {
        client.joinRoom(room);
        log.info("Client {} joined room {}", client.getSessionId(), room);
    }

    @OnEvent("leave_room")
    public void onLeaveRoom(SocketIOClient client, String room) {
        client.leaveRoom(room);
        log.info("Client {} left room {}", client.getSessionId(), room);
    }

    // ⚡️ THÊM MỚI: Hàm phát realtime bình luận
    public void sendNewReview(ReviewResponse review) {
        String room = "tour_" + review.getTourId();
        log.info("📢 Sending new_review to room: {}", room);
        server.getRoomOperations(room).sendEvent("new_review", review);
    }

    @PostConstruct
    public void startServer() {
        server.addListeners(this);
        server.start();
        log.info("✅ Socket server started");
    }

    @PreDestroy
    public void stopServer() {
        server.stop();
        log.info("🛑 Socket server stopped");
    }
}
