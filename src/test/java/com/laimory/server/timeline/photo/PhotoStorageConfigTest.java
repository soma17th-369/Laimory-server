package com.laimory.server.timeline.photo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 저장소 선택 배선 검증 — R2 빈은 bucket이 있을 때만 생기고, primary=r2는 R2 설정 없이 기동하지 않는다.
 * 클라이언트 생성은 원격 호출을 하지 않으므로 인프라 없이 컨텍스트만 띄운다.
 */
class PhotoStorageConfigTest {

    // 운영과 같은 Boot 변환기(`10m` → Duration)를 쓴다.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(PhotoStorageConfig.class, S3PhotoStorageService.class)
            .withPropertyValues("photo.s3.bucket=s3-bucket", "photo.upload.presign-ttl=10m");

    @Test
    void withoutR2BucketStartsWithS3OnlyAndNoR2Bean() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(R2PhotoBucket.class);
            assertThat(context).hasSingleBean(S3PhotoStorageService.class);
        });
    }

    @Test
    void withCompleteR2SettingsCreatesR2BucketForR2Primary() {
        runner.withPropertyValues(
                        "photo.storage.primary=r2",
                        "photo.r2.bucket=r2-bucket",
                        "photo.r2.endpoint=https://account.r2.cloudflarestorage.com",
                        "photo.r2.access-key-id=test-id",
                        "photo.r2.secret-access-key=test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(R2PhotoBucket.class).name()).isEqualTo("r2-bucket");
                });
    }

    @Test
    void r2BucketWithoutCredentialsFailsStartup() {
        runner.withPropertyValues(
                        "photo.r2.bucket=r2-bucket",
                        "photo.r2.endpoint=https://account.r2.cloudflarestorage.com")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void r2PrimaryWithoutR2SettingsFailsStartup() {
        runner.withPropertyValues("photo.storage.primary=r2")
                .run(context -> assertThat(context).hasFailed());
    }
}
