package com.example.file_service.service;

import com.example.file_service.config.AvatarWritePermissionClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Delegates avatar write permission to Auth, which owns account/role data. */
@Component
public class AvatarWritePolicy {
    private final AvatarWritePermissionClient client;

    public AvatarWritePolicy(AvatarWritePermissionClient client) {
        this.client = client;
    }

    public void requireWritable(String ownerId, boolean lockTarget) {
        client.requireWritable(ownerId, lockTarget);
    }
}