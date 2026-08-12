package com.laimory.server.user.migration;

import java.util.function.IntConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * property로 게이트되는 subject backfill migration 진입점(#285). {@code app.subject.migration.mode}가
 * 설정된 기동에서만 빈으로 존재하며({@link SubjectMigrationConfig}), 모드 실행 후 exit code(성공 0/
 * 실패 1)와 함께 프로세스를 종료한다 — #285 runbook이 maintenance window에 수동 실행하는 one-shot
 * 도구다({@code PhotoMigrationRunner}와 같은 형태). 일반 서비스 기동에는 property가 없어 이 빈 자체가
 * 생성되지 않는다.
 *
 * <p>로그는 건수만 남긴다. fail-closed 중단({@link SubjectMigrationAbortedException})은 건수 전용
 * 메시지를 그대로 남기고, 그 외 예외는 식별자 유출 가능성을 차단하기 위해 <b>예외 클래스 이름만</b>
 * 남긴다(메시지·스택 미출력).
 */
@Slf4j
class SubjectMigrationRunner implements ApplicationRunner {

    private final SubjectMigrationMode mode;
    private final SubjectMappingBackfillMigration mappingBackfill;
    private final SubjectOwnerBackfillMigration ownerBackfill;
    private final IntConsumer exitHandler;

    SubjectMigrationRunner(SubjectMigrationMode mode,
                           SubjectMappingBackfillMigration mappingBackfill,
                           SubjectOwnerBackfillMigration ownerBackfill,
                           IntConsumer exitHandler) {
        this.mode = mode;
        this.mappingBackfill = mappingBackfill;
        this.ownerBackfill = ownerBackfill;
        this.exitHandler = exitHandler;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 1;
        try {
            log.info("subject migration 시작: mode={}", mode);
            switch (mode) {
                case BACKFILL_MAPPINGS -> logMappingResult(mappingBackfill.execute());
                case BACKFILL_OWNERS -> logOwnerResult(ownerBackfill.execute());
                case VERIFY_OWNERS -> logVerification(ownerBackfill.verify());
            }
            exitCode = 0;
        } catch (SubjectMigrationAbortedException e) {
            // 메시지는 건수 전용으로 구성된다(불변식) — 그대로 남겨도 식별자가 없다.
            log.error("subject migration fail-closed 중단: mode={} {}", mode, e.getMessage());
        } catch (Exception e) {
            log.error("subject migration 실패: mode={} exceptionType={}", mode,
                    e.getClass().getName());
        }
        exitHandler.accept(exitCode);
    }

    private void logMappingResult(SubjectMappingBackfillMigration.Result result) {
        log.info("subject mapping backfill 완료: usersProcessed={} mappingsCreated={} "
                        + "mappingsAlreadyPresent={} userCount={} mappingCount={}",
                result.usersProcessed(), result.mappingsCreated(), result.mappingsAlreadyPresent(),
                result.userCount(), result.mappingCount());
    }

    private void logOwnerResult(SubjectOwnerBackfillMigration.Result result) {
        log.info("subject owner backfill 완료: usersProcessed={} dailyRecordsBackfilled={} "
                        + "stagingItemsBackfilled={} pushRegistrationsBackfilled={}",
                result.usersProcessed(), result.dailyRecordsBackfilled(),
                result.stagingItemsBackfilled(), result.pushRegistrationsBackfilled());
        logVerification(result.verification());
    }

    private void logVerification(SubjectOwnerBackfillMigration.Verification verification) {
        log.info("subject owner 검증 통과: dailyRecordsNullSubject={} stagingNullSubject={} "
                        + "pushNullSubject={} dailyRecordsOwnerMismatch={} "
                        + "stagingOwnerMismatch={} pushOwnerMismatch={}",
                verification.dailyRecordsNullSubject(), verification.stagingNullSubject(),
                verification.pushNullSubject(), verification.dailyRecordsOwnerMismatch(),
                verification.stagingOwnerMismatch(), verification.pushOwnerMismatch());
    }
}
