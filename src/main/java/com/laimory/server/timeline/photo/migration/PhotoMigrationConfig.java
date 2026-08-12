package com.laimory.server.timeline.photo.migration;

import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * PHOTO migration 도구 배선(#284). {@code app.photo.migration.mode} property가 <b>설정된 기동에만</b>
 * 이 config와 하위 빈이 존재한다 — property 부재가 기본이며 일반 서비스 기동에 절대 개입하지 않는다.
 * 모드 값이 유효하지 않으면 {@link PhotoMigrationMode#fromProperty}가 기동을 실패시킨다(fail-fast).
 *
 * <p>환경 분기는 저장소 관례대로 {@code @ConditionalOnProperty} 하나가 유일한 스위치다
 * ({@code app.ai.mode} 등과 같은 형태). #285 runbook이 maintenance window에
 * {@code --app.photo.migration.mode=<mode>}로 수동 실행한다.
 */
@Configuration
@ConditionalOnProperty(name = "app.photo.migration.mode")
class PhotoMigrationConfig {

    @Bean
    PhotoObjectCopyMigration photoObjectCopyMigration(S3Client s3Client,
                                                      @Value("${photo.s3.bucket}") String bucket,
                                                      UserRepository userRepository,
                                                      SubjectMappingService subjectMappingService,
                                                      TimelinePhotoDeleteJobRepository photoDeleteJobRepository) {
        return new PhotoObjectCopyMigration(s3Client, bucket, userRepository, subjectMappingService,
                photoDeleteJobRepository);
    }

    @Bean
    PhotoUrlRewriteMigration photoUrlRewriteMigration(UserRepository userRepository,
                                                      SubjectMappingService subjectMappingService,
                                                      TimelineDraftSourceItemRepository draftSourceItemRepository,
                                                      TimelineItemRepository timelineItemRepository,
                                                      EntityManager entityManager,
                                                      PlatformTransactionManager transactionManager,
                                                      @Value("${photo.cdn.domain}") String cdnDomain) {
        return new PhotoUrlRewriteMigration(userRepository, subjectMappingService,
                draftSourceItemRepository, timelineItemRepository, entityManager, transactionManager,
                cdnDomain);
    }

    @Bean
    PhotoMigrationRunner photoMigrationRunner(@Value("${app.photo.migration.mode}") String mode,
                                              PhotoObjectCopyMigration photoObjectCopyMigration,
                                              PhotoUrlRewriteMigration photoUrlRewriteMigration,
                                              ApplicationContext applicationContext) {
        return new PhotoMigrationRunner(PhotoMigrationMode.fromProperty(mode),
                photoObjectCopyMigration, photoUrlRewriteMigration,
                exitCode -> System.exit(
                        SpringApplication.exit(applicationContext, () -> exitCode)));
    }
}
