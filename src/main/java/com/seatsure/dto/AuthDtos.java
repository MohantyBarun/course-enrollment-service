package com.seatsure.dto;

import com.seatsure.entity.User;
import com.seatsure.service.Roles;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    static final String PASSWORD_RULE = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,64}$";
    static final String PASSWORD_MESSAGE =
            "Password must be 8-64 characters with an uppercase letter, a lowercase letter, a number and a symbol";

    public record RegisterRequest(
            @NotBlank(message = "Name is required") @Size(max = 100, message = "Name is too long") String name,
            @NotBlank(message = "Email is required") @Email(message = "Invalid email") @Size(max = 150) String email,
            @Pattern(regexp = "^$|^[+]?[0-9 ]{10,15}$", message = "Invalid phone number") String phone,
            @NotBlank(message = "Password is required") @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE)
            String password) {
    }

    public record LoginRequest(
            @NotBlank(message = "Email is required") @Email(message = "Invalid email") String email,
            @NotBlank(message = "Password is required") String password) {
    }

    public record RefreshRequest(@NotBlank(message = "Refresh token is required") String refreshToken) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "Current password is required") String currentPassword,
            @NotBlank(message = "New password is required") @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE)
            String newPassword) {
    }

    public record UserView(Long id, String name, String email, String phone, Roles role) {
        public static UserView of(User user) {
            return new UserView(user.getId(), user.getName(), user.getEmail(), user.getPhone(), user.getRole());
        }
    }

    /** Returned by register, login, demo login and refresh (refresh tokens rotate on every use). */
    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                                UserView user) {
    }
}
