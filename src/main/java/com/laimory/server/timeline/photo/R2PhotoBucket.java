package com.laimory.server.timeline.photo;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Cloudflare R2 사진 bucket과 그 전용 S3 API 클라이언트 묶음.
 *
 * <p>{@link S3Client}/{@link S3Presigner}를 빈으로 따로 노출하지 않는다 — 같은 타입의 AWS S3 빈과 섞여
 * 주입 대상이 모호해지지 않게 R2 쪽은 이 묶음으로만 꺼낸다. R2 자격증명은 AWS 기본 체인과 분리된
 * 전용 static provider로만 쓰인다({@link PhotoStorageConfig}).
 *
 * @param name      R2 bucket 이름
 * @param client    삭제·목록용 클라이언트(R2 S3 API endpoint)
 * @param presigner presigned PUT 발급용
 */
public record R2PhotoBucket(String name, S3Client client, S3Presigner presigner) implements AutoCloseable {

    @Override
    public void close() {
        client.close();
        presigner.close();
    }
}
