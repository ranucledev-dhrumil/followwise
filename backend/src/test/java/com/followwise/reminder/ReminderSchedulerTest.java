package com.followwise.reminder;

import com.followwise.app.AppStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReminderSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AppStore.class, () -> mock(AppStore.class))
            .withBean(ReminderEngineService.class, () -> mock(ReminderEngineService.class))
            .withBean(ReminderEventStore.class, () -> mock(ReminderEventStore.class))
            .withBean(ReminderProperties.class, () -> mock(ReminderProperties.class))
            .withUserConfiguration(ReminderScheduler.class);

    @Test
    void testSchedulerConditionalOnBothSwitches() {
        // Both false -> absent
        contextRunner.withPropertyValues("app.scheduler-enabled=false", "app.reminders-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ReminderScheduler.class));

        // Only scheduler enabled -> absent
        contextRunner.withPropertyValues("app.scheduler-enabled=true", "app.reminders-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ReminderScheduler.class));

        // Only reminders enabled -> absent
        contextRunner.withPropertyValues("app.scheduler-enabled=false", "app.reminders-enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(ReminderScheduler.class));

        // Both true -> present
        contextRunner.withPropertyValues("app.scheduler-enabled=true", "app.reminders-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ReminderScheduler.class));
    }
}
