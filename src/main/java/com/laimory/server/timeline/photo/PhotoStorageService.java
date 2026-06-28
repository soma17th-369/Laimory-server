package com.laimory.server.timeline.photo;

/**
 * 사진 객체 저장소 추상화. 업로드용 presigned PUT URL 발급과 객체 삭제만 책임진다.
 *
 * <p>업로드는 서버가 직접 바이트를 받지 않고 S3 presigned PUT URL을 발급하면, 클라이언트가 그 URL로
 * S3에 직접 PUT 한다(서버는 바디를 거치지 않는다). 운영 구현은 {@link S3PhotoStorageService}(AWS S3),
 * 테스트는 in-memory fake로 대체한다.
 *
 * <p>서빙 URL(비서명 CloudFront URL) 구성은 이 인터페이스의 책임이 아니다({@link PhotoUrlService} 참조) —
 * 저장과 표현(URL)을 분리한다.
 */
public interface PhotoStorageService {

    /**
     * 주어진 객체 key/content-type에 묶인 S3 presigned PUT URL을 발급한다.
     *
     * <p>content-type을 서명에 바인딩하므로 클라이언트는 PUT 시 동일한 {@code Content-Type} 헤더를 보내야 한다
     * (의도된 계약).
     *
     * @param objectKey   저장소 내 전체 S3 객체 key(예: {@code {sha256hex(userId)}/photos/uuid.jpg})
     * @param contentType 업로드할 객체의 content-type(예: {@code image/jpeg})
     * @return 클라이언트가 직접 PUT 할 수 있는 presigned https URL
     */
    String generatePresignedPutUrl(String objectKey, String contentType);

    /**
     * 주어진 객체 key의 객체를 삭제한다(없으면 no-op).
     *
     * @param objectKey 삭제할 전체 S3 객체 key
     */
    void delete(String objectKey);
}
