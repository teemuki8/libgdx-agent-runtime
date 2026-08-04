package io.github.teemuki8.libgdx.agent.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class UiCorrelationRegistryTest {
    @Test
    void resolvesBothDirectionsWithExplicitMissingExpiredAmbiguousAndTruncationEvidence() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("ui-correlation"))
                .uiCorrelationLimits(new UiCorrelationLimits(8, 1, 8, 64))
                .build();
        UiBindingRegistration first = runtime.uiCorrelations().register(new UiBinding(
                "health-primary", EntityId.of("enemy-1"), Optional.of("health"),
                "battle-ui", "target-health:enemy-1",
                UiBindingValidity.frames(new ExecutionEpochId(0),
                        new FrameRange(new FrameId(0), new FrameId(2)))));
        runtime.uiCorrelations().register(new UiBinding(
                "health-secondary", EntityId.of("enemy-1"), Optional.of("health"),
                "battle-ui", "health-label:enemy-1", UiBindingValidity.always()));
        runtime.uiCorrelations().register(new UiBinding(
                "state-by-generation", EntityId.of("enemy-2"), Optional.of("state"),
                "battle-ui", "state-label:enemy-2",
                UiBindingValidity.generation("ui-generation-1")));

        UiBindingResult ambiguous = runtime.uiCorrelations().runtimeToUi(
                EntityId.of("enemy-1"), Optional.of("health"), new ExecutionEpochId(0),
                new FrameId(1), Optional.empty(), 1);
        assertEquals(UiBindingStatus.AMBIGUOUS, ambiguous.status());
        assertEquals(2, ambiguous.observedCount());
        assertEquals(1, ambiguous.bindings().size());
        assertTrue(ambiguous.truncated());

        UiBindingResult expired = runtime.uiCorrelations().uiToRuntime(
                "battle-ui", "target-health:enemy-1", new ExecutionEpochId(0),
                new FrameId(3), Optional.empty(), 1);
        assertEquals(UiBindingStatus.EXPIRED, expired.status());
        assertTrue(expired.bindings().isEmpty());
        UiBindingResult staleGeneration = runtime.uiCorrelations().runtimeToUi(
                EntityId.of("enemy-2"), Optional.of("state"), new ExecutionEpochId(0),
                new FrameId(1), Optional.of("ui-generation-2"), 1);
        assertEquals(UiBindingStatus.EXPIRED, staleGeneration.status());

        first.close();
        assertEquals(UiBindingStatus.MISSING, runtime.uiCorrelations().uiToRuntime(
                "battle-ui", "target-health:enemy-1", new ExecutionEpochId(0),
                new FrameId(1), Optional.empty(), 1).status());
    }

    @Test
    void frameMappingsRetainBothIdentifiersOrSharedTokensWithoutAssumingEquality() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("ui-frames"))
                .uiCorrelationLimits(new UiCorrelationLimits(8, 8, 2, 64))
                .build();
        runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
                new ExecutionEpochId(0), new FrameId(4), "battle-ui",
                Optional.of("ui-frame-91"), Optional.empty()));
        runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
                new ExecutionEpochId(0), new FrameId(5), "battle-ui",
                Optional.empty(), Optional.of("render-token-5")));
        runtime.uiCorrelations().recordFrame(new UiFrameCorrelation(
                new ExecutionEpochId(0), new FrameId(6), "battle-ui",
                Optional.of("ui-frame-93"), Optional.empty()));

        UiFrameCorrelationPage page = runtime.uiCorrelations().framesForUiSession("battle-ui", 1);
        assertEquals(new FrameId(6), page.items().getFirst().runtimeFrameId());
        assertEquals(1, page.evictedCount());
        assertTrue(page.hasMore());
        assertEquals(new FrameId(5), runtime.uiCorrelations()
                .framesForToken("render-token-5", 8).items().getFirst().runtimeFrameId());
    }
    @Test
    void enforcesConfiguredBoundsAndRegistrationLifecycle() {
        AgentRuntime runtime = AgentRuntime.builder()
                .sessionId(SessionId.of("ui-bounds"))
                .uiCorrelationLimits(new UiCorrelationLimits(1, 1, 1, 8))
                .build();
        runtime.uiCorrelations().register(new UiBinding(
                "b1", EntityId.of("e1"), Optional.empty(), "ui", "c1",
                UiBindingValidity.always()));
        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED,
                assertThrows(AgentRuntimeException.class,
                        () -> runtime.uiCorrelations().register(new UiBinding(
                                "b2", EntityId.of("e2"), Optional.empty(), "ui", "c2",
                                UiBindingValidity.always()))).code());
        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED,
                assertThrows(AgentRuntimeException.class,
                        () -> runtime.uiCorrelations().runtimeToUi(
                                EntityId.of("e1"), Optional.of("too-long9"),
                                new ExecutionEpochId(0), new FrameId(0),
                                Optional.empty(), 1)).code());
        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED,
                assertThrows(AgentRuntimeException.class,
                        () -> runtime.uiCorrelations().runtimeToUi(
                                EntityId.of("too-long9"), Optional.empty(),
                                new ExecutionEpochId(0), new FrameId(0),
                                Optional.empty(), 1)).code());
        assertEquals(RuntimeErrorCode.LIMIT_EXCEEDED,
                assertThrows(AgentRuntimeException.class,
                        () -> runtime.uiCorrelations().framesForToken("too-long9", 1)).code());
        runtime.start();
        assertThrows(AgentRuntimeException.class,
                () -> runtime.frame(1, () -> runtime.uiCorrelations().recordFrame(
                        new UiFrameCorrelation(
                                new ExecutionEpochId(0), new FrameId(0), "ui",
                                Optional.of("f1"), Optional.empty()))));
    }

}
