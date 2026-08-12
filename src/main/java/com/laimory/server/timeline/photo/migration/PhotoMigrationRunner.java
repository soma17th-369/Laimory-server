package com.laimory.server.timeline.photo.migration;

import java.util.function.IntConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * property로 게이트되는 PHOTO migration 진입점(#284). {@code app.photo.migration.mode}가 설정된
 * 기동에서만 빈으로 존재하며({@link PhotoMigrationConfig}), 모드 실행 후 exit code(성공 0/실패 1)와
 * 함께 프로세스를 종료한다 — #285 runbook이 maintenance window에 수동 실행하는 one-shot 도구다.
 * 일반 서비스 기동에는 property가 없어 이 빈 자체가 생성되지 않는다.
 *
 * <p>로그는 건수만 남긴다. fail-closed 중단({@link PhotoMigrationAbortedException})은 건수 전용
 * 메시지를 그대로 남기고, 그 외 예외는 식별자 유출 가능성을 차단하기 위해 <b>예외 클래스 이름만</b>
 * 남긴다(메시지·스택 미출력 — {@code TimelinePhotoDeleteWorker}의 exceptionType 관례).
 */
@Slf4j
class PhotoMigrationRunner implements ApplicationRunner {

    private final PhotoMigrationMode mode;
    private final PhotoObjectCopyMigration copyMigration;
    private final PhotoUrlRewriteMigration rewriteMigration;
    private final IntConsumer exitHandler;

    PhotoMigrationRunner(PhotoMigrationMode mode,
                         PhotoObjectCopyMigration copyMigration,
                         PhotoUrlRewriteMigration rewriteMigration,
                         IntConsumer exitHandler) {
        this.mode = mode;
        this.copyMigration = copyMigration;
        this.rewriteMigration = rewriteMigration;
        this.exitHandler = exitHandler;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 1;
        try {
            log.info("photo migration 시작: mode={}", mode);
            switch (mode) {
                case COPY_VERIFY -> logCopyResult(copyMigration.execute());
                case REWRITE_URLS -> logRewriteResult(rewriteMigration.execute());
            }
            exitCode = 0;
        } catch (PhotoMigrationAbortedException e) {
            // 메시지는 건수 전용으로 구성된다(불변식) — 그대로 남겨도 식별자가 없다.
            log.error("photo migration fail-closed 중단: mode={} {}", mode, e.getMessage());
        } catch (Exception e) {
            log.error("photo migration 실패: mode={} exceptionType={}", mode, e.getClass().getName());
        }
        exitHandler.accept(exitCode);
    }

    private void logCopyResult(PhotoObjectCopyMigration.Result result) {
        log.info("photo object copy 완료: usersProcessed={} objectsListed={} objectsCopied={} "
                        + "objectsAlreadyPresent={} mismatches=0",
                result.usersProcessed(), result.objectsListed(), result.objectsCopied(),
                result.objectsAlreadyPresent());
    }

    private void logRewriteResult(PhotoUrlRewriteMigration.Result result) {
        log.info("photoUrl rewrite 완료: usersProcessed={} stagingExamined={} stagingRewritten={} "
                        + "stagingAlreadyTarget={} finalExamined={} finalRewritten={} "
                        + "finalAlreadyTarget={} mismatches=0",
                result.usersProcessed(), result.stagingRowsExamined(), result.stagingRowsRewritten(),
                result.stagingRowsAlreadyTarget(), result.finalRowsExamined(),
                result.finalRowsRewritten(), result.finalRowsAlreadyTarget());
    }
}
