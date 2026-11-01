package com.student.studentservice.repository;

import com.student.studentservice.entity.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long>, JpaSpecificationExecutor<Student> {

    // Fetch the owning Persons row in the same query to avoid N+1 selects when listing.
    @Override
    @EntityGraph(attributePaths = "persons")
    Page<Student> findAll(Specification<Student> spec, Pageable pageable);

    /** Rows of [department, studentCount], largest first. */
    @Query("SELECT s.department, COUNT(s) FROM Student s GROUP BY s.department ORDER BY COUNT(s) DESC")
    List<Object[]> countByDepartment();

    /** SELECT ... FOR UPDATE: serializes a student's concurrent enrollments (per-term credit limit). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Student s WHERE s.id = :id")
    Optional<Student> findByIdForUpdate(@Param("id") Long id);
}
