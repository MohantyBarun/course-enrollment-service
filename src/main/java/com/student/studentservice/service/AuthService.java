package com.student.studentservice.service;
import com.student.studentservice.dto.*;

public interface AuthService {
    LoginResponseDTO userLogin(LoginRequestDTO loginRequestDTO);
    String changePassword(String email, ChangePasswordRequestDTO changePasswordRequestDTO);
    RefreshTokenResponseDTO refreshToken(RefreshTokenRequestDTO request);
    String logout(RefreshTokenRequestDTO request);
}
