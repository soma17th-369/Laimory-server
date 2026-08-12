package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.id.SubjectId;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.logging.LogSanitizer;
import com.laimory.server.timeline.dto.PhotoUploadCreateResponse;
import com.laimory.server.timeline.dto.PhotoUploadItem;
import com.laimory.server.timeline.dto.PhotoUploadResponse;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

/**
 * presigned PUT URL 발급 오케스트레이터. 발급 전 cheap 검증(타입/크기/요청 한도) 후 photo마다 filename을 만들고
 * presigned PUT URL을 발급한다({@link S3PhotoStorageService} 합성, 레포는 없음).
 *
 * <p>서버는 데이터 경로 밖이라 업로드 바이트를 못 본다. 대신 요청의 {@code size}를 presigned PUT 서명의
 * content-length로 바인딩해 S3가 업로드 시점에 크기를 강제하게 한다(크기 우회 방지). 타입은 허용목록
 * (jpg/png/webp)으로 발급 전에 거르고, 요청 한도(개수·photo당 크기)로 오브젝트 남발/비용 폭주를 막는다.
 * 총합 캡은 두지 않는다 — 개수x장당으로 이미 유계이고, 장당 유효한 정상 선택이 집계에서 거절되는
 * UX 엣지만 만들어서 제거했다(비용 비상시엔 개수/장당 env로 조인다).
 *
 * <p>예외 정책: 정상 사용자가 유발 가능한 한도/포맷 위반(개수·크기·미지원 타입)은
 * {@link BusinessException}(-1004/1005/1007, 한도값을 메시지에 포함)으로, 형식 불량(누락·비양수·null 요소)은
 * {@link IllegalArgumentException}(→400 -400)으로 던진다.
 */
@Slf4j
@Service
public class PhotoUploadService {

    private final S3PhotoStorageService s3PhotoStorageService;
    private final int maxCount;
    private final long maxSizePerPhotoBytes;
    // 에러 메시지 {0}용 MB 표기(바이트 원값 노출 방지). 설정은 whole MB 단위 전제(application.properties 참고).
    private final long maxSizePerPhotoMb;

    public PhotoUploadService(
            S3PhotoStorageService s3PhotoStorageService,
            @Value("${photo.upload.max-count}") int maxCount,
            @Value("${photo.upload.max-size-per-photo}") DataSize maxSizePerPhoto) {
        this.s3PhotoStorageService = s3PhotoStorageService;
        this.maxCount = maxCount;
        this.maxSizePerPhotoBytes = maxSizePerPhoto.toBytes();
        this.maxSizePerPhotoMb = maxSizePerPhoto.toMegabytes();
    }

    /**
     * 요청 photos를 검증한 뒤 같은 순서로 filename + presigned PUT URL을 발급한다. 버전별 분기는 없으나
     * 컨트롤러가 넘긴 {@code applicationVersion}을 받아 둔다(컨벤션 일관성).
     * subjectId는 S3 full key의 hash namespace를 결정한다.
     */
    public PhotoUploadCreateResponse createUploads(String applicationVersion, SubjectId subjectId,
                                                   List<PhotoUploadItem> photos) {
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("photos is required");
        }
        if (photos.size() > maxCount) {
            throw new BusinessException(ExceptionType.PHOTO_COUNT_EXCEEDED, maxCount);
        }

        for (int i = 0; i < photos.size(); i++) {
            PhotoUploadItem photo = photos.get(i);
            if (photo == null) {
                throw new IllegalArgumentException("photos[" + i + "] is required");
            }
            if (photo.contentType() == null || photo.contentType().isBlank()) {
                throw new IllegalArgumentException("contentType is required: index=" + i);
            }
            if (!PhotoObjectKeys.isSupported(photo.contentType())) {
                // 사용자 파일 선택으로 유발 가능(HEIC 등) → 전용 코드. 원문 타입은 응답에 echo하지 않고 로그로만.
                log.warn("unsupported photo content-type: index={} contentType={}",
                        i, LogSanitizer.sanitize(photo.contentType(), 100));
                throw new BusinessException(ExceptionType.UNSUPPORTED_PHOTO_FORMAT);
            }
            if (photo.size() == null) {
                throw new IllegalArgumentException("size is required: index=" + i);
            }
            if (photo.size() <= 0) {
                throw new IllegalArgumentException("size must be positive: index=" + i + ", size=" + photo.size());
            }
            if (photo.size() > maxSizePerPhotoBytes) {
                throw new BusinessException(ExceptionType.PHOTO_SIZE_EXCEEDED, maxSizePerPhotoMb);
            }
        }

        List<PhotoUploadResponse> uploads = new ArrayList<>(photos.size());
        for (PhotoUploadItem photo : photos) {
            // newFilename의 허용 타입 검증은 방어선(위 isSupported가 사전 차단).
            String filename = PhotoObjectKeys.newFilename(photo.contentType());
            String fullKey = PhotoObjectKeys.subjectFullKey(filename, subjectId);
            String uploadUrl = s3PhotoStorageService.generatePresignedPutUrl(
                    fullKey, photo.contentType(), photo.size());
            uploads.add(new PhotoUploadResponse(filename, uploadUrl));
        }
        return new PhotoUploadCreateResponse(uploads);
    }
}
