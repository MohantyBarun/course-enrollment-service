package com.seatsure.repository;

import com.seatsure.entity.LoginSecurity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Failed-login counters and lockouts, one row per user (keyed by the user's id). */
public interface LoginSecurityRepository extends JpaRepository<LoginSecurity, Long> {

    /** Locked for the whole login attempt, so concurrent wrong passwords are all counted. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM LoginSecurity s WHERE s.id = :userId")
    Optional<LoginSecurity> findByIdForUpdate(@Param("userId") Long userId);
}
