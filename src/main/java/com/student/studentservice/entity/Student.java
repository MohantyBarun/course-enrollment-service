package com.student.studentservice.entity;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@Table(name = "students")
public class Student {
    @Id
    private Long id;

    @Column(name = "branch", nullable = false)
    private String branch;

    @Column(name = "department", nullable = false)
    private String department;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "present_address", length = 400)
    private String presentAddress;

    @Column(name = "permanent_address", length = 400)
    private String permanentAddress;

    @ToString.Exclude
    @OneToOne
    @MapsId
    @JoinColumn(name = "id")
    private Persons persons;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
