package com.laimory.server.timeline.photo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code app.photo.migration.mode} 게이트 배선 검증({@link ApplicationContextRunner},
 * {@code SubjectHmacKeyWiringTest} 선례) — property 부재 시 runner·executor 빈이 아예 생성되지 않아
 * 일반 서비스 기동에 개입하지 않고, 유효 모드에서만 배선되며, 알 수 없는 모드는 기동을 실패시킨다.
 *
 * <p>{@code ApplicationContextRunner}는 {@code ApplicationRunner#run}을 호출하지 않으므로 빈 존재
 * 검증이 migration 실행·{@code System.exit} 없이 안전하다.
 */
class PhotoMigrationWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PhotoMigrationConfig.class)
            .withBean(S3Client.class, () -> mock(S3Client.class))
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(SubjectMappingService.class, () -> mock(SubjectMappingService.class))
            .withBean(TimelinePhotoDeleteJobRepository.class,
                    () -> mock(TimelinePhotoDeleteJobRepository.class))
            .withBean(TimelineDraftSourceItemRepository.class,
                    () -> mock(TimelineDraftSourceItemRepository.class))
            .withBean(TimelineItemRepository.class, () -> mock(TimelineItemRepository.class))
            .withBean(EntityManager.class, () -> mock(EntityManager.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withPropertyValues("photo.s3.bucket=test-bucket", "photo.cdn.domain=cdn.test");

    @Test
    void modeMissing_createsNoMigrationBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PhotoMigrationRunner.class);
            assertThat(context).doesNotHaveBean(PhotoObjectCopyMigration.class);
            assertThat(context).doesNotHaveBean(PhotoUrlRewriteMigration.class);
        });
    }

    @Test
    void validMode_wiresRunnerAndExecutors() {
        runner.withPropertyValues("app.photo.migration.mode=copy-verify")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PhotoMigrationRunner.class);
                    assertThat(context).hasSingleBean(PhotoObjectCopyMigration.class);
                    assertThat(context).hasSingleBean(PhotoUrlRewriteMigration.class);
                });
    }

    @Test
    void unknownMode_failsContextStartup() {
        // 오타가 조용한 no-op이나 잘못된 모드 실행이 되지 않도록 기동 자체가 실패해야 한다.
        runner.withPropertyValues("app.photo.migration.mode=copy-vrify")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("app.photo.migration.mode"));
    }
}
