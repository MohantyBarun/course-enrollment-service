package com.student.studentservice.service;

import com.student.studentservice.dto.PagedResponseDTO;
import com.student.studentservice.dto.StudentRequestDTO;
import com.student.studentservice.dto.StudentResponseDTO;

public interface StudentService {
    StudentResponseDTO registerStudent(StudentRequestDTO requestDTO);

    PagedResponseDTO<StudentResponseDTO> getStudents(String search, String department, String branch,
                                                     int page, int size, String sortBy, String direction);

    StudentResponseDTO getStudentById(Long id);

    StudentResponseDTO getStudentByEmail(String email);

    StudentResponseDTO updateStudent(Long id, StudentRequestDTO requestDTO);

    void deleteStudent(Long id);
}
