package com.student.studentservice.support;

import com.student.studentservice.dto.StudentRequestDTO;

import java.time.LocalDate;
import java.util.UUID;

/** Unique test data, so integration tests sharing a database don't collide. */
public final class TestStudents {

    private TestStudents() {
    }

    public static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static StudentRequestDTO request(String email) {
        StudentRequestDTO dto = new StudentRequestDTO();
        dto.setName("Student " + email.substring(0, email.indexOf('@')));
        dto.setEmail(email);
        dto.setDepartment("Computer Science");
        dto.setBranch("CSE");
        dto.setDateOfBirth(LocalDate.of(2004, 1, 1));
        return dto;
    }
}
