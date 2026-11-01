package com.student.studentservice.notification;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Slf4j
@Configuration
public class NotificationConfig {

    /**
     * <ul>
     *   <li>No SMTP (SPRING_MAIL_HOST unset): emails are logged only.</li>
     *   <li>SMTP + MAIL_ALLOWED_RECIPIENTS: real email to allowlisted addresses, the rest logged.</li>
     *   <li>SMTP on a public demo without an allowlist: logged only. Visitors can register students
     *       with any address, so the demo must not email arbitrary people.</li>
     *   <li>SMTP otherwise: real email to everyone.</li>
     * </ul>
     */
    @Bean
    public EmailSender emailSender(ObjectProvider<JavaMailSender> mailSender,
                                   @Value("${app.mail.from}") String from,
                                   @Value("${app.demo.enabled:false}") boolean demoMode,
                                   @Value("${app.mail.allowed-recipients:}") String allowedRecipients,
                                   CircuitBreakerRegistry circuitBreakers) {
        JavaMailSender smtp = mailSender.getIfAvailable();
        if (smtp == null) {
            log.info("SMTP not configured (SPRING_MAIL_HOST): emails are logged, not sent");
            return new LoggingEmailSender("SMTP not configured");
        }
        SmtpEmailSender sender = new SmtpEmailSender(smtp, from, circuitBreakers.circuitBreaker("smtp"));
        if (!AllowlistEmailSender.parse(allowedRecipients).isEmpty()) {
            EmailSender allowlisted = new AllowlistEmailSender(sender, allowedRecipients);
            log.info("Email mode: {}", allowlisted.mode());
            return allowlisted;
        }
        if (demoMode) {
            log.info("Demo mode without MAIL_ALLOWED_RECIPIENTS: emails are logged, not sent");
            return new LoggingEmailSender("demo mode; set MAIL_ALLOWED_RECIPIENTS to email chosen addresses");
        }
        return sender;
    }
}
