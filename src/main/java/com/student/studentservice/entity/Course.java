package com.student.studentservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@Table(name = "courses")
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "credits", nullable = false)
    private Integer credits;

    @Column(name = "department", nullable = false, length = 100)
    private String department;

    /** Academic term, e.g. "Autumn 2026". */
    @Column(name = "term", nullable = false, length = 50)
    private String term;

    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    @Column(name = "instructor", length = 100)
    private String instructor;

    /** Free-text timetable slot, e.g. "Mon/Wed 10:00–11:30". */
    @Column(name = "schedule", length = 100)
    private String schedule;

    @Column(name = "enrollment_open", nullable = false)
    private Boolean enrollmentOpen = true;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
