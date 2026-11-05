package com.example.auth_service.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.auth_service.entity.UserAccountEvent;
import com.example.auth_service.repository.UserAccountEventRepository;

import lombok.RequiredArgsConstructor;

/**
 * Revokes every session of a user by appending an audit event, because the session epoch is the
 * newest event id for that user. Replay detection runs in its own transaction: the rejected
 * refresh request rolls back its own transaction, and a concurrent duplicate insert may already
 * mark it rollback-only. Authorization changes instead join the caller's transaction.
 */
@Service
@RequiredArgsConstructor
public class SessionEpochService {
    static final String REPLAY_ACTION = "TOKEN_REPLAY";
    /** Exactly 20 characters: user_account_event.action is varchar(20), so a longer name fails on insert. */
    static final String AUTHORIZATION_CHANGE_ACTION = "AUTHORIZATION_CHANGE";

    private final UserAccountEventRepository events;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(long userId, long tokenEpoch) {
        // A second replay of the same family arrives with an epoch already below the current one:
        // every token has been revoked, so do not append another event.
        if (tokenEpoch < events.sessionEpoch(userId)) {
            return;
        }
        UserAccountEvent event = new UserAccountEvent();
        event.setTargetUserId(userId);
        event.setActorId(userId);
        event.setAction(REPLAY_ACTION);
        // The audit reason is shown to admins in the account history, so it stays Vietnamese.
        event.setReason("Phát hiện dùng lại refresh token, đã thu hồi mọi phiên của tài khoản");
        event.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")));
        events.save(event);
    }

    /**
     * Invalidates every token of the given accounts when their permissions changed. Unlike replay
     * detection this joins the caller's transaction, so the revocation commits only together with
     * the authorization change and never leaves users logged out by a rolled-back edit. The admin
     * who made the change is recorded as the actor because the account history shows who did it.
     * MANDATORY is the guard for that contract: called outside the authorization transaction, each
     * event would commit on its own and log accounts out even when the edit itself was rolled back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAll(Collection<Long> userIds, String reason) {
        if (userIds.isEmpty()) {
            return;
        }
        Long actingAdmin = currentAdminId();
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        List<UserAccountEvent> pending = userIds.stream().distinct().map(userId -> {
            UserAccountEvent event = new UserAccountEvent();
            event.setTargetUserId(userId);
            event.setActorId(actingAdmin == null ? userId : actingAdmin);
            event.setAction(AUTHORIZATION_CHANGE_ACTION);
            event.setReason(reason);
            event.setCreatedAt(now);
            return event;
        }).toList();
        events.saveAll(pending);
    }

    private static Long currentAdminId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        try {
            return Long.parseLong(auth.getName());
        } catch (NumberFormatException anonymousOrSystem) {
            return null;
        }
    }
}
