package com.cde.platform.conversion;

import com.cde.platform.fetch.RemoteContentFetcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

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
    class Disabled {

        @Autowired ApplicationContext context;

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
    }
}
