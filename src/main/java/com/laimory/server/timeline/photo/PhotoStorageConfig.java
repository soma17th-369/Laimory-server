package com.laimory.server.timeline.photo;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 사진 저장 인프라 빈 설정.
 *
 * <p>{@link S3Client}(삭제용)와 {@link S3Presigner}(presigned PUT URL 발급용)를 리전과
 * {@code DefaultCredentialsProvider}(기본 자격증명 체인)만으로 생성한다. 두 빈 모두 생성 시점에 AWS를
 * 호출하지 않으므로(자격증명/리전은 호출 시점에 지연 해석) 자격증명 없이도 컨텍스트 로드가 안전하다 —
 * 운영에선 EC2 인스턴스 프로파일/환경변수로 자격증명이 주입된다.
 *
 * <p>R2({@code photo.r2.bucket}이 있을 때만)는 같은 S3 API를 R2 endpoint로 쓰는 별도 클라이언트를
 * {@link R2PhotoBucket}으로 묶어 만든다. 자격증명은 R2 전용 static provider뿐이다 — 기본 체인에 R2 키를
 * 섞으면 Secrets Manager·AgentCore 클라이언트까지 그 키로 뜰 수 있고, 배포 preflight도 {@code .env}의
 * AWS 표준 자격증명 이름을 금지한다.
 */
@Configuration
public class PhotoStorageConfig {

    /** R2 S3 API는 region을 쓰지 않지만 SDK가 요구한다 — R2 규약 값은 {@code auto}다. */
    private static final Region R2_REGION = Region.of("auto");

    @Bean
    public S3Client s3Client(@Value("${aws.region:ap-northeast-2}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(@Value("${aws.region:ap-northeast-2}") String region) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * R2 bucket 묶음. path-style로 host를 {@code <account>.r2.cloudflarestorage.com} 하나로 고정하고,
     * checksum은 API가 요구할 때만 계산·검증한다(SDK 2.30+ 기본값은 모든 요청에 CRC를 붙인다).
     *
     * @throws IllegalStateException bucket은 있는데 endpoint·키 중 하나라도 비었을 때(기동 실패)
     */
    @Bean
    @ConditionalOnExpression("!'${photo.r2.bucket:}'.isBlank()")
    public R2PhotoBucket r2PhotoBucket(
            @Value("${photo.r2.bucket}") String bucket,
            @Value("${photo.r2.endpoint:}") String endpoint,
            @Value("${photo.r2.access-key-id:}") String accessKeyId,
            @Value("${photo.r2.secret-access-key:}") String secretAccessKey) {
        if (endpoint.isBlank() || accessKeyId.isBlank() || secretAccessKey.isBlank()) {
            throw new IllegalStateException(
                    "photo.r2.bucket이 설정되면 photo.r2.endpoint·access-key-id·secret-access-key도 필요합니다");
        }
        URI endpointUri = URI.create(endpoint);
        StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();

        S3Client client = S3Client.builder()
                .endpointOverride(endpointUri)
                .region(R2_REGION)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(endpointUri)
                .region(R2_REGION)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        return new R2PhotoBucket(bucket, client, presigner);
    }
}
