package com.cde.platform.ai;

import com.cde.platform.ai.AiPayloadSanitiser.ComparisonFacts;
import com.cde.platform.audit.AuditAction;
import com.cde.platform.audit.AuditOutcome;
import com.cde.platform.audit.AuditRequest;
import com.cde.platform.audit.RecordedChanges;
import com.cde.platform.audit.RequestAuditor;
import com.cde.platform.deployment.DeploymentProperties;
import com.cde.platform.deployment.DeploymentTier;
import com.cde.platform.deployment.ExternalServiceMode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * The only path from this platform to a model provider.
 *
 * <p>§10.1 is an absolute rule with no per-feature exceptions, and this class
 * is where it is kept. Four gates stand between a request and an outbound
 * call — the deployment tier permits one, a provider is configured, the
 * sanitiser produced something sendable, and the attempt is audited either
 * way — and none of them had a test.
 *
 * <p>The cases are weighted towards refusal, because that is the direction
 * that matters. A gate that wrongly blocks produces a support ticket; a gate
 * that wrongly opens sends a Defence tenant's drawing register to a third
 * party, and nothing downstream would report it. The audit assertions carry
 * the same weight for the same reason: §10.1 requires that every call be
 * recorded and that no payload ever be, and "somebody tried to use AI on a
 * sovereign deployment" is the single most interesting event this class can
 * produce.
 *
 * <p>No network. Every case here is decided before a socket would be opened,
 * except the one that deliberately points at an unroutable address to reach
 * the provider-failure branch.
 */
@DisplayName("asking a model provider for help")
class AiAssistanceServiceTest {

    /** What the audit trail was told, without a database in the way. */
    private final List<AuditRequest.Builder> audited = new ArrayList<>();

    private AiProperties properties;
    private DeploymentProperties deployment;
    private RequestAuditor auditor;
    private AiAssistanceService assistance;

    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);

    @BeforeEach
    void setUp() {
        audited.clear();

        properties = new AiProperties();
        properties.setApiKey("test-key-not-a-real-credential");
        properties.setModel("a-model-this-deployment-contracted-for");

        deployment = new DeploymentProperties();
        deployment.setTier(DeploymentTier.COMMERCIAL);
        deployment.setAiFeatures(ExternalServiceMode.ONLINE_API);

        auditor = mock(RequestAuditor.class);
        doAnswer(invocation -> {
            audited.add(invocation.getArgument(0));
            return null;
        }).when(auditor).recordIfTenantBound(any(), any());

        assistance = new AiAssistanceService(
            properties, deployment, new AiPayloadSanitiser(), auditor, new ObjectMapper());
    }

    private ComparisonFacts facts(String... changes) {
        return new ComparisonFacts(
            "A-101 Ground floor", "A-101 Ground floor",
            "P01", "P02", "DRAWING", List.of(changes));
    }

    private AiAssistanceService.AssistanceOutcome summarise(ComparisonFacts facts) {
        return assistance.summariseComparison(facts, 7L, "sam.okonkwo", httpRequest);
    }

    /** Every audit record this run produced, as the fields it carries. */
    private List<AuditRequest> records() {
        return audited.stream().map(AuditRequest.Builder::build).toList();
    }

    // ── Gate one: does this deployment permit an outbound call ────────────

    @Nested
    @DisplayName("deployments that may not call a provider at all")
    class DeploymentGate {

        @ParameterizedTest
        @EnumSource(value = DeploymentTier.class, names = {"GOVERNMENT", "DEFENCE"})
        @DisplayName("a sovereign tier never calls out, whatever else is configured")
        void sovereignTiersRefuse(DeploymentTier tier) {
            // Checked here as well as at startup: a startup check catches a
            // misconfiguration, and this catches a code path that forgot.
            deployment.setTier(tier);

            var outcome = summarise(facts("Door D-12 moved 300mm east"));

            assertThat(outcome.available()).isFalse();
            assertThat(outcome.report()).isNull();
        }

        @Test
        @DisplayName("the kill switch is honoured even on a commercial tier")
        void killSwitchIsHonoured() {
            deployment.setAiFeatures(ExternalServiceMode.DISABLED);

            assertThat(assistance.isAvailable()).isFalse();
            assertThat(summarise(facts("anything")).available()).isFalse();
        }

        @Test
        @DisplayName("a local-inference deployment does not call the online provider")
        void localDatasetModeDoesNotCallOut() {
            deployment.setAiFeatures(ExternalServiceMode.LOCAL_DATASET);

            assertThat(assistance.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("the refused attempt is still recorded")
        void refusedAttemptIsAudited() {
            // On a sovereign deployment this is the event worth having:
            // somebody tried.
            deployment.setTier(DeploymentTier.DEFENCE);

            summarise(facts("Door D-12 moved"));

            assertThat(records()).hasSize(1);
            assertThat(records().getFirst().action()).isEqualTo(AuditAction.AI_REQUEST);
            assertThat(records().getFirst().outcome()).isEqualTo(AuditOutcome.FAILURE);
        }

        @Test
        @DisplayName("the record says why, without saying what was in the request")
        void refusalRecordNamesTheReason() {
            deployment.setTier(DeploymentTier.GOVERNMENT);

            summarise(facts("Door D-12 moved 300mm east"));

            String change = RecordedChanges.flatten(records().getFirst().change());
            assertThat(change).contains("unavailable-on-this-deployment");
            assertThat(change).doesNotContain("Door D-12");
        }
    }

    // ── Gate two: is a provider configured ────────────────────────────────

    @Nested
    @DisplayName("deployments with no provider configured")
    class ConfigurationGate {

        @Test
        @DisplayName("an absent credential makes the feature unavailable, not broken")
        void absentCredentialIsUnavailable() {
            // AI is optional and the rest of the platform does not depend on
            // it, so this reports itself off rather than failing a request.
            properties.setApiKey("");

            assertThat(assistance.isAvailable()).isFalse();
            assertThat(summarise(facts("x")).available()).isFalse();
        }

        @Test
        @DisplayName("a credential with no model names nothing to call")
        void credentialWithoutModelIsUnavailable() {
            properties.setModel("");

            assertThat(assistance.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("a fully configured commercial deployment is available")
        void configuredDeploymentIsAvailable() {
            assertThat(assistance.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("whitespace is not configuration")
        void whitespaceIsNotAKey() {
            properties.setApiKey("   ");

            assertThat(assistance.isAvailable()).isFalse();
        }
    }

    // ── Gate three: did the sanitiser permit it ───────────────────────────

    @Nested
    @DisplayName("content the sanitiser refuses")
    class SanitiserGate {

        @Test
        @DisplayName("classified material is never sent")
        void classifiedContentIsRefused() {
            var outcome = summarise(facts("OFFICIAL-SENSITIVE: perimeter sensor layout revised"));

            assertThat(outcome.wasRefused()).isTrue();
            assertThat(outcome.report()).isNull();
        }

        @Test
        @DisplayName("the reader is told why, in words they can act on")
        void refusalExplainsItself() {
            var outcome = summarise(facts("PROTECTED: cabinet locations"));

            assertThat(outcome.refusalDetail())
                .contains("cannot be sent to a model provider")
                .contains("never sent off this deployment");
        }

        @Test
        @DisplayName("the refusal is audited as a denial, not as a failure")
        void refusalIsAuditedAsDenied() {
            // They are different events. A failure is the provider being
            // unreachable; a denial is this platform refusing to send.
            summarise(facts("PROTECTED: cabinet locations"));

            assertThat(records().getFirst().outcome()).isEqualTo(AuditOutcome.DENIED);
        }

        @Test
        @DisplayName("the record names the field refused, never its contents")
        void refusalRecordNamesFieldsNotContents() {
            // The whole point of the rule would be lost if the audit trail
            // carried the classified text that was refused.
            summarise(facts("PROTECTED: cabinet locations at grid ref 51.5074"));

            String change = RecordedChanges.flatten(records().getFirst().change());
            assertThat(change).contains("classified-content-refused");
            assertThat(change).doesNotContain("cabinet locations");
            assertThat(change).doesNotContain("51.5074");
        }

        @Test
        @DisplayName("an available deployment is still reported as available when refusing")
        void refusalIsNotUnavailability() {
            // The UI shows different things for "this deployment has no AI"
            // and "this particular content cannot be sent".
            var outcome = summarise(facts("SECRET: nothing doing"));

            assertThat(outcome.available()).isTrue();
        }
    }

    // ── Gate four: the provider itself ────────────────────────────────────

    @Nested
    @DisplayName("a provider that cannot be reached")
    class ProviderFailure {

        @BeforeEach
        void pointAtNowhere() {
            // A reserved-for-documentation address (RFC 5737) on a closed
            // port: it cannot route anywhere, so this exercises the failure
            // branch without depending on the network being absent.
            properties.setEndpoint("https://192.0.2.1:9/v1/messages");
            properties.setTimeout(java.time.Duration.ofMillis(250));
        }

        @Test
        @DisplayName("is reported as unavailable rather than as a broken request")
        void unreachableProviderIsUnavailable() {
            var outcome = summarise(facts("Door D-12 moved 300mm east"));

            assertThat(outcome.available()).isFalse();
            assertThat(outcome.report()).isNull();
        }

        @Test
        @DisplayName("does not leak the provider's own failure detail to the caller")
        void providerDetailIsNotReturned() {
            // It can name internal hosts, and it is not something the caller
            // can act on beyond trying again.
            var outcome = summarise(facts("Door D-12 moved"));

            assertThat(outcome.refusalDetail()).isNull();
        }

        @Test
        @DisplayName("is audited as a failure, naming the reason")
        void unreachableProviderIsAudited() {
            summarise(facts("Door D-12 moved"));

            assertThat(records()).hasSize(1);
            assertThat(records().getFirst().outcome()).isEqualTo(AuditOutcome.FAILURE);
            assertThat(RecordedChanges.flatten(records().getFirst().change())).contains("provider-unreachable");
        }

        @Test
        @DisplayName("the audit record still carries no content from the request")
        void failureRecordCarriesNoPayload() {
            summarise(facts("Door D-12 moved 300mm east"));

            assertThat(RecordedChanges.flatten(records().getFirst().change())).doesNotContain("Door D-12");
        }
    }

    // ── Every path audits exactly once ────────────────────────────────────

    @Nested
    @DisplayName("the audit trail")
    class Auditing {

        @Test
        @DisplayName("records the attempt on every outcome, and only once")
        void everyOutcomeIsAuditedOnce() {
            // §10.1 requires every call to be logged. A path that returns
            // without auditing is a call nobody can account for, and the
            // three below are the only ways out of this method.
            deployment.setTier(DeploymentTier.DEFENCE);
            summarise(facts("a"));
            assertThat(records()).hasSize(1);

            audited.clear();
            deployment.setTier(DeploymentTier.COMMERCIAL);
            summarise(facts("PROTECTED: b"));
            assertThat(records()).hasSize(1);

            audited.clear();
            properties.setEndpoint("https://192.0.2.1:9/v1/messages");
            properties.setTimeout(java.time.Duration.ofMillis(250));
            summarise(facts("c"));
            assertThat(records()).hasSize(1);
        }

        @Test
        @DisplayName("attributes the attempt to whoever made it")
        void recordsTheActor() {
            deployment.setTier(DeploymentTier.DEFENCE);

            summarise(facts("a"));

            assertThat(records().getFirst().actorLabel()).isEqualTo("sam.okonkwo");
        }

        @Test
        @DisplayName("names the feature, so spend can be attributed to it")
        void recordsTheFeature() {
            deployment.setTier(DeploymentTier.DEFENCE);

            summarise(facts("a"));

            assertThat(RecordedChanges.flatten(records().getFirst().change())).contains("comparison-report");
        }
    }
}
