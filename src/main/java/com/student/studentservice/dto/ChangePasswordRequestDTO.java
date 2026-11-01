package com.student.studentservice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequestDTO {
    // No email field: the account is taken from the authenticated JWT, so a user
    // can only ever change their own password.

    @NotNull(message = "Old password cannot be null")
    private String oldPassword;

    @NotNull(message = "New password cannot be null")
    @Size(min = 8, max = 20, message = "New password must be between 8 and 20 characters")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$",
            message = "Password must have uppercase, lowercase, number and special character"
    )
    private String newPassword;

    @NotNull(message = "Re-confirm New password cannot be null")
    private String confirmNewPassword;

}
