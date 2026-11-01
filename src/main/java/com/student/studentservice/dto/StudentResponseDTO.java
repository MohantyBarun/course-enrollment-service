package com.student.studentservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class StudentResponseDTO {
    private Long id;
    private String name; //person's table
    private String email; //person's table
    private String branch; //student
    private String department;
    private LocalDate dateOfBirth;
    private String presentAddress;
    private String permanentAddress;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Only populated in the response to POST /students, so the admin can hand it to the student
    // (until email delivery exists). Omitted from every other response.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String temporaryPassword;
}
