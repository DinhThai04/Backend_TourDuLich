package com.example.auth_service.service;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.auth_service.repository.InvalidatedTokenRepository;

import lombok.RequiredArgsConstructor;

/**
 * Deletes blacklisted token ids once the token itself has expired.
 * A token past its expiry is rejected by signature verification anyway,
 * so keeping the row would only grow the table forever.
 */
@Component
@RequiredArgsConstructor
public class InvalidatedTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(InvalidatedTokenCleanup.class);

    private final InvalidatedTokenRepository invalidatedTokenRepository;

    @Scheduled(fixedDelayString = "${app.auth.invalidated-token-cleanup-ms:3600000}", initialDelay = 60000)
    @Transactional
    public void removeExpiredTokens() {
        long removed = invalidatedTokenRepository.deleteByExpiryTimeBefore(new Date());
        if (removed > 0) {
            log.info("Removed {} expired invalidated token(s)", removed);
        }
    }
}
