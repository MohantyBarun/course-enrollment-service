package com.student.studentservice.controller;

import com.student.studentservice.insights.NotificationFeedService;
import com.student.studentservice.insights.SystemStatusService;
import com.student.studentservice.insights.TestEmailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only views of the backend's internals for the admin UI. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "Admin insights")
public class InsightsController {

    private final NotificationFeedService notificationFeed;
    private final SystemStatusService systemStatus;
    private final TestEmailService testEmail;

    public record TestEmailRequest(String to) {
    }

    @Operation(summary = "Latest notifications and where each one is in the outbox pipeline (admin)")
    @GetMapping("/notifications")
    public NotificationFeedService.Feed notifications() {
        return notificationFeed.latest();
    }

    @Operation(summary = "Queue a test email through the outbox (admin)",
            description = "Defaults to the first allowlisted address. On a restricted setup only allowlisted "
                    + "recipients are accepted, and test emails are limited to one every few minutes.")
    @PostMapping("/notifications/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TestEmailService.Queued sendTestEmail(@RequestBody(required = false) TestEmailRequest request,
                                                 @io.swagger.v3.oas.annotations.Parameter(hidden = true) Authentication auth) {
        return testEmail.queue(request == null ? null : request.to(), auth.getName());
    }

    @Operation(summary = "Live system snapshot: traffic, caches, outbox, rate limits, circuit breakers (admin)")
    @GetMapping("/system")
    public SystemStatusService.Snapshot system() {
        return systemStatus.snapshot();
    }
}
