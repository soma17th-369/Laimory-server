package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

class TimelinePhotoDeleteWorkerSchedulingTest {

    private static final String DEFAULT_CRON = "0 0 3 * * *";
    private static final String DEFAULT_ZONE = "Asia/Seoul";

    @Test
    void usesConfigurableDailyCronWithoutFixedDelay() throws NoSuchMethodException {
        Method method = TimelinePhotoDeleteWorker.class.getDeclaredMethod("deletePendingPhotoObjects");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron())
                .isEqualTo("${app.timeline.photo-delete.cron:" + DEFAULT_CRON + "}");
        assertThat(scheduled.zone())
                .isEqualTo("${app.timeline.photo-delete.zone:" + DEFAULT_ZONE + "}");
        assertThat(scheduled.fixedDelayString()).isEmpty();
    }

    @Test
    void defaultsScheduleNextRunAtThreeAmInSeoul() {
        CronExpression cron = CronExpression.parse(DEFAULT_CRON);
        ZoneId zone = ZoneId.of(DEFAULT_ZONE);
        ZonedDateTime beforeRun = ZonedDateTime.of(2026, 8, 6, 2, 59, 59, 0, zone);

        assertThat(cron.next(beforeRun))
                .isEqualTo(ZonedDateTime.of(2026, 8, 6, 3, 0, 0, 0, zone));
    }
}
