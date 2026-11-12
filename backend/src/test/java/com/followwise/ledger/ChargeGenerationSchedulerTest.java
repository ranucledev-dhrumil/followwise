package com.followwise.ledger;

import com.followwise.AbstractIntegrationTest;
import com.followwise.app.AppStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = "app.scheduler-enabled=false")
class ChargeGenerationSchedulerTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void schedulerBeanAbsentWhenPropertyFalse() {
        assertFalse(context.containsBean("chargeGenerationScheduler"));
    }

    @Test
    void oneAppThrowingDoesNotStopOthersInScheduler() {
        AppStore mockAppStore = mock(AppStore.class);
        ChargeGeneratorService mockService = mock(ChargeGeneratorService.class);

        UUID wid1 = UUID.randomUUID();
        UUID aid1 = UUID.randomUUID();
        UUID wid2 = UUID.randomUUID();
        UUID aid2 = UUID.randomUUID();

        when(mockAppStore.findActiveAppIds()).thenReturn(List.of(
                new AppStore.WorkspaceAppId(wid1, aid1),
                new AppStore.WorkspaceAppId(wid2, aid2)
        ));

        when(mockService.generateForApp(wid1, aid1, com.followwise.identity.SystemUser.ID, false, true))
                .thenThrow(new RuntimeException("Simulated error"));

        when(mockService.generateForApp(wid2, aid2, com.followwise.identity.SystemUser.ID, false, true))
                .thenReturn(new ChargeGenerationResult(false, 3, 1, 0, 0, 0, null, null, null));

        ChargeGenerationScheduler scheduler = new ChargeGenerationScheduler(mockAppStore, mockService);
        assertDoesNotThrow(() -> scheduler.runAll());
    }

    private void assertDoesNotThrow(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            org.junit.jupiter.api.Assertions.fail("Expected no exception but threw " + e.getMessage());
        }
    }
}

@TestPropertySource(properties = "app.scheduler-enabled=true")
class ChargeGenerationSchedulerEnabledTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void schedulerBeanPresentWhenPropertyTrue() {
        assertTrue(context.containsBean("chargeGenerationScheduler"));
    }
}
