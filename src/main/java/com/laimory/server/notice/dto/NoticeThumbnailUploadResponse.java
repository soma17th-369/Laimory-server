package com.laimory.server.notice.dto;

/**
 * 관리자 공지 썸네일 업로드 발급 결과(#560). 관리자 웹은 {@code uploadUrl}로 S3에 직접 PUT하고, 성공한 뒤에만
 * {@code filename}을 공지에 저장한다. {@code uploadUrl}은 access log에서 이름으로 마스킹된다.
 */
public record NoticeThumbnailUploadResponse(String filename, String uploadUrl) {
}
