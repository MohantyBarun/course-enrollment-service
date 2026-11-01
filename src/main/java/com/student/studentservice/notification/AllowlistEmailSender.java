package com.student.studentservice.notification;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Sends real email only to approved recipients (exact addresses, or "@domain" for a whole domain)
 * and logs the rest. This is what makes real email safe on a public demo, where anyone can
 * register a student with any address.
 */
@Slf4j
public class AllowlistEmailSender implements EmailSender {

    private final EmailSender delegate;
    private final List<String> allowed;

    public AllowlistEmailSender(EmailSender delegate, String allowlist) {
        this.delegate = delegate;
        this.allowed = parse(allowlist);
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("The allowlist is empty");
        }
    }

    static List<String> parse(String allowlist) {
        return allowlist == null ? List.of() : Arrays.stream(allowlist.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public boolean isAllowed(String recipient) {
        String to = recipient.trim().toLowerCase(Locale.ROOT);
        return allowed.stream().anyMatch(entry -> entry.startsWith("@") ? to.endsWith(entry) : to.equals(entry));
    }

    @Override
    public Delivery send(EmailMessage message) {
        if (isAllowed(message.to())) {
            return delegate.send(message);
        }
        log.info("Email '{}' to {} not sent (not on the recipient allowlist)", message.subject(), message.maskedRecipient());
        return Delivery.skipped("Not sent: recipient isn't on MAIL_ALLOWED_RECIPIENTS");
    }

    @Override
    public List<String> allowlist() {
        return allowed;
    }

    @Override
    public String mode() {
        return delegate.mode() + " for " + allowed.size() + " allowlisted recipient"
                + (allowed.size() == 1 ? "" : "s") + "; others logged only";
    }
}
