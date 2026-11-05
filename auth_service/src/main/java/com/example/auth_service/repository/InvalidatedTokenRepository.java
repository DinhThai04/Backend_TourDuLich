package com.example.auth_service.repository;

import java.util.Date;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.auth_service.entity.InvalidatedToken;



@Repository
public interface InvalidatedTokenRepository extends JpaRepository<InvalidatedToken, String> {
    /** Removes logout records whose token has already expired, so the blacklist stays small. */
    long deleteByExpiryTimeBefore(Date cutoff);
}