package com.student.studentservice.repository;

import com.student.studentservice.entity.RefreshToken;
import com.student.studentservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    // Used during logout and email change — revoke all tokens for a user
    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken rt SET rt.isRevoked = true WHERE rt.user = :user")
    void revokeAllTokensByUser(User user);

    // Used when a user is deleted — refresh_tokens has a FK to users
    @Modifying
    @Transactional
    @Query("DELETE FROM RefreshToken rt WHERE rt.user = :user")
    void deleteAllByUser(User user);
}
