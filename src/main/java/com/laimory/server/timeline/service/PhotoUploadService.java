package com.laimory.server.timeline.service;

import com.laimory.server.timeline.TimelineDefaults;
import com.laimory.server.timeline.dto.PhotoUploadCreateResponse;
import com.laimory.server.timeline.dto.PhotoUploadItem;
import com.laimory.server.timeline.dto.PhotoUploadResponse;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

/**
 * presigned PUT URL 발급 오케스트레이터. 발급 전 cheap 검증(타입/크기/요청 한도) 후 photo마다 filename을 만들고
 * presigned PUT URL을 발급한다({@link S3PhotoStorageService} 합성, 레포는 없음).
 *
 * <p>서버는 데이터 경로 밖이라 업로드 바이트를 못 본다. 대신 요청의 {@code size}를 presigned PUT 서명의
 * content-length로 바인딩해 S3가 업로드 시점에 크기를 강제하게 한다(크기 우회 방지). 타입은 허용목록
 * (jpg/png/webp)으로 발급 전에 거르고, 요청 한도(개수·photo당 크기·총합)로 오브젝트 남발/비용 폭주를 막는다.
 * 위반은 모두 {@link IllegalArgumentException}으로 던져 400으로 응답한다.
 */
@Service
public class PhotoUploadService {

    private final S3PhotoStorageService s3PhotoStorageService;
    private final int maxCount;
    private final long maxSizePerPhotoBytes;
    private final long maxTotalSizeBytes;

    public PhotoUploadService(
            S3PhotoStorageService s3PhotoStorageService,
            @Value("${photo.upload.max-count}") int maxCount,
            @Value("${photo.upload.max-size-per-photo}") DataSize maxSizePerPhoto,
            @Value("${photo.upload.max-total-size}") DataSize maxTotalSize) {
        this.s3PhotoStorageService = s3PhotoStorageService;
        this.maxCount = maxCount;
        this.maxSizePerPhotoBytes = maxSizePerPhoto.toBytes();
        this.maxTotalSizeBytes = maxTotalSize.toBytes();
    }

    /**
     * 요청 photos를 검증한 뒤 같은 순서로 filename + presigned PUT URL을 발급한다. 버전별 분기는 없으나
     * 컨트롤러가 넘긴 {@code applicationVersion}을 받아 둔다(컨벤션 일관성).
     */
    public PhotoUploadCreateResponse createUploads(String applicationVersion, List<PhotoUploadItem> photos) {
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("photos is required");
        }
        if (photos.size() > maxCount) {
            throw new IllegalArgumentException("too many photos: " + photos.size() + " > " + maxCount);
        }

        long totalBytes = 0;
        for (int i = 0; i < photos.size(); i++) {
            PhotoUploadItem photo = photos.get(i);
            if (photo.contentType() == null || photo.contentType().isBlank()) {
                throw new IllegalArgumentException("contentType is required: index=" + i);
            }
            if (photo.size() == null) {
                throw new IllegalArgumentException("size is required: index=" + i);
            }
            if (photo.size() <= 0) {
                throw new IllegalArgumentException("size must be positive: index=" + i + ", size=" + photo.size());
            }
            if (photo.size() > maxSizePerPhotoBytes) {
                throw new IllegalArgumentException(
                        "photo too large: index=" + i + ", size=" + photo.size() + " > " + maxSizePerPhotoBytes);
            }
            totalBytes += photo.size();
        }
        if (totalBytes > maxTotalSizeBytes) {
            throw new IllegalArgumentException("total size exceeds limit: " + totalBytes + " > " + maxTotalSizeBytes);
        }

        List<PhotoUploadResponse> uploads = new ArrayList<>(photos.size());
        for (PhotoUploadItem photo : photos) {
            // newFilename이 허용 타입(jpg/png/webp)을 재검증한다(미지원 타입 → IAE → 400).
            String filename = PhotoObjectKeys.newFilename(photo.contentType());
            String fullKey = PhotoObjectKeys.fullKey(filename, TimelineDefaults.DEFAULT_USER_ID);
            String uploadUrl = s3PhotoStorageService.generatePresignedPutUrl(
                    fullKey, photo.contentType(), photo.size());
            uploads.add(new PhotoUploadResponse(filename, uploadUrl));
        }
        return new PhotoUploadCreateResponse(uploads);
    }
}
