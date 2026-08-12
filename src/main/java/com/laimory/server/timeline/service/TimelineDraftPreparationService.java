package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * draft POST의 DailyRecord 선생성 + source staging 저장 트랜잭션 경계 전담 빈.
 * {@link TimelineDraftTaskService}가 Spring 프록시를 통해 호출한다(self-invocation 회피 —
 * {@link DailyTimelineService} 선례).
 *
 * <p>AI dispatch 전에 record 메타데이터(recordAt/recordTimezone)를 DB에 먼저 확정한다 — AI는 body의
 * dailyRecordId로 record를 읽으므로 이 커밋이 dispatch보다 선행해야 한다. SAVED 재확인이 던지면 전체
 * 롤백된다(신규 record·메타데이터 갱신·source rows 모두). 같은 (subjectId, recordDate) 동시 생성은
 * 별도 직렬화 없이 경합할 수 있고 unique 위반은 그대로 전파된다. 이 동시성 계약의 보완은 후속 작업 범위다.
 */
@Service
@RequiredArgsConstructor
public class TimelineDraftPreparationService {

    private final DailyRecordService dailyRecordService;
    private final TimelineDraftSourceItemService timelineDraftSourceItemService;

    /**
     * DailyRecord를 find-or-create(기존 DRAFT면 recordAt/recordTimezone 즉시 갱신)하고 SAVED를 재확인한 뒤
     * source rows를 같은 트랜잭션으로 저장한다. 반환값은 commit될 record ID다 — 호출부가 Redis task와
     * AI dispatch body에 싣는다.
     */
    @WithSpan
    @Transactional
    public long prepareDraft(UUID subjectId, LocalDate recordDate, LocalDateTime recordAt, String recordTimezone,
                             List<TimelineDraftSourceItem> sourceRows) {
        DailyRecord record = dailyRecordService.findOrCreateDraft(subjectId, recordDate, recordAt, recordTimezone);
        if (record.getStatus() == DailyRecordStatus.SAVED) {
            throw new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
        }
        timelineDraftSourceItemService.saveAll(sourceRows);
        return record.getDailyRecordId();
    }
}
