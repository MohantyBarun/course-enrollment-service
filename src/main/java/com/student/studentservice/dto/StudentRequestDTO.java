package com.student.studentservice.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

/**
 * Used for both creating and updating a student. There is deliberately no role field:
 * every account created through this endpoint is a student.
 */
@Data
public class StudentRequestDTO {
    //Data for persons table
    @NotBlank(message = "Name cannot be blank")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    private String name;

    @NotBlank(message = "Email cannot be blank")
    @Email(message = "Invalid format of email")
    @Size(max = 150, message = "Email cannot exceed 150 characters")
    private String email;

    //data for students table
    @NotBlank(message = "Department cannot be empty")
    @Size(max = 100, message = "Department cannot exceed 100 characters")
    private String department;

    @NotBlank(message = "Branch cannot be empty")
    @Size(max = 100, message = "Branch cannot exceed 100 characters")
    private String branch;

    @NotNull(message = "Date of Birth cannot be null")
    @Past(message = "Date of Birth must be in the past")
    private LocalDate dateOfBirth;

    @Size(max = 400, message = "Present address cannot exceed 400 characters")
    private String presentAddress;

    @Size(max = 400, message = "Permanent address cannot exceed 400 characters")
    private String permanentAddress;

}
