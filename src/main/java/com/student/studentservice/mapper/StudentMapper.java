package com.student.studentservice.mapper;

import com.student.studentservice.dto.StudentRequestDTO;
import com.student.studentservice.dto.StudentResponseDTO;
import com.student.studentservice.entity.Persons;
import com.student.studentservice.entity.Student;

public final class StudentMapper {

    private StudentMapper() {
    }

    public static StudentResponseDTO toResponse(Student student) {
        Persons person = student.getPersons();
        StudentResponseDTO dto = new StudentResponseDTO();
        dto.setId(student.getId());
        dto.setName(person.getName());
        dto.setEmail(person.getEmail());
        dto.setBranch(student.getBranch());
        dto.setDepartment(student.getDepartment());
        dto.setDateOfBirth(student.getDateOfBirth());
        dto.setPresentAddress(student.getPresentAddress());
        dto.setPermanentAddress(student.getPermanentAddress());
        dto.setCreatedAt(student.getCreatedAt());
        dto.setUpdatedAt(student.getUpdatedAt());
        return dto;
    }

    /** Copies the student-table fields from the request onto the entity (used for create and update). */
    public static void applyStudentFields(StudentRequestDTO request, Student student) {
        student.setBranch(request.getBranch());
        student.setDepartment(request.getDepartment());
        student.setDateOfBirth(request.getDateOfBirth());
        student.setPresentAddress(request.getPresentAddress());
        student.setPermanentAddress(request.getPermanentAddress());
    }
}
