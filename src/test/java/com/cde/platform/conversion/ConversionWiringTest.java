package com.cde.platform.conversion;

import com.cde.platform.fetch.RemoteContentFetcher;
import com.cde.platform.security.ConversionPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * That the feature is present when switched on and absent when not.
 *
 * <p>Both halves matter. The interesting failure is not a broken bean — it is
 * a context that will not start at all, in one configuration or the other,
 * discovered by whoever deploys it. The conversion beans depend on the fetch
 * beans, which exist only under {@code cde.fetch.enabled}, so getting that
 * condition wrong in either direction breaks startup for a whole class of
 * deployment.
 */
class ConversionWiringTest {

    @Nested
    @DisplayName("with fetching switched on")
    @SpringBootTest
    @TestPropertySource(properties = {
        "cde.fetch.enabled=true",
        "cde.fetch.permitted-hosts=files.example.test"
    })
    class Enabled {

        @Autowired ApplicationContext context;

        @Test
        @DisplayName("the whole pipeline is wired and the context starts")
        void pipelineIsWired() {
            assertThat(context.getBean(RemoteContentFetcher.class)).isNotNull();
            assertThat(context.getBean(ConversionJobService.class)).isNotNull();
            assertThat(context.getBean(ConversionWorkQueue.class)).isNotNull();
            assertThat(context.getBean(ConversionPipeline.class)).isNotNull();
            assertThat(context.getBean(ConversionJobExecutor.class)).isNotNull();
            assertThat(context.getBean(ConversionStartupRecovery.class)).isNotNull();
        }
    }

    @Nested
    @DisplayName("with fetching switched off, which is the default")
    @SpringBootTest
    @AutoConfigureMockMvc
    class Disabled {

        @Autowired ApplicationContext context;
        @Autowired MockMvc mockMvc;

        @Test
        @DisplayName("the context starts with no conversion beans at all")
        void featureIsAbsentRatherThanDisabled() {
            // Absent beats disabled: an air-gapped or PROTECTED deployment
            // (§6.4–6.6) gets no outbound client and no queue, so there is no
            // code path to reach rather than one trusted not to be called.
            assertThat(context.getBeanNamesForType(ConversionJobService.class)).isEmpty();
            assertThat(context.getBeanNamesForType(ConversionJobExecutor.class)).isEmpty();
            assertThat(context.getBeanNamesForType(RemoteContentFetcher.class)).isEmpty();
        }

        @Test
        @DisplayName("an authenticated caller gets 404, not 401 or 500")
        void theEndpointIsSimplyNotThere() throws Exception {
            // The integration guide tells integrators to expect 404 here and to
            // read it as "this deployment does not do that" rather than as a
            // credential problem. That is a claim about the security chain
            // ordering — an unmapped path could plausibly answer 401 first —
            // so it is asserted rather than assumed.
            mockMvc.perform(get("/api/conversions")
                    .with(user("wiring-test").authorities(
                        new SimpleGrantedAuthority(ConversionPermission.SUBMIT))))
                .andExpect(status().isNotFound());
        }
    }
}
