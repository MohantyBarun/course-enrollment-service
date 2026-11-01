package com.seatsure.controller;

import com.seatsure.config.AppConfig;
import com.seatsure.dto.AuthDtos.ChangePasswordRequest;
import com.seatsure.dto.AuthDtos.TokenResponse;
import com.seatsure.dto.AuthDtos.UserView;
import com.seatsure.exception.AccountLockedException;
import com.seatsure.exception.AuthenticationFailedException;
import com.seatsure.exception.ConflictException;
import com.seatsure.service.AuthService;
import com.seatsure.service.Roles;
import com.seatsure.service.TokenService;
import com.seatsure.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.seatsure.controller.JwtTestTokens.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({AppConfig.class, JwtUtil.class})
@TestPropertySource(properties = {JWT_PROPERTIES_SECRET, "jwt.access.expiration=900000"})
class AuthControllerTest {

    private static final TokenResponse TOKENS = new TokenResponse("access", "refresh", "Bearer", 900,
            new UserView(7L, "Asha", "asha@seatsure.test", null, Roles.ROLE_CUSTOMER));

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;

    @MockitoBean private AuthService authService;
    @MockitoBean private TokenService tokenService;

    @Test
    void register_isPublicAndReturns201() throws Exception {
        when(authService.register(any())).thenReturn(TOKENS);

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Asha\",\"email\":\"asha@seatsure.test\",\"password\":\"Secret#123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.user.role").value("ROLE_CUSTOMER"));
    }

    @Test
    void register_weakPassword_returns400() throws Exception {
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Asha\",\"email\":\"asha@seatsure.test\",\"password\":\"weak\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        verifyNoInteractions(authService);
    }

    @Test
    void register_existingEmail_returns409() throws Exception {
        when(authService.register(any())).thenThrow(new ConflictException("An account with this email already exists."));

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Asha\",\"email\":\"asha@seatsure.test\",\"password\":\"Secret#123\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        when(authService.login(any())).thenThrow(new AuthenticationFailedException("Invalid email or password"));

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@x.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid email or password"));
    }

    @Test
    void login_blockedAccount_returns403() throws Exception {
        when(authService.login(any())).thenThrow(new AccountLockedException("Too many failed attempts."));

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@x.com\",\"password\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unexpectedError_returns500WithoutLeakingDetails() throws Exception {
        when(authService.login(any())).thenThrow(new IllegalStateException("db password is hunter2"));

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@x.com\",\"password\":\"x\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Something went wrong. Please try again later."));
    }

    @Test
    @DisplayName("refresh and logout work without an access token (they're authorised by the refresh token)")
    void refreshAndLogout_arePublic() throws Exception {
        when(tokenService.rotate("r")).thenReturn(TOKENS);

        mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"r\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("refresh"));
        mockMvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"r\"}"))
                .andExpect(status().isNoContent());
        verify(tokenService).revokeSession("r");
    }

    @Test
    void me_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the account changed is the one in the token, never one named in the body")
    void changePassword_usesUserFromToken() throws Exception {
        mockMvc.perform(post("/auth/change-password").header("Authorization", customer(jwtUtil))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"currentPassword\":\"Old#12345\",\"newPassword\":\"NewPass@123\"}"))
                .andExpect(status().isNoContent());

        verify(authService).changePassword(eq(CUSTOMER_ID), any(ChangePasswordRequest.class));
    }
}
