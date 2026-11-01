package com.seatsure.notification;

public interface EmailSender {

    /**
     * @return whether the email was actually handed to a mail server or deliberately only logged
     * @throws RuntimeException if it could not be handed to the mail server (the caller retries)
     */
    Delivery send(EmailMessage message);

    /** Human-readable delivery mode, shown on the admin System page. */
    String mode();

    /** Recipients that get real email, if restricted ("@domain" entries cover a domain); empty = no restriction. */
    default java.util.List<String> allowlist() {
        return java.util.List.of();
    }

    record Delivery(boolean sent, String detail) {

        public static Delivery sent(String detail) {
            return new Delivery(true, detail);
        }

        public static Delivery skipped(String reason) {
            return new Delivery(false, reason);
        }
    }
}
