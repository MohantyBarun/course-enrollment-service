package com.student.studentservice.repository;

import com.student.studentservice.entity.Student;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable filters for the student list endpoint. Each method returns null when its
 * filter is not supplied, so callers can combine only the filters that are present.
 */
public final class StudentSpecifications {

    private StudentSpecifications() {
    }

    /** Case-insensitive partial match on name or email. */
    public static Specification<Student> nameOrEmailContains(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String pattern = "%" + search.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("persons").get("name")), pattern),
                cb.like(cb.lower(root.get("persons").get("email")), pattern)
        );
    }

    public static Specification<Student> departmentEquals(String department) {
        if (department == null || department.isBlank()) {
            return null;
        }
        return (root, query, cb) -> cb.equal(cb.lower(root.get("department")), department.trim().toLowerCase());
    }

    public static Specification<Student> branchEquals(String branch) {
        if (branch == null || branch.isBlank()) {
            return null;
        }
        return (root, query, cb) -> cb.equal(cb.lower(root.get("branch")), branch.trim().toLowerCase());
    }
}
