package com.student.studentservice.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.student.studentservice.entity.NotificationLog;
import com.student.studentservice.outbox.OutboxEvents;
import com.student.studentservice.repository.NotificationLogRepository;
import com.student.studentservice.util.SecretBox;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock private ProcessedMessages processedMessages;
    @Mock private EmailSender emailSender;
    @Mock private NotificationLogRepository notificationLog;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecretBox secretBox = new SecretBox("", "unit-test-secret-unit-test-secret-unit-test-secret");
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new NotificationDispatcher(processedMessages, emailSender, objectMapper, secretBox,
                notificationLog, "https://app.example.com");
    }

    private OutboxEvents.Message message(String type, Object payload) throws Exception {
        return new OutboxEvents.Message(UUID.randomUUID(), type, objectMapper.writeValueAsString(payload));
    }

    private NotificationLog savedLog() {
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLog).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void studentRegistered_sendsWelcomeEmailWithTheDecryptedPassword_andLogsItMasked() throws Exception {
        var msg = message(OutboxEvents.STUDENT_REGISTERED, new OutboxEvents.StudentRegistered(
                42L, "John Doe", "john@x.com", secretBox.encrypt("Xk9#mP2q"), 48));
        when(processedMessages.markProcessed(msg.id())).thenReturn(true);
        when(emailSender.send(any())).thenReturn(EmailSender.Delivery.sent("Accepted by the SMTP server"));

        dispatcher.dispatch(msg);

        ArgumentCaptor<EmailMessage> email = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(email.capture());
        assertThat(email.getValue().to()).isEqualTo("john@x.com");
        assertThat(email.getValue().body()).contains("Xk9#mP2q").contains("48 hours").contains("https://app.example.com");

        NotificationLog log = savedLog();
        assertThat(log.getMessageId()).isEqualTo(msg.id());
        assertThat(log.getDelivery()).isEqualTo(NotificationLog.Delivery.SENT);
        assertThat(log.getBodyPreview()).as("the stored copy never has the password")
                .doesNotContain("Xk9#mP2q").contains("Temporary password: " + NotificationDispatcher.MASK);
    }

    @Test
    void loggedOnlyEmails_areRecordedAsSkipped() throws Exception {
        var msg = message(OutboxEvents.ENROLLMENT_CONFIRMED, new OutboxEvents.EnrollmentConfirmed(
                9L, 42L, "John Doe", "john@x.com", "CS201", "Data Structures", "Autumn 2026", 4));
        when(processedMessages.markProcessed(msg.id())).thenReturn(true);
        when(emailSender.send(any())).thenReturn(EmailSender.Delivery.skipped("Not sent: demo mode"));

        dispatcher.dispatch(msg);

        ArgumentCaptor<EmailMessage> email = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(email.capture());
        assertThat(email.getValue().subject()).isEqualTo("Enrolled: CS201 Data Structures");
        NotificationLog log = savedLog();
        assertThat(log.getDelivery()).isEqualTo(NotificationLog.Delivery.SKIPPED);
        assertThat(log.getDetail()).isEqualTo("Not sent: demo mode");
        assertThat(log.getBodyPreview()).contains("CS201");
    }

    @Test
    void testEmail_isSentAndLogged() throws Exception {
        var msg = message(OutboxEvents.TEST_EMAIL, new OutboxEvents.TestEmailRequested("owner@gmail.com", "demo.admin@x.dev"));
        when(processedMessages.markProcessed(msg.id())).thenReturn(true);
        when(emailSender.send(any())).thenReturn(EmailSender.Delivery.sent("Accepted by the SMTP server"));

        dispatcher.dispatch(msg);

        ArgumentCaptor<EmailMessage> email = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(email.capture());
        assertThat(email.getValue().to()).isEqualTo("owner@gmail.com");
        assertThat(email.getValue().subject()).isEqualTo("StudentHub test email");
        assertThat(savedLog().getEventType()).isEqualTo(OutboxEvents.TEST_EMAIL);
    }

    @Test
    void duplicateMessage_isSkipped() throws Exception {
        var msg = message(OutboxEvents.ENROLLMENT_CONFIRMED, new OutboxEvents.EnrollmentConfirmed(
                9L, 42L, "John", "john@x.com", "CS201", "DS", "Autumn 2026", 4));
        when(processedMessages.markProcessed(msg.id())).thenReturn(false);

        dispatcher.dispatch(msg);

        verifyNoInteractions(emailSender, notificationLog);
    }

    @Test
    void unknownEventType_isIgnored() throws Exception {
        var msg = message("course.renamed", java.util.Map.of("courseId", 1));
        when(processedMessages.markProcessed(msg.id())).thenReturn(true);

        dispatcher.dispatch(msg);

        verifyNoInteractions(emailSender, notificationLog);
    }

    @Test
    void sendFailure_propagatesSoTheMessageIsRetried_andNothingIsLogged() throws Exception {
        var msg = message(OutboxEvents.ENROLLMENT_CONFIRMED, new OutboxEvents.EnrollmentConfirmed(
                9L, 42L, "John", "john@x.com", "CS201", "DS", "Autumn 2026", 4));
        when(processedMessages.markProcessed(msg.id())).thenReturn(true);
        doThrow(new IllegalStateException("SMTP down")).when(emailSender).send(any());

        assertThatThrownBy(() -> dispatcher.dispatch(msg)).hasMessageContaining("SMTP down");
        verifyNoInteractions(notificationLog);
    }

    @Test
    void emailAddressesAreMaskedForLogs() {
        assertThat(new EmailMessage("john.doe@x.com", "s", "b").maskedRecipient()).isEqualTo("jo***@x.com");
        assertThat(new EmailMessage("broken", "s", "b").maskedRecipient()).isEqualTo("***");
    }

    @Test
    void emailSenderSelection() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> none = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> smtp = mock(ObjectProvider.class);
        when(smtp.getIfAvailable()).thenReturn(mock(JavaMailSender.class));
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        NotificationConfig config = new NotificationConfig();

        assertThat(config.emailSender(none, "from@x.com", false, "", breakers)).isInstanceOf(LoggingEmailSender.class);
        assertThat(config.emailSender(none, "from@x.com", true, "me@x.com", breakers))
                .as("an allowlist doesn't help without SMTP").isInstanceOf(LoggingEmailSender.class);
        assertThat(config.emailSender(smtp, "from@x.com", false, "", breakers)).isInstanceOf(SmtpEmailSender.class);
        assertThat(config.emailSender(smtp, "from@x.com", true, "", breakers))
                .as("a public demo without an allowlist never sends real email").isInstanceOf(LoggingEmailSender.class);
        assertThat(config.emailSender(smtp, "from@x.com", true, "me@x.com", breakers))
                .isInstanceOf(AllowlistEmailSender.class);
    }
}
