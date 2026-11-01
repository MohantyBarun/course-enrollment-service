package com.student.studentservice.entity;

import com.student.studentservice.service.EnrollmentStatus;
import com.student.studentservice.service.Grade;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/** A student's registration in a course; also carries the final grade. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@Table(name = "enrollments",
        uniqueConstraints = @UniqueConstraint(name = "uk_enrollment_student_course",
                columnNames = {"student_id", "course_id"}))
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EnrollmentStatus status = EnrollmentStatus.ENROLLED;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", length = 10)
    private Grade grade;

    @CreatedDate
    @Column(name = "enrolled_at", updatable = false)
    private LocalDateTime enrolledAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
