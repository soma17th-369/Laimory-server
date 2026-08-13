package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class TimelineDraftCleanupSchedulingTest {

    @Test
    void usesExplicitConfigurableSeoulZone() throws NoSuchMethodException {
        Method method = TimelineDraftCleanupScheduler.class.getDeclaredMethod("cleanupExpiredDrafts");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${app.draft.cleanup-cron:0 0 4 * * *}");
        assertThat(scheduled.zone()).isEqualTo("${app.draft.cleanup-zone:Asia/Seoul}");
    }
}
