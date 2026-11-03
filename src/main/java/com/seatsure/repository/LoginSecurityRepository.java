package com.seatsure.repository;

import com.seatsure.entity.LoginSecurity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Failed-login counters and lockouts, one row per user (keyed by the user's id). */
public interface LoginSecurityRepository extends JpaRepository<LoginSecurity, Long> {
}
