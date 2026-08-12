package com.laimory.server.user.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code app.subject.migration.mode} 게이트 배선 검증({@link ApplicationContextRunner},
 * {@code PhotoMigrationWiringTest} 선례) — property 부재 시 runner·executor 빈이 아예 생성되지 않아
 * 일반 서비스 기동에 개입하지 않고, 유효 모드에서만 배선되며, 알 수 없는 모드와 photo migration
 * 동시 설정(상호 배타 가드)은 기동을 실패시킨다.
 *
 * <p>{@code ApplicationContextRunner}는 {@code ApplicationRunner#run}을 호출하지 않으므로 빈 존재
 * 검증이 migration 실행·{@code System.exit} 없이 안전하다.
 */
class SubjectMigrationWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SubjectMigrationConfig.class)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(SubjectMappingService.class, () -> mock(SubjectMappingService.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void modeMissing_createsNoMigrationBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SubjectMigrationRunner.class);
            assertThat(context).doesNotHaveBean(SubjectMappingBackfillMigration.class);
            assertThat(context).doesNotHaveBean(SubjectOwnerBackfillMigration.class);
        });
    }

    @Test
    void validMode_wiresRunnerAndExecutors() {
        runner.withPropertyValues("app.subject.migration.mode=backfill-mappings")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SubjectMigrationRunner.class);
                    assertThat(context).hasSingleBean(SubjectMappingBackfillMigration.class);
                    assertThat(context).hasSingleBean(SubjectOwnerBackfillMigration.class);
                });
    }

    @Test
    void unknownMode_failsContextStartup() {
        // 오타가 조용한 no-op이나 잘못된 모드 실행이 되지 않도록 기동 자체가 실패해야 한다.
        runner.withPropertyValues("app.subject.migration.mode=backfill-mapping")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("app.subject.migration.mode"));
    }

    @Test
    void photoMigrationModeAlsoSet_failsContextStartup() {
        // 상호 배타 가드 — 두 one-shot runner의 SpringApplication.exit 경합을 기동 단계에서 차단한다.
        runner.withPropertyValues(
                        "app.subject.migration.mode=backfill-mappings",
                        "app.photo.migration.mode=copy-verify")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("동시에 설정할 수 없다"));
    }
}
