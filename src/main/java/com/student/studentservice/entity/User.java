package com.student.studentservice.entity;

import com.student.studentservice.service.Roles;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@Data
@EntityListeners(AuditingEntityListener.class)
@Table(name="users")
public class User {
    @Id
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 150)
    private String email;

    @Column(name = "password", length = 255)
    private String password;

    @Column(name="temp_password", length = 255)
    private String tempPassword;

    @Column(name = "temp_password_attempts_count",nullable = false)
    private Integer tempPasswordAttemptsCount=0;

    @Column(name = "temp_password_expiry")
    private LocalDateTime tempPasswordExpiry;

    @Enumerated(EnumType.STRING)
    @Column(name="role", nullable = false)
    private Roles role;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive= true;

    @Column(name = "is_email_verified", nullable = false)
    private Boolean isEmailVerified=false;

    @Column(name = "is_first_time_login", nullable = false)
    private Boolean isFirstTimeLogin=true;

    @ToString.Exclude
    @OneToOne
    @MapsId
    @JoinColumn(name = "id")
    private Persons persons;

    @CreatedDate
    @Column(name = "created_At", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_At")
    private LocalDateTime updatedAt;
}