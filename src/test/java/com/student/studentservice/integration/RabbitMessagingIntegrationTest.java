package com.student.studentservice.integration;

import com.student.studentservice.dto.StudentRequestDTO;
import com.student.studentservice.messaging.RabbitMessagingConfig;
import com.student.studentservice.notification.EmailMessage;
import com.student.studentservice.notification.EmailSender;
import com.student.studentservice.outbox.OutboxEvents;
import com.student.studentservice.service.StudentService;
import com.student.studentservice.support.PostgresTestcontainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Outbox -> RabbitMQ (publisher confirms) -> listener -> email, against real PostgreSQL and RabbitMQ:
 * delivery, de-duplication of redelivered messages, and dead-lettering after the retries.
 */
@SpringBootTest(properties = {
        "jwt.secret=integration-test-secret-that-is-longer-than-sixty-four-bytes-for-hs512-signing",
        "app.rate-limit.enabled=false",
        "app.messaging.rabbit.enabled=true",
        "app.outbox.poll-interval=PT1S",
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.multiplier=1",
        "spring.rabbitmq.listener.simple.retry.max-attempts=3"
})
@Testcontainers(disabledWithoutDocker = true)
@Import({PostgresTestcontainer.class, RabbitMessagingIntegrationTest.Rabbit.class})
class RabbitMessagingIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Rabbit {
        @Bean
        @ServiceConnection
        RabbitMQContainer rabbit() {
            return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-alpine"));
        }
    }

    @Autowired private StudentService studentService;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private EmailSender emailSender;

    @BeforeEach
    void smtpFailsForSomeRecipients() {
        doAnswer(invocation -> {
            EmailMessage m = invocation.getArgument(0);
            if (m.to().startsWith("bounce")) {
                throw new IllegalStateException("SMTP 550 mailbox unavailable");
            }
            return EmailSender.Delivery.sent("Accepted by the SMTP server");
        }).when(emailSender).send(any());
    }

    private static StudentRequestDTO student(String email) {
        StudentRequestDTO dto = new StudentRequestDTO();
        dto.setName("Rabbit Test");
        dto.setEmail(email);
        dto.setDepartment("Electronics");
        dto.setBranch("ECE");
        dto.setDateOfBirth(LocalDate.of(2004, 2, 2));
        return dto;
    }

    private int deadLetterCount() {
        Properties props = amqpAdmin.getQueueProperties(RabbitMessagingConfig.EMAIL_DEAD_LETTER_QUEUE);
        return props == null ? 0 : ((Number) props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }

    @Test
    @DisplayName("a registration is published with a confirm, consumed, and emailed")
    void registration_isDeliveredThroughRabbit() {
        String email = "ok-" + UUID.randomUUID() + "@college.test";

        studentService.registerStudent(student(email));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                verify(emailSender).send(argThat((EmailMessage m) -> m.to().equals(email))));
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE payload LIKE ?", String.class, "%" + email + "%")
                .equals("PUBLISHED"));
    }

    @Test
    @DisplayName("a redelivered message (same id) sends the email only once")
    void duplicateMessage_isProcessedOnce() {
        String email = "dup-" + UUID.randomUUID() + "@college.test";
        String payload = "{\"enrollmentId\":1,\"studentId\":1,\"name\":\"Dup\",\"email\":\"" + email + "\","
                + "\"courseCode\":\"CS201\",\"courseTitle\":\"DS\",\"term\":\"Autumn 2026\",\"credits\":4}";
        MessageProperties props = new MessageProperties();
        props.setMessageId(UUID.randomUUID().toString());
        props.setType(OutboxEvents.ENROLLMENT_CONFIRMED);
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);

        rabbitTemplate.send(RabbitMessagingConfig.EVENTS_EXCHANGE, OutboxEvents.ENROLLMENT_CONFIRMED, message);
        rabbitTemplate.send(RabbitMessagingConfig.EVENTS_EXCHANGE, OutboxEvents.ENROLLMENT_CONFIRMED, message);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                verify(emailSender).send(argThat((EmailMessage m) -> m.to().equals(email))));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(emailSender, times(1)).send(argThat((EmailMessage m) -> m.to().equals(email))));
    }

    @Test
    @DisplayName("a message that keeps failing is retried, then dead-lettered (and can be retried later)")
    void poisonMessage_endsUpInTheDeadLetterQueue() {
        int before = deadLetterCount();
        String email = "bounce-" + UUID.randomUUID() + "@college.test";

        studentService.registerStudent(student(email));

        await().atMost(Duration.ofSeconds(30)).until(() -> deadLetterCount() == before + 1);
        verify(emailSender, times(3)).send(argThat((EmailMessage m) -> m.to().equals(email)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_messages pm JOIN outbox_events oe "
                + "ON oe.id = pm.message_id WHERE oe.payload LIKE ?", Long.class, "%" + email + "%"))
                .as("not marked processed, so a replay from the DLQ would send it").isZero();
    }
}
