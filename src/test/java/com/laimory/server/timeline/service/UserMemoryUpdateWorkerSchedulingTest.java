package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class UserMemoryUpdateWorkerSchedulingTest {

    @Test
    void usesExplicitConfigurableSeoulZone() throws NoSuchMethodException {
        Method method = UserMemoryUpdateWorker.class.getDeclaredMethod("dispatchPendingUpdates");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${app.user-memory.update.cron:0 30 4 * * *}");
        assertThat(scheduled.zone()).isEqualTo("${app.user-memory.update.zone:Asia/Seoul}");
    }
}
