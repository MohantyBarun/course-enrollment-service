package com.student.studentservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Whether an enrolled student attended a given class date. One row per enrollment per date. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "attendance_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_attendance_enrollment_date",
                columnNames = {"enrollment_id", "class_date"}))
public class AttendanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private Enrollment enrollment;

    @Column(name = "class_date", nullable = false)
    private LocalDate classDate;

    @Column(name = "present", nullable = false)
    private Boolean present;

    public AttendanceRecord(Enrollment enrollment, LocalDate classDate, boolean present) {
        this.enrollment = enrollment;
        this.classDate = classDate;
        this.present = present;
    }
}
