package com.laimory.server.notice.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.logging.LogSanitizer;
import com.laimory.server.notice.dto.NoticeThumbnailUploadResponse;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

/**
 * 공지 썸네일의 S3 경계 — presigned PUT 발급(관리자 웹)과 CDN URL 파생(#560).
 *
 * <p>문의 첨부({@code InquiryAttachmentService})와 같은 규칙을 쓴다: 허용 타입은 jpg/png/webp, {@code size}는 서명의
 * Content-Length에 바인딩해 S3가 업로드 시점에 크기를 강제하고, 장당 상한은 사진과 같은 property를 공유한다.
 * 다른 점은 key가 subject namespace 밖의 {@code notices/} prefix라는 것뿐이다 — 공지는 사용자 소유가 아니라
 * 계정 삭제(#302) 대상이 아니다. 관리자 웹은 브라우저라 이 업로드는 사진 bucket의 CORS 규칙(관리자 origin의 PUT
 * 허용)에 의존한다. 서버는 업로드 완료를 확인하지 않는다(사진·문의 첨부와 같은 계약).
 */
@Slf4j
@Service
public class NoticeThumbnailService {

    private static final String PREFIX = "notices/";

    private final S3PhotoStorageService s3PhotoStorageService;
    private final long maxSizePerPhotoBytes;
    private final long maxSizePerPhotoMb;
    private final String cdnDomain;

    public NoticeThumbnailService(S3PhotoStorageService s3PhotoStorageService,
                                  @Value("${photo.upload.max-size-per-photo}") DataSize maxSizePerPhoto,
                                  @Value("${photo.cdn.domain}") String cdnDomain) {
        this.s3PhotoStorageService = s3PhotoStorageService;
        this.maxSizePerPhotoBytes = maxSizePerPhoto.toBytes();
        this.maxSizePerPhotoMb = maxSizePerPhoto.toMegabytes();
        this.cdnDomain = cdnDomain;
    }

    /** 타입·크기를 검증한 뒤 새 filename과 그 key의 presigned PUT URL을 발급한다. 공지와 무관하다(DB 조회 없음). */
    public NoticeThumbnailUploadResponse createUpload(String contentType, long size) {
        if (!PhotoObjectKeys.isSupported(contentType)) {
            log.warn("unsupported notice thumbnail content-type: contentType={}",
                    LogSanitizer.sanitize(contentType, 100));
            throw new BusinessException(ExceptionType.UNSUPPORTED_PHOTO_FORMAT);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        if (size > maxSizePerPhotoBytes) {
            throw new BusinessException(ExceptionType.PHOTO_SIZE_EXCEEDED, maxSizePerPhotoMb);
        }
        String filename = PhotoObjectKeys.newFilename(contentType);
        String uploadUrl = s3PhotoStorageService.generatePresignedPutUrl(PREFIX + filename, contentType, size);
        return new NoticeThumbnailUploadResponse(filename, uploadUrl);
    }

    /**
     * 사진·문의 첨부와 같은 무서명·만료 없는 CloudFront 고정 URL. 사진 bucket의 OAC 읽기 정책이 bucket 전체
     * 범위라 {@code notices/} prefix도 CDN이 서빙한다 — 정책을 좁히면 이 URL은 서버 에러 없이 403이 된다.
     */
    public String cdnUrl(String filename) {
        return "https://" + cdnDomain + "/" + PREFIX + filename;
    }
}
