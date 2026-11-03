package com.seatsure.insights;

import java.time.Clock;
import com.seatsure.entity.OutboxEvent;
import com.seatsure.exception.BadRequestException;
import com.seatsure.exception.ConflictException;
import com.seatsure.notification.AllowlistEmailSender;
import com.seatsure.notification.EmailMessage;
import com.seatsure.notification.EmailSender;
import com.seatsure.outbox.OutboxEvents;
import com.seatsure.outbox.OutboxService;
import com.seatsure.repository.OutboxEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Lets an admin check the email setup end to end. Because every demo visitor is an admin, test
 * emails can only go to allowlisted addresses (when there is an allowlist) and are limited to one
 * per cooldown period, so the owner's inbox and the SMTP quota can't be flooded.
 */
@Service
public class TestEmailService {

    public record Queued(String recipient, String message) {
    }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final OutboxService outbox;
    private final OutboxEventRepository outboxRepository;
    private final EmailSender emailSender;
    private final Duration cooldown;
    private final Clock clock;

    public TestEmailService(OutboxService outbox, OutboxEventRepository outboxRepository, EmailSender emailSender,
                            @Value("${app.mail.test-cooldown:PT5M}") Duration cooldown, Clock clock) {
        this.outbox = outbox;
        this.outboxRepository = outboxRepository;
        this.emailSender = emailSender;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    @Transactional
    public Queued queue(String requestedTo, String requestedBy) {
        String to = resolveRecipient(requestedTo);
        Optional<OutboxEvent> last = outboxRepository.findFirstByEventTypeOrderByCreatedAtDesc(OutboxEvents.TEST_EMAIL);
        if (last.isPresent()) {
            Duration since = Duration.between(last.get().getCreatedAt(), clock.instant());
            if (since.compareTo(cooldown) < 0) {
                long wait = Math.max(1, cooldown.minus(since).toSeconds());
                throw new ConflictException("A test email was sent " + since.toSeconds() + " seconds ago. "
                        + "Try again in " + (wait >= 60 ? (wait + 59) / 60 + " minutes" : wait + " seconds") + ".");
            }
        }
        outbox.record("Notification", "test", OutboxEvents.TEST_EMAIL,
                new OutboxEvents.TestEmailRequested(to, requestedBy));
        return new Queued(EmailMessage.mask(to), "Test email queued. Watch its status below.");
    }

    private String resolveRecipient(String requestedTo) {
        var allowlist = emailSender.allowlist();
        if (requestedTo == null || requestedTo.isBlank()) {
            return allowlist.stream().filter(a -> !a.startsWith("@")).findFirst()
                    .orElseThrow(() -> new BadRequestException("Enter the address to send the test email to."));
        }
        String to = requestedTo.trim();
        if (!EMAIL.matcher(to).matches()) {
            throw new BadRequestException("That doesn't look like an email address.");
        }
        if (!allowlist.isEmpty() && !(emailSender instanceof AllowlistEmailSender a && a.isAllowed(to))) {
            throw new BadRequestException("Only allowlisted recipients (MAIL_ALLOWED_RECIPIENTS) can get a test email here.");
        }
        return to;
    }
}
