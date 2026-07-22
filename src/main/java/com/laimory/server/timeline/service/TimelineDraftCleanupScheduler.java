package com.laimory.server.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보관기간을 초과한 draft source staging 행을 주기적으로 정리하는 스케줄러.
 * 행마다 PHOTO 사진의 S3 객체를 먼저 지운 뒤 행을 삭제한다(S3 삭제 실패 시 행을 남겨 다음 실행에서 재시도).
 *
 * <p>불변식: 보관기간(retentionDays, 기본 7일)은 PROCESSING_TTL(1시간)보다 훨씬 커야 한다(retention ≫ PROCESSING_TTL 1h).
 * 그래야 처리 중(in-flight)인 task의 draft가 cutoff에 걸려 조기 삭제되는 일이 없다.
 *
 * <p>대상은 만료된 <b>draft 행</b>(omitted 또는 FAILED task 잔여)의 사진뿐이다. AI가 채택한 source는 final
 * transaction에서 이미 삭제돼 여기 남지 않으므로 final Item이 참조하는 S3 객체를 지울 일이 없다.
 * presign만 받고 draft를 안 만든 orphan 객체도 대상이 아니다(MVP 잔여 인정).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelineDraftCleanupScheduler {

    /**
     * 허용 최소 보관기간(일). 1일(24h)이면 PROCESSING_TTL(1h)보다 충분히 크다.
     * 이보다 작은 값(0·음수 포함)은 active draft를 조기 삭제할 수 있어 기동 시 거부한다.
     */
    private static final long MIN_RETENTION_DAYS = 1;

    private final TimelineDraftSourceItemService timelineDraftSourceItemService;
    private final S3PhotoStorageService s3PhotoStorageService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${app.draft.retention-days}")
    private long retentionDays;

    /**
     * 클래스 불변식(retention ≫ PROCESSING_TTL 1h)을 기동 시점에 fail-fast로 강제한다.
     * 잘못된 설정이 04:00 cleanup 때 조용히 active draft를 지우는 대신, 배포 즉시 부팅을 실패시킨다.
     */
    @PostConstruct
    void validateRetentionDays() {
        if (retentionDays < MIN_RETENTION_DAYS) {
            throw new IllegalStateException(
                    "app.draft.retention-days는 최소 %d 이상이어야 한다(현재 %d). retention은 PROCESSING_TTL(1h)보다 훨씬 커야 active draft가 조기 삭제되지 않는다."
                            .formatted(MIN_RETENTION_DAYS, retentionDays));
        }
    }

    /**
     * 매일 04:00(서버 존)에 보관기간 초과 draft 행을 정리한다. 행마다 PHOTO 사진 S3 객체를 먼저 지우고
     * 성공한 행만 삭제한다 — S3 삭제가 실패하면 행을 남겨 다음 실행에서 재시도(고아 객체보다 행 보존 우선).
     */
    @Scheduled(cron = "${app.draft.cleanup-cron:0 0 4 * * *}")
    public void cleanupExpiredDrafts() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(retentionDays);
        List<TimelineDraftSourceItem> expired = timelineDraftSourceItemService.findCreatedBefore(cutoff);
        log.info("draft cleanup 시작: cutoff={}, retentionDays={}, expired={}", cutoff, retentionDays, expired.size());

        int deleted = 0;
        int failed = 0;
        for (TimelineDraftSourceItem row : expired) {
            try {
                if (row.getItemType() == ItemType.PHOTO) {
                    deletePhotoObject(row);
                }
                timelineDraftSourceItemService.deleteById(row.getTimelineDraftSourceItemId());
                deleted++;
            } catch (RuntimeException e) {
                // S3 삭제 실패 등으로 행을 남긴다 — 다음 실행에서 재시도(부분 실패가 나머지 행 정리를 막지 않음).
                failed++;
                log.warn("draft cleanup 실패(다음 실행 재시도): id={}, taskId={}",
                        row.getTimelineDraftSourceItemId(), row.getTaskId(), e);
            }
        }
        log.info("draft cleanup 완료: deleted={}, failed={}", deleted, failed);
    }

    /**
     * draft 행의 PHOTO payload에서 filename을 복원해 S3 사진 객체를 삭제한다. payload가 깨졌거나 filename이
     * 없으면(S3 키를 만들 수 없으면) 객체 삭제는 건너뛰되 행 삭제는 진행한다(만료 행 정리 우선; 객체는 orphan 인정).
     */
    private void deletePhotoObject(TimelineDraftSourceItem row) {
        PhotoPayload photo;
        try {
            photo = objectMapper.treeToValue(row.getPayload(), PhotoPayload.class);
        } catch (JsonProcessingException e) {
            log.warn("PHOTO payload 파싱 실패, S3 삭제 건너뜀: id={}", row.getTimelineDraftSourceItemId(), e);
            return;
        }
        if (photo.filename() == null || photo.filename().isBlank()) {
            log.warn("PHOTO payload filename 없음, S3 삭제 건너뜀: id={}", row.getTimelineDraftSourceItemId());
            return;
        }
        s3PhotoStorageService.delete(PhotoObjectKeys.fullKey(photo.filename(), row.getUserId()));
    }
}
