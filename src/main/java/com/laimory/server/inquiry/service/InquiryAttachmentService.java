package com.laimory.server.inquiry.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.logging.LogSanitizer;
import com.laimory.server.inquiry.InquiryObjectKeys;
import com.laimory.server.inquiry.dto.InquiryAttachmentUploadCreateResponse;
import com.laimory.server.inquiry.dto.InquiryAttachmentUploadItem;
import com.laimory.server.inquiry.dto.InquiryAttachmentUploadResponse;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

/**
 * 문의 첨부의 S3 경계 — presigned PUT 발급(앱)과 열람용 CDN URL(앱 소유자·관리자).
 *
 * <p>사진 업로드({@code PhotoUploadService})와 같은 규칙을 쓴다: 허용 타입은 jpg/png/webp, {@code size}는
 * 서명의 Content-Length에 바인딩해 S3가 업로드 시점에 크기를 강제하고, 장당 상한은 사진과 같은 property를
 * 공유한다. 다른 점은 요청당 개수 상한({@link InquiryObjectKeys#MAX_ATTACHMENTS})과 key prefix뿐이다.
 * 서버는 업로드 완료를 확인하지 않는다(접수 시 S3 실존 검사 없음 — 계획의 승인된 결정).
 */
@Slf4j
@Service
public class InquiryAttachmentService {

    private final S3PhotoStorageService s3PhotoStorageService;
    private final long maxSizePerPhotoBytes;
    private final long maxSizePerPhotoMb;
    private final String cdnDomain;

    public InquiryAttachmentService(S3PhotoStorageService s3PhotoStorageService,
                                    @Value("${photo.upload.max-size-per-photo}") DataSize maxSizePerPhoto,
                                    @Value("${photo.cdn.domain}") String cdnDomain) {
        this.s3PhotoStorageService = s3PhotoStorageService;
        this.maxSizePerPhotoBytes = maxSizePerPhoto.toBytes();
        this.maxSizePerPhotoMb = maxSizePerPhoto.toMegabytes();
        this.cdnDomain = cdnDomain;
    }

    /** 요청 attachments를 검증한 뒤 같은 순서로 filename + presigned PUT URL을 발급한다. */
    public InquiryAttachmentUploadCreateResponse createUploads(String applicationVersion, UUID subjectId,
                                                              List<InquiryAttachmentUploadItem> attachments) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        if (attachments == null || attachments.isEmpty()) {
            throw new IllegalArgumentException("attachments is required");
        }
        if (attachments.size() > InquiryObjectKeys.MAX_ATTACHMENTS) {
            throw new BusinessException(ExceptionType.PHOTO_COUNT_EXCEEDED, InquiryObjectKeys.MAX_ATTACHMENTS);
        }
        for (int i = 0; i < attachments.size(); i++) {
            InquiryAttachmentUploadItem attachment = attachments.get(i);
            if (attachment == null) {
                throw new IllegalArgumentException("attachments[" + i + "] is required");
            }
            if (attachment.contentType() == null || attachment.contentType().isBlank()) {
                throw new IllegalArgumentException("contentType is required: index=" + i);
            }
            if (!PhotoObjectKeys.isSupported(attachment.contentType())) {
                log.warn("unsupported inquiry attachment content-type: index={} contentType={}",
                        i, LogSanitizer.sanitize(attachment.contentType(), 100));
                throw new BusinessException(ExceptionType.UNSUPPORTED_PHOTO_FORMAT);
            }
            if (attachment.size() == null || attachment.size() <= 0) {
                throw new IllegalArgumentException("size must be positive: index=" + i);
            }
            if (attachment.size() > maxSizePerPhotoBytes) {
                throw new BusinessException(ExceptionType.PHOTO_SIZE_EXCEEDED, maxSizePerPhotoMb);
            }
        }

        List<InquiryAttachmentUploadResponse> uploads = new ArrayList<>(attachments.size());
        for (InquiryAttachmentUploadItem attachment : attachments) {
            String filename = PhotoObjectKeys.newFilename(attachment.contentType());
            String uploadUrl = s3PhotoStorageService.generatePresignedPutUrl(
                    InquiryObjectKeys.fullKey(filename, subjectId), attachment.contentType(), attachment.size());
            uploads.add(new InquiryAttachmentUploadResponse(filename, uploadUrl));
        }
        return new InquiryAttachmentUploadCreateResponse(uploads);
    }

    /**
     * 열람용 URL(#529 — 앱 소유자 상세와 관리자 상세 공용) — 사진({@code PhotoUrlService})과 같은 무서명·만료 없는
     * CloudFront 고정 URL이다.
     * 사진 bucket의 OAC 읽기 정책이 bucket 전체 범위라 {@code inquiries/} prefix도 CDN이 서빙한다 — 정책을
     * {@code photos/}로 좁히면 이 URL은 서버 에러 없이 403이 된다.
     */
    public String cdnUrl(UUID subjectId, String filename) {
        return "https://" + cdnDomain + "/" + InquiryObjectKeys.fullKey(filename, subjectId);
    }

}
