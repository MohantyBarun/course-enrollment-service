package com.student.studentservice.dto;

import jakarta.validation.constraints.*;

/** Request/response records for the course catalog. */
public final class CourseDtos {

    private CourseDtos() {
    }

    public record CourseRequest(
            @NotBlank(message = "Course code is required")
            @Size(max = 20, message = "Course code cannot exceed 20 characters")
            @Pattern(regexp = "^[A-Za-z0-9-]+$", message = "Use letters, numbers and dashes only")
            String code,

            @NotBlank(message = "Title is required")
            @Size(max = 150, message = "Title cannot exceed 150 characters")
            String title,

            @Size(max = 1000, message = "Description cannot exceed 1000 characters")
            String description,

            @NotNull(message = "Credits are required")
            @Min(value = 1, message = "Credits must be between 1 and 6")
            @Max(value = 6, message = "Credits must be between 1 and 6")
            Integer credits,

            @NotBlank(message = "Department is required")
            @Size(max = 100)
            String department,

            @NotBlank(message = "Term is required")
            @Size(max = 50)
            String term,

            @NotNull(message = "Capacity is required")
            @Min(value = 1, message = "Capacity must be between 1 and 500")
            @Max(value = 500, message = "Capacity must be between 1 and 500")
            Integer capacity,

            @Size(max = 100) String instructor,

            @Size(max = 100) String schedule,

            Boolean enrollmentOpen
    ) {
    }

    public record CourseResponse(
            Long id,
            String code,
            String title,
            String description,
            int credits,
            String department,
            String term,
            int capacity,
            long enrolledCount,
            long seatsLeft,
            String instructor,
            String schedule,
            boolean enrollmentOpen
    ) {
    }

    /** Compact course info embedded in enrollment views. */
    public record CourseSummary(Long id, String code, String title, int credits, String term,
                                String instructor, String schedule) {
    }
}
