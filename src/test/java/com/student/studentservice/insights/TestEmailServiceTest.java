package com.student.studentservice.insights;

import com.student.studentservice.entity.OutboxEvent;
import com.student.studentservice.exception.BadRequestException;
import com.student.studentservice.exception.ConflictException;
import com.student.studentservice.notification.AllowlistEmailSender;
import com.student.studentservice.notification.EmailSender;
import com.student.studentservice.notification.LoggingEmailSender;
import com.student.studentservice.outbox.OutboxEvents;
import com.student.studentservice.outbox.OutboxService;
import com.student.studentservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TestEmailServiceTest {

    private final OutboxService outbox = mock(OutboxService.class);
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    private TestEmailService service(EmailSender sender) {
        when(repository.findFirstByEventTypeOrderByCreatedAtDesc(OutboxEvents.TEST_EMAIL)).thenReturn(Optional.empty());
        return new TestEmailService(outbox, repository, sender, Duration.ofMinutes(5));
    }

    private static EmailSender allowlist(String entries) {
        EmailSender smtp = mock(EmailSender.class);
        when(smtp.mode()).thenReturn("SMTP");
        return new AllowlistEmailSender(smtp, entries);
    }

    @Test
    void defaultsToTheFirstAllowlistedAddress() {
        TestEmailService.Queued queued = service(allowlist("@college.edu, owner@gmail.com")).queue(null, "demo.admin@x.dev");

        assertThat(queued.recipient()).isEqualTo("ow***@gmail.com");
        verify(outbox).record(eq("Notification"), eq("test"), eq(OutboxEvents.TEST_EMAIL),
                eq(new OutboxEvents.TestEmailRequested("owner@gmail.com", "demo.admin@x.dev")));
    }

    @Test
    void acceptsAllowlistedAddressesAndDomains_rejectsOthers() {
        TestEmailService service = service(allowlist("owner@gmail.com, @college.edu"));

        assertThat(service.queue("dean@college.edu", "admin").recipient()).isEqualTo("de***@college.edu");
        assertThatThrownBy(() -> service.queue("stranger@else.com", "admin"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("allowlisted");
        assertThatThrownBy(() -> service.queue("not-an-email", "admin")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void withoutAnAllowlist_anAddressIsRequired() {
        TestEmailService service = service(new LoggingEmailSender("SMTP not configured"));

        assertThatThrownBy(() -> service.queue(" ", "admin")).isInstanceOf(BadRequestException.class);
        assertThat(service.queue("anyone@x.com", "admin").recipient()).isEqualTo("an***@x.com");
    }

    @Test
    void isLimitedToOnePerCooldown() {
        TestEmailService service = service(allowlist("owner@gmail.com"));
        OutboxEvent recent = OutboxEvent.pending("Notification", "test", OutboxEvents.TEST_EMAIL, "{}",
                LocalDateTime.now().minusSeconds(30));
        when(repository.findFirstByEventTypeOrderByCreatedAtDesc(OutboxEvents.TEST_EMAIL)).thenReturn(Optional.of(recent));

        assertThatThrownBy(() -> service.queue(null, "admin"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Try again in 5 minutes");
        verify(outbox, never()).record(any(), any(), any(), any());
    }
}
