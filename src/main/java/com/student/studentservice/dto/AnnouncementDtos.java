package com.student.studentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class AnnouncementDtos {

    private AnnouncementDtos() {
    }

    public record AnnouncementRequest(
            @NotBlank(message = "Title is required")
            @Size(max = 150, message = "Title cannot exceed 150 characters")
            String title,

            @NotBlank(message = "Message is required")
            @Size(max = 2000, message = "Message cannot exceed 2000 characters")
            String body,

            /* Blank or null = everyone */
            @Size(max = 100)
            String audienceDepartment
    ) {
    }

    public record AnnouncementResponse(Long id, String title, String body, String audienceDepartment,
                                       String authorName, LocalDateTime createdAt) {
    }
}
