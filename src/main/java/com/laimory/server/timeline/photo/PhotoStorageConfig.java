package com.laimory.server.timeline.photo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 사진 저장 인프라 빈 설정.
 *
 * <p>{@link S3Client}(삭제용)와 {@link S3Presigner}(presigned PUT URL 발급용)를 리전과
 * {@code DefaultCredentialsProvider}(기본 자격증명 체인)만으로 생성한다. 두 빈 모두 생성 시점에 AWS를
 * 호출하지 않으므로(자격증명/리전은 호출 시점에 지연 해석) 자격증명 없이도 컨텍스트 로드가 안전하다 —
 * 운영에선 EC2 인스턴스 프로파일/환경변수로 자격증명이 주입된다.
 */
@Configuration
public class PhotoStorageConfig {

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
}
