package com.laimory.server.user.migration;

import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * subject backfill migration 도구 배선(#285). {@code app.subject.migration.mode} property가
 * <b>설정된 기동에만</b> 이 config와 하위 빈이 존재한다 — property 부재가 기본이며 일반 서비스 기동에
 * 절대 개입하지 않는다({@code PhotoMigrationConfig}와 같은 형태). 모드 값이 유효하지 않으면
 * {@link SubjectMigrationMode#fromProperty}가 기동을 실패시킨다(fail-fast).
 *
 * <p>#285 runbook이 maintenance window에 {@code --app.subject.migration.mode=<mode>}로 수동
 * 실행한다(모드는 반드시 한 번에 하나).
 */
@Configuration
@ConditionalOnProperty(name = "app.subject.migration.mode")
class SubjectMigrationConfig {

    /**
     * 상호 배타 가드 — photo migration({@code app.photo.migration.mode})과 동시 설정은 기동 실패다.
     * 두 runner 모두 실행 후 {@code SpringApplication.exit}를 부르는 one-shot이라 동시 활성화는
     * exit 경합으로 어느 쪽도 완주를 보장할 수 없다(runbook 계약: 모드는 반드시 하나씩).
     */
    SubjectMigrationConfig(Environment environment) {
        if (environment.containsProperty("app.photo.migration.mode")) {
            throw new IllegalStateException("app.subject.migration.mode와 app.photo.migration.mode는 "
                    + "동시에 설정할 수 없다 — migration은 한 번에 한 모드만 실행한다");
        }
    }

    @Bean
    SubjectMappingBackfillMigration subjectMappingBackfillMigration(
            UserRepository userRepository,
            SubjectMappingService subjectMappingService,
            JdbcTemplate jdbcTemplate) {
        return new SubjectMappingBackfillMigration(userRepository, subjectMappingService,
                jdbcTemplate);
    }

    @Bean
    SubjectOwnerBackfillMigration subjectOwnerBackfillMigration(
            UserRepository userRepository,
            SubjectMappingService subjectMappingService,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        return new SubjectOwnerBackfillMigration(userRepository, subjectMappingService,
                jdbcTemplate, transactionManager);
    }

    @Bean
    SubjectMigrationRunner subjectMigrationRunner(
            @Value("${app.subject.migration.mode}") String mode,
            SubjectMappingBackfillMigration subjectMappingBackfillMigration,
            SubjectOwnerBackfillMigration subjectOwnerBackfillMigration,
            ApplicationContext applicationContext) {
        return new SubjectMigrationRunner(SubjectMigrationMode.fromProperty(mode),
                subjectMappingBackfillMigration, subjectOwnerBackfillMigration,
                exitCode -> System.exit(
                        SpringApplication.exit(applicationContext, () -> exitCode)));
    }
}
