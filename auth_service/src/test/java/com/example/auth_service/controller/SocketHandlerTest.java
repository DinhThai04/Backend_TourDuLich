package com.example.auth_service.controller;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SocketHandlerTest {
    private final SocketHandler handler = new SocketHandler(mock(SocketIOServer.class));
    private final SocketIOClient client = mock(SocketIOClient.class);
    private final Set<String> rooms = new HashSet<>();

    @BeforeEach
    void setup() {
        when(client.getSessionId()).thenReturn(UUID.randomUUID());
        when(client.getAllRooms()).thenReturn(rooms);
        doAnswer(invocation -> rooms.add(invocation.getArgument(0))).when(client).joinRoom(anyString());
        doAnswer(invocation -> rooms.remove(invocation.getArgument(0))).when(client).leaveRoom(anyString());
    }

    @Test
    void joinsOnlyTourRooms() {
        handler.onJoinRoom(client, "tour_42");
        verify(client).joinRoom("tour_42");

        for (String invalid : new String[]{"tour_", "tour_abc", "room_1", "tour_12345678901234567890", null}) {
            handler.onJoinRoom(client, invalid);
            verify(client, never()).joinRoom(invalid);
        }
    }

    @Test
    void capsTourRoomsPerClient() {
        // The namespace room ("/") is always present but must not consume the tour-room budget.
        rooms.add("/");
        for (int index = 1; index <= 4; index++) {
            handler.onJoinRoom(client, "tour_" + index);
        }
        handler.onJoinRoom(client, "tour_5");
        verify(client).joinRoom("tour_5");

        handler.onJoinRoom(client, "tour_6");
        verify(client, never()).joinRoom("tour_6");
    }

    @Test
    void leavesOnlyRoomsTheClientAlreadyJoined() {
        rooms.add("tour_7");
        handler.onLeaveRoom(client, "tour_8");
        verify(client, never()).leaveRoom("tour_8");

        handler.onLeaveRoom(client, "tour_7");
        verify(client).leaveRoom("tour_7");

        handler.onLeaveRoom(client, "not-a-room");
        verify(client, never()).leaveRoom("not-a-room");
    }
}
