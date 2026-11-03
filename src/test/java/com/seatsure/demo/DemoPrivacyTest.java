package com.seatsure.demo;

import com.seatsure.dto.AdminDtos.AdminBookingView;
import com.seatsure.service.Roles;
import com.seatsure.util.CurrentUser;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DemoPrivacyTest {

    private final DemoPrivacy privacy = new DemoPrivacy(true, "demo.admin@seatsure.dev");

    private static AdminBookingView booking(String name, String email) {
        return new AdminBookingView("SS2345ABCD", name, email, "Paper Boats", "Starlight", Instant.now(), 2, 50_000,
                "CONFIRMED", Instant.now());
    }

    @Test
    void appliesOnlyToTheSharedDemoAdmin_andOnlyWhenTheDemoIsOn() {
        assertThat(privacy.appliesTo(new CurrentUser(1L, "Demo.Admin@seatsure.dev", Roles.ROLE_ADMIN))).isTrue();
        assertThat(privacy.appliesTo(new CurrentUser(2L, "admin@barun.dev", Roles.ROLE_ADMIN))).isFalse();
        assertThat(privacy.appliesTo(null)).isFalse();
        assertThat(new DemoPrivacy(false, "demo.admin@seatsure.dev")
                .appliesTo(new CurrentUser(1L, "demo.admin@seatsure.dev", Roles.ROLE_ADMIN))).isFalse();
    }

    @Test
    void realCustomers_areMasked_seededDemoCustomersAreNot() {
        AdminBookingView real = privacy.mask(booking("Asha Kumari Rao", "asha.rao@gmail.com"));
        assertThat(real.customerName()).isEqualTo("Asha R.");
        assertThat(real.customerEmail()).isEqualTo("as***@gmail.com");
        assertThat(real.reference()).isEqualTo("SS2345ABCD");

        AdminBookingView seeded = booking("Riya Sen", "riya.sen@seatsure.dev");
        assertThat(privacy.mask(seeded)).isSameAs(seeded);
    }

    @Test
    void maskName_handlesSingleNamesAndBlanks() {
        assertThat(DemoPrivacy.maskName("Madonna")).isEqualTo("Madonna");
        assertThat(DemoPrivacy.maskName("  ")).isEqualTo("  ");
        assertThat(DemoPrivacy.maskName(null)).isNull();
    }
}
