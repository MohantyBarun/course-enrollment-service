package com.student.studentservice.repository;

import com.student.studentservice.entity.LoginSecurity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoginSecurityRepository extends JpaRepository<LoginSecurity, Long> {
        Optional<LoginSecurity> findByUserId(Long userId); // find by user id
}
