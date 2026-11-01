package com.student.studentservice.controller;

import com.student.studentservice.config.AppConfig;
import com.student.studentservice.dto.ChangePasswordRequestDTO;
import com.student.studentservice.dto.LoginResponseDTO;
import com.student.studentservice.dto.RefreshTokenResponseDTO;
import com.student.studentservice.exception.AccountLockedException;
import com.student.studentservice.exception.AuthenticationFailedException;
import com.student.studentservice.service.AuthService;
import com.student.studentservice.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.student.studentservice.controller.JwtTestTokens.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({AppConfig.class, JwtUtil.class})
@TestPropertySource(properties = {
        JWT_PROPERTIES_SECRET,
        "jwt.access.expiration=900000",
        "jwt.refresh.expiration=604800000"
})
class AuthControllerTest {

    private static final String CHANGE_PASSWORD_BODY = """
            {"oldPassword":"Temp#1234","newPassword":"NewPass@123","confirmNewPassword":"NewPass@123"}""";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;

    @MockitoBean private AuthService authService;

    @Test
    void login_isPublic() throws Exception {
        LoginResponseDTO response = new LoginResponseDTO();
        response.setAccessToken("access");
        when(authService.userLogin(any())).thenReturn(response);

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@x.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"));
    }

    @Test
    void login_invalidBody_returns400() throws Exception {
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
        verifyNoInteractions(authService);
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        when(authService.userLogin(any())).thenThrow(new AuthenticationFailedException("Invalid password"));

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@x.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid password"));
    }

    @Test
    void login_blockedAccount_returns403() throws Exception {
        when(authService.userLogin(any())).thenThrow(new AccountLockedException("Account temporarily blocked"));

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@x.com\",\"password\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unexpectedError_returns500WithoutLeakingDetails() throws Exception {
        when(authService.userLogin(any())).thenThrow(new IllegalStateException("db password is hunter2"));

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@x.com\",\"password\":\"x\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Something went wrong. Please try again later."));
    }

    @Test
    @DisplayName("regression: refresh works without an access token (it's authorised by the refresh token)")
    void refresh_isPublic() throws Exception {
        when(authService.refreshToken(any())).thenReturn(new RefreshTokenResponseDTO("new-access"));

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"r\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"));
    }

    @Test
    void refresh_blankToken_returns400() throws Exception {
        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(authService);
    }

    @Test
    void logout_isPublic() throws Exception {
        when(authService.logout(any())).thenReturn("Logged out successfully");

        mockMvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"r\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void changePassword_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/auth/change-password").contentType(MediaType.APPLICATION_JSON)
                        .content(CHANGE_PASSWORD_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("users on a temporary password can still reach change-password")
    void changePassword_allowedWithRestrictedToken() throws Exception {
        when(authService.changePassword(any(), any())).thenReturn("Password changed successfully");

        mockMvc.perform(post("/auth/change-password").header("Authorization", restrictedStudent(jwtUtil))
                        .contentType(MediaType.APPLICATION_JSON).content(CHANGE_PASSWORD_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("regression: the account comes from the JWT, never from the request body")
    void changePassword_usesEmailFromToken() throws Exception {
        String bodyTargetingAdmin = """
                {"email":"admin@college.com","oldPassword":"Temp#1234","newPassword":"NewPass@123","confirmNewPassword":"NewPass@123"}""";

        mockMvc.perform(post("/auth/change-password").header("Authorization", student(jwtUtil))
                        .contentType(MediaType.APPLICATION_JSON).content(bodyTargetingAdmin))
                .andExpect(status().isOk());

        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        verify(authService).changePassword(email.capture(), any(ChangePasswordRequestDTO.class));
        assertThat(email.getValue()).isEqualTo(STUDENT_EMAIL);
    }

    @Test
    @DisplayName("regression: the password policy is enforced (@Valid)")
    void changePassword_weakPassword_returns400() throws Exception {
        mockMvc.perform(post("/auth/change-password").header("Authorization", student(jwtUtil))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"x\",\"newPassword\":\"weak\",\"confirmNewPassword\":\"weak\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").exists());
        verifyNoInteractions(authService);
    }
}
