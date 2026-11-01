package com.student.studentservice.notification;

public record EmailMessage(String to, String subject, String body) {

    /** "jo***@example.com": enough to correlate in logs without logging the full address. */
    public String maskedRecipient() {
        return mask(to);
    }

    /** "jo***@example.com"; "@example.com" (a whole-domain allowlist entry) stays as it is. */
    public static String mask(String address) {
        if (address.startsWith("@")) {
            return address;
        }
        int at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return address.substring(0, Math.min(2, at)) + "***" + address.substring(at);
    }
}
