package com.seatsure.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private String run(String incoming, AtomicReference<String> mdcDuringRequest) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/courses");
        if (incoming != null) {
            request.addHeader(RequestIdFilter.HEADER, incoming);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> mdcDuringRequest.set(MDC.get(RequestIdFilter.MDC_KEY)));
        return response.getHeader(RequestIdFilter.HEADER);
    }

    @Test
    void generatesAnId_putsItInTheMdc_andEchoesIt() throws Exception {
        AtomicReference<String> mdc = new AtomicReference<>();

        String id = run(null, mdc);

        assertThat(id).matches("[0-9a-f-]{36}");
        assertThat(mdc.get()).isEqualTo(id);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("cleared after the request").isNull();
    }

    @Test
    void keepsAWellFormedIdFromTheCaller() throws Exception {
        assertThat(run("frontend-abc_123.4", new AtomicReference<>())).isEqualTo("frontend-abc_123.4");
    }

    @Test
    void replacesUnsafeValues() throws Exception {
        assertThat(run("evil\nFAKE LOG LINE", new AtomicReference<>())).matches("[0-9a-f-]{36}");
        assertThat(run("x".repeat(65), new AtomicReference<>())).matches("[0-9a-f-]{36}");
    }
}
