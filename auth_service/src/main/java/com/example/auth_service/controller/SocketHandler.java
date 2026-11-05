package com.example.auth_service.controller;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnConnect;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.corundumstudio.socketio.annotation.OnEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SocketHandler {
    /** The React client joins exactly "tour_${id}". */
    private static final Pattern TOUR_ROOM = Pattern.compile("tour_\\d{1,19}");
    private static final int MAX_TOUR_ROOMS = 5;

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
        if (room == null || !TOUR_ROOM.matcher(room).matches()) {
            log.warn("Ignoring invalid room {} for client {}", room, client.getSessionId());
            return;
        }
        Set<String> joined = client.getAllRooms();
        if (joined.contains(room)) {
            return;
        }
        long tourRooms = joined.stream().filter(name -> TOUR_ROOM.matcher(name).matches()).count();
        if (tourRooms >= MAX_TOUR_ROOMS) {
            log.warn("Client {} reached the {} room limit; ignoring {}", client.getSessionId(), MAX_TOUR_ROOMS, room);
            return;
        }
        client.joinRoom(room);
        log.info("Client {} joined room {}", client.getSessionId(), room);
    }

    @OnEvent("leave_room")
    public void onLeaveRoom(SocketIOClient client, String room) {
        if (room == null || !TOUR_ROOM.matcher(room).matches() || !client.getAllRooms().contains(room)) {
            log.warn("Ignoring leave for room {} not joined by client {}", room, client.getSessionId());
            return;
        }
        client.leaveRoom(room);
        log.info("Client {} left room {}", client.getSessionId(), room);
    }

    @PostConstruct
    public void startServer() {
        server.addListeners(this);
        server.start();
        log.info("Socket server started");
    }

    @PreDestroy
    public void stopServer() {
        server.stop();
        log.info("Socket server stopped");
    }
}
