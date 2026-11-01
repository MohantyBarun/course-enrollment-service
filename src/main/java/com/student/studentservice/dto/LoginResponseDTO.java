package com.student.studentservice.dto;

import com.student.studentservice.service.Roles;
import lombok.Data;

@Data
public class LoginResponseDTO {
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private Roles role;
    private String email;
    private Boolean isFirstTimeLogin;

}
