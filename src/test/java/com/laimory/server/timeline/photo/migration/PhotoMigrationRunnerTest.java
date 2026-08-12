package com.laimory.server.timeline.photo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * migration runner 단위 검증 — mode별 dispatch, 성공 0/실패 1 exit code, 로그의
 * 건수 전용·무식별자 불변식(fail-closed 메시지는 그대로, 그 외 예외는 클래스 이름만).
 */
class PhotoMigrationRunnerTest {

    private final PhotoObjectCopyMigration copyMigration = mock(PhotoObjectCopyMigration.class);
    private final PhotoUrlRewriteMigration rewriteMigration = mock(PhotoUrlRewriteMigration.class);
    private final LegacyPhotoObjectDeleteMigration deleteMigration =
            mock(LegacyPhotoObjectDeleteMigration.class);
    private final List<Integer> exitCodes = new ArrayList<>();

    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(PhotoMigrationRunner.class)).addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(PhotoMigrationRunner.class)).detachAppender(logAppender);
    }

    private PhotoMigrationRunner runner(PhotoMigrationMode mode) {
        return new PhotoMigrationRunner(mode, copyMigration, rewriteMigration, deleteMigration,
                exitCodes::add);
    }

    private String allLogText() {
        StringBuilder text = new StringBuilder();
        logAppender.list.forEach(event -> text.append(event.getFormattedMessage()).append('\n'));
        return text.toString();
    }

    @Test
    void copyVerify_runsCopyAndExitsZero() {
        when(copyMigration.execute())
                .thenReturn(new PhotoObjectCopyMigration.Result(2, 5, 4, 1));

        runner(PhotoMigrationMode.COPY_VERIFY).run(new DefaultApplicationArguments());

        verify(copyMigration).execute();
        verifyNoInteractions(rewriteMigration);
        verifyNoInteractions(deleteMigration);
        assertThat(exitCodes).containsExactly(0);
        assertThat(allLogText())
                .contains("usersProcessed=2")
                .contains("objectsCopied=4")
                .contains("objectsAlreadyPresent=1");
    }

    @Test
    void rewriteUrls_runsRewrite() {
        when(rewriteMigration.execute())
                .thenReturn(new PhotoUrlRewriteMigration.Result(2, 3, 3, 0, 4, 4, 0));

        runner(PhotoMigrationMode.REWRITE_URLS).run(new DefaultApplicationArguments());

        verify(rewriteMigration).execute();
        verifyNoInteractions(copyMigration);
        verifyNoInteractions(deleteMigration);
        assertThat(exitCodes).containsExactly(0);
        assertThat(allLogText())
                .contains("stagingRewritten=3")
                .contains("finalRewritten=4");
    }

    @Test
    void deleteLegacy_runsVerifiedDelete() {
        when(deleteMigration.execute())
                .thenReturn(new LegacyPhotoObjectDeleteMigration.Result(2, 5, 5, 0));

        runner(PhotoMigrationMode.DELETE_LEGACY).run(new DefaultApplicationArguments());

        verify(deleteMigration).execute();
        verifyNoInteractions(copyMigration, rewriteMigration);
        assertThat(exitCodes).containsExactly(0);
        assertThat(allLogText())
                .contains("usersProcessed=2")
                .contains("objectsVerified=5")
                .contains("objectsDeleted=5")
                .contains("objectsRemaining=0");
    }

    @Test
    void abortedMigration_exitsOneAndLogsCountOnlyMessage() {
        when(copyMigration.execute())
                .thenThrow(new PhotoMigrationAbortedException("pending photo delete job 존재로 중단: "
                        + "pendingDeleteJobs=2"));

        runner(PhotoMigrationMode.COPY_VERIFY).run(new DefaultApplicationArguments());

        assertThat(exitCodes).containsExactly(1);
        assertThat(allLogText()).contains("pendingDeleteJobs=2");
    }

    @Test
    void unexpectedException_exitsOneAndLogsOnlyExceptionClassName() {
        // 예외 메시지에 식별자가 실려 올 수 있는 경로 — 클래스 이름만 남고 메시지는 로그에 없어야 한다.
        when(copyMigration.execute())
                .thenThrow(new IllegalStateException("simulated-identifier-value-1234567890abcdef"));

        runner(PhotoMigrationMode.COPY_VERIFY).run(new DefaultApplicationArguments());

        assertThat(exitCodes).containsExactly(1);
        assertThat(allLogText())
                .contains("exceptionType=java.lang.IllegalStateException")
                .doesNotContain("simulated-identifier-value-1234567890abcdef");
    }
}
