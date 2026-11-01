package com.student.studentservice.repository;

import com.student.studentservice.entity.Enrollment;
import com.student.studentservice.service.EnrollmentStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    Optional<Enrollment> findByStudentIdAndCourseId(Long studentId, Long courseId);

    @EntityGraph(attributePaths = "course")
    List<Enrollment> findByStudentId(Long studentId);

    /** Course roster with student details, fetched in one query. */
    @EntityGraph(attributePaths = {"student", "student.persons"})
    List<Enrollment> findByCourseIdAndStatusIn(Long courseId, Collection<EnrollmentStatus> statuses);

    @EntityGraph(attributePaths = {"student", "student.persons", "course"})
    List<Enrollment> findByStatus(EnrollmentStatus status);

    long countByCourseIdAndStatusIn(Long courseId, Collection<EnrollmentStatus> statuses);

    long countByStatus(EnrollmentStatus status);

    boolean existsByCourseId(Long courseId);

    /** Seats taken per course: rows of [courseId, count]. */
    @Query("SELECT e.course.id, COUNT(e) FROM Enrollment e WHERE e.status IN :statuses GROUP BY e.course.id")
    List<Object[]> countByCourseGrouped(@Param("statuses") Collection<EnrollmentStatus> statuses);

    /** Credits a student is currently taking in a term (for the per-term credit limit). */
    @Query("SELECT COALESCE(SUM(e.course.credits), 0) FROM Enrollment e "
            + "WHERE e.student.id = :studentId AND e.course.term = :term AND e.status = :status")
    long sumCredits(@Param("studentId") Long studentId, @Param("term") String term,
                    @Param("status") EnrollmentStatus status);

    @Modifying
    @Query("DELETE FROM Enrollment e WHERE e.student.id = :studentId")
    void deleteAllByStudentId(@Param("studentId") Long studentId);
}
