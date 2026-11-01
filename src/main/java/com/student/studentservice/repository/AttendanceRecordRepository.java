package com.student.studentservice.repository;

import com.student.studentservice.entity.AttendanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

    List<AttendanceRecord> findByEnrollmentCourseIdAndClassDate(Long courseId, LocalDate classDate);

    /** Class dates already recorded for a course, newest first. */
    @Query("SELECT DISTINCT a.classDate FROM AttendanceRecord a WHERE a.enrollment.course.id = :courseId "
            + "ORDER BY a.classDate DESC")
    List<LocalDate> findClassDates(@Param("courseId") Long courseId);

    /** Rows of [enrollmentId, classesHeld, classesAttended]. */
    @Query("SELECT a.enrollment.id, COUNT(a), SUM(CASE WHEN a.present = true THEN 1 ELSE 0 END) "
            + "FROM AttendanceRecord a WHERE a.enrollment.id IN :enrollmentIds GROUP BY a.enrollment.id")
    List<Object[]> summarize(@Param("enrollmentIds") Collection<Long> enrollmentIds);

    /** One row of [classesHeld, classesAttended] across every record. */
    @Query("SELECT COUNT(a), SUM(CASE WHEN a.present = true THEN 1 ELSE 0 END) FROM AttendanceRecord a")
    List<Object[]> totals();

    @Modifying
    @Query("DELETE FROM AttendanceRecord a WHERE a.enrollment.id IN "
            + "(SELECT e.id FROM Enrollment e WHERE e.student.id = :studentId)")
    void deleteAllByStudentId(@Param("studentId") Long studentId);
}
