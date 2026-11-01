package com.student.studentservice.dto;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequestDTO {

    @NotBlank(message = "Email cannot be blank")
    @Email(message = "Invalid format of email")
    private String email;

    @NotBlank(message = "Password cannot be empty")
    private String password;

}
