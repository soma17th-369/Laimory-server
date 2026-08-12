package com.laimory.server.user.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
 * subject migration runner 단위 검증 — mode별 dispatch, 성공 0/실패 1 exit code, 로그의
 * 건수 전용·무식별자 불변식(fail-closed 메시지는 그대로, 그 외 예외는 클래스 이름만)
 * ({@code PhotoMigrationRunnerTest}와 같은 형태).
 */
class SubjectMigrationRunnerTest {

    private final SubjectMappingBackfillMigration mappingBackfill =
            mock(SubjectMappingBackfillMigration.class);
    private final SubjectOwnerBackfillMigration ownerBackfill =
            mock(SubjectOwnerBackfillMigration.class);
    private final List<Integer> exitCodes = new ArrayList<>();

    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(SubjectMigrationRunner.class)).addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(SubjectMigrationRunner.class)).detachAppender(logAppender);
    }

    private SubjectMigrationRunner runner(SubjectMigrationMode mode) {
        return new SubjectMigrationRunner(mode, mappingBackfill, ownerBackfill, exitCodes::add);
    }

    private String allLogText() {
        StringBuilder text = new StringBuilder();
        logAppender.list.forEach(event -> text.append(event.getFormattedMessage()).append('\n'));
        return text.toString();
    }

    @Test
    void backfillMappings_runsMappingBackfillAndExitsZero() {
        when(mappingBackfill.execute())
                .thenReturn(new SubjectMappingBackfillMigration.Result(5, 2, 3, 5, 5));

        runner(SubjectMigrationMode.BACKFILL_MAPPINGS).run(new DefaultApplicationArguments());

        verify(mappingBackfill).execute();
        verifyNoInteractions(ownerBackfill);
        assertThat(exitCodes).containsExactly(0);
        assertThat(allLogText())
                .contains("usersProcessed=5")
                .contains("mappingsCreated=2")
                .contains("mappingsAlreadyPresent=3");
    }

    @Test
    void backfillOwners_runsOwnerBackfillWithVerification() {
        when(ownerBackfill.execute()).thenReturn(new SubjectOwnerBackfillMigration.Result(
                3, 4, 2, 1,
                new SubjectOwnerBackfillMigration.Verification(0, 0, 0, 0, 0, 0)));

        runner(SubjectMigrationMode.BACKFILL_OWNERS).run(new DefaultApplicationArguments());

        verify(ownerBackfill).execute();
        verifyNoInteractions(mappingBackfill);
        assertThat(exitCodes).containsExactly(0);
        assertThat(allLogText())
                .contains("dailyRecordsBackfilled=4")
                .contains("dailyRecordsNullSubject=0");
    }

    @Test
    void verifyOwners_runsVerificationOnly() {
        when(ownerBackfill.verify())
                .thenReturn(new SubjectOwnerBackfillMigration.Verification(
                        0, 0, 0, 0, 0, 0));

        runner(SubjectMigrationMode.VERIFY_OWNERS).run(new DefaultApplicationArguments());

        verify(ownerBackfill).verify();
        verifyNoMoreInteractions(ownerBackfill); // execute()가 불리면 안 된다 — 검증 전용 모드
        verifyNoInteractions(mappingBackfill);
        assertThat(exitCodes).containsExactly(0);
        assertThat(allLogText()).contains("pushOwnerMismatch=0");
    }

    @Test
    void abortedMigration_exitsOneAndLogsCountOnlyMessage() {
        when(mappingBackfill.execute())
                .thenThrow(new SubjectMigrationAbortedException(
                        "mapping 수 불일치로 중단: users=5 mappings=4 mappingsCreated=0"
                                + " mappingsAlreadyPresent=4"));

        runner(SubjectMigrationMode.BACKFILL_MAPPINGS).run(new DefaultApplicationArguments());

        assertThat(exitCodes).containsExactly(1);
        assertThat(allLogText()).contains("users=5 mappings=4");
    }

    @Test
    void unexpectedException_exitsOneAndLogsOnlyExceptionClassName() {
        // 예외 메시지에 식별자가 실려 올 수 있는 경로 — 클래스 이름만 남고 메시지는 로그에 없어야 한다.
        when(ownerBackfill.execute())
                .thenThrow(new IllegalStateException("simulated-identifier-value-1234567890abcdef"));

        runner(SubjectMigrationMode.BACKFILL_OWNERS).run(new DefaultApplicationArguments());

        assertThat(exitCodes).containsExactly(1);
        assertThat(allLogText())
                .contains("exceptionType=java.lang.IllegalStateException")
                .doesNotContain("simulated-identifier-value-1234567890abcdef");
    }
}
