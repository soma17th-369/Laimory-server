package com.laimory.server.timeline.dto;

/**
 * presign 요청에 중첩되는 업로드 입력 요소(요청 바디 내부 요소라 방향 접미사 대신 도메인 이름).
 *
 * <p>{@code contentType}/{@code size}는 발급 전 검증(허용 타입·크기 한도)에 쓰고, {@code size}는 presigned PUT
 * 서명의 content-length로 바인딩해 S3가 업로드 시점에 정확한 크기를 강제하게 한다(크기 우회 방지).
 */
public record PhotoUploadItem(
        String contentType,
        Long size
) {
}
