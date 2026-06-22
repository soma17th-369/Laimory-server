package com.laimory.server.timeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.RecordDates;
import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.TimelineDefaults;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 작성 작업 생성(POST) 오케스트레이터. recordDate 계산 + SAVED 거절 + draft 행 저장 + PROCESSING 기록 + AI 디스패치를 합성한다.
 * (leaf가 아닌 합성 오케스트레이터라 여러 leaf 서비스를 주입한다.)
 *
 * <p>요청 스레드는 디스패치를 블로킹하지 않는다(dispatch는 fire-and-forget; v1 no-op).
 * 같은 날짜로 PROCESSING task가 떠 있는 동안 두 번째 POST가 와도 둘 다 통과할 수 있다(plan 모호점 4, MVP 수용).
 *
 * <p>⚠️ 단계 순서가 load-bearing이다: draft 행을 <b>먼저 저장·커밋</b>한 뒤 Redis에 PROCESSING을 기록한다 —
 * 그래야 "PROCESSING인데 draft 없음" 오판(콜백의 idempotent-recovery 판정을 깨뜨림)이 안 생긴다.
 */
@Service
@RequiredArgsConstructor
public class TimelineDraftTaskService {

    private final DailyRecordService dailyRecordService;
    private final TimelineTaskService timelineTaskService;
    private final TimelineDraftSourceItemService timelineDraftSourceItemService;
    private final CardSuggestionDispatcher cardSuggestionDispatcher;
    private final ObjectMapper objectMapper;

    /**
     * 작성 작업을 만들고 taskId를 반환한다. recordDate는 anchor instant + zone에서 정오 경계로 계산한다.
     * 이미 SAVED인 daily record면 409(ResponseStatusException)로 거절한다.
     * dispatch가 동기 예외를 던지면 task를 FAILED로 고정하고 taskId는 정상 반환한다(클라가 폴링으로 결과 확인).
     */
    public String createDraftTask(String applicationVersion, LocalDateTime recordAnchorAt, String recordTimeZone,
                                  List<SourceItemDto> sourceItems) {
        if (recordAnchorAt == null) {
            throw new IllegalArgumentException("recordAnchorAt is required");
        }
        if (recordTimeZone == null) {
            throw new IllegalArgumentException("recordTimeZone is required");
        }
        if (sourceItems == null || sourceItems.isEmpty()) {
            throw new IllegalArgumentException("sourceItems is required");
        }

        // recordTimeZone은 저장·역산용이라 유효성만 검증(잘못된 zone → IAE → 400). 날짜는 anchor 벽시계의 정오 경계로 산출(zone 불필요).
        RecordDates.requireValidTimeZone(recordTimeZone);
        LocalDate recordDate = RecordDates.resolveRecordDate(recordAnchorAt);

        dailyRecordService.findByUserIdAndRecordDate(TimelineDefaults.DEFAULT_USER_ID, recordDate)
                .filter(record -> record.getStatus() == DailyRecordStatus.SAVED)
                .ifPresent(record -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "daily record already SAVED: " + record.getDailyRecordId());
                });

        String taskId = UUID.randomUUID().toString();
        // one-time 콜백 토큰: 원문은 AI에만 전달하고 서버는 해시만 보관한다.
        String callbackToken = CallbackTokens.generate();
        String callbackTokenHash = CallbackTokens.hash(callbackToken);

        // 1. draft 행을 먼저 저장·커밋한다(Redis보다 먼저 — 위 클래스 주석의 순서 불변식). 실패 시 미커밋 상태로 전파(500).
        List<TimelineDraftSourceItem> rows = sourceItems.stream()
                .map(src -> TimelineDraftSourceItem.of(
                        taskId, TimelineDefaults.DEFAULT_USER_ID, recordDate, recordTimeZone,
                        src.itemId(), src.itemType(), src.startAt(), src.endAt(), src.summary(),
                        objectMapper.valueToTree(src.payload())))
                .toList();
        timelineDraftSourceItemService.saveAll(rows);

        // 2. Redis PROCESSING 기록. 실패하면 방금 저장한 draft를 보상 삭제하고 전파한다(고아 draft 방지).
        try {
            timelineTaskService.createProcessing(taskId, recordDate, callbackTokenHash);
        } catch (RuntimeException e) {
            timelineDraftSourceItemService.deleteByTaskId(taskId);
            throw e;
        }

        // 3. AI dispatch. 동기 예외(RuntimeException)면 task를 FAILED로 고정하고 draft는 보존(cleanup이 나중에 정리).
        //    taskId는 정상 반환해 클라가 폴링으로 실패를 확인하게 한다.
        try {
            cardSuggestionDispatcher.dispatch(taskId, callbackToken);
        } catch (RuntimeException e) {
            timelineTaskService.markFailed(taskId, recordDate,
                    "card suggestion dispatch failed: " + e.getMessage(), callbackTokenHash);
        }

        return taskId;
    }
}
