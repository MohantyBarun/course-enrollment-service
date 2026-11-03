package com.seatsure.demo;

import com.seatsure.dto.AdminDtos.AdminBookingView;
import com.seatsure.insights.NotificationFeedService.Entry;
import com.seatsure.notification.EmailMessage;
import com.seatsure.util.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * On the public demo every visitor can sign in as the shared demo admin, so admin pages must not
 * show real customers' personal data to them. The seeded demo accounts are fictional and stay
 * readable; everyone else's name and email are masked, and their email contents are hidden.
 * Real admins (their own accounts) see everything.
 */
@Component
public class DemoPrivacy {

    /** Domain of every account the demo seeds (see {@link DemoDataService}). */
    public static final String SEEDED_DOMAIN = "@seatsure.dev";

    private final boolean demoEnabled;
    private final String demoAdminEmail;

    public DemoPrivacy(@Value("${app.demo.enabled:false}") boolean demoEnabled,
                       @Value("${app.demo.admin-email:demo.admin@seatsure.dev}") String demoAdminEmail) {
        this.demoEnabled = demoEnabled;
        this.demoAdminEmail = demoAdminEmail;
    }

    /** True for the shared demo admin: what they see of real customers is masked. */
    public boolean appliesTo(CurrentUser viewer) {
        return demoEnabled && viewer != null && viewer.email().equalsIgnoreCase(demoAdminEmail);
    }

    public AdminBookingView mask(AdminBookingView b) {
        if (isSeeded(b.customerEmail())) {
            return b;
        }
        return new AdminBookingView(b.reference(), maskName(b.customerName()), EmailMessage.mask(b.customerEmail()),
                b.movieTitle(), b.cinemaName(), b.startsAt(), b.seats(), b.totalPaise(), b.status(), b.bookedAt());
    }

    public Entry mask(Entry e) {
        if (e.recipient() == null || isSeeded(e.recipient())) {
            return e;
        }
        return new Entry(e.id(), e.eventType(), e.label(), EmailMessage.mask(e.recipient()), e.subject(),
                e.preview() == null ? null : "(hidden on the public demo)", e.status(), e.detail(), e.attempts(),
                e.createdAt(), e.handledAt(), e.nextAttemptAt());
    }

    static boolean isSeeded(String email) {
        return email != null && email.toLowerCase(Locale.ROOT).endsWith(SEEDED_DOMAIN);
    }

    /** "Asha Rao" -> "Asha R.": enough to follow a support conversation, not to identify anyone. */
    static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String[] parts = name.trim().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }
}
