package com.laimory.server.timeline.service;

import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.repository.TimelineTaskStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * timeline draft 작업 상태 leaf 서비스. 자신과 1:1인 TimelineTaskStore에만 접근한다.
 * 처리중(PROCESSING)은 1시간, 종결 상태(SUCCESS/FAILED)는 24시간 TTL로 보관한다.
 *
 * <p>콜백 재시도는 token-use 카운터 없이 멱등 흡수한다 — AI direct-write 구조에서 서버는 finalize를
 * 하지 않으므로 유효한 동일 콜백의 반복은 terminal no-op 200으로 안전하다(카운터가 있으면 terminal
 * 저장 실패 뒤 정당한 재콜백까지 401로 막아 복구가 불가능해진다).
 *
 * <p><b>날짜 guard</b>: 같은 (userId, recordDate)에 AI 작업·삭제·Event PHOTO 추가가 겹치지 않게 하는 Redis lease다.
 * holder({@code task:{taskId}}, {@code delete:{operationId}}, {@code patch-photo-add:{operationId}})를 값으로
 * 새겨 자기 guard만 갱신·해제한다.
 * 해제 경계 규칙 — ① PROCESSING 저장 <b>전</b> 실패: 호출부가 즉시 해제. ② PROCESSING 저장 <b>후</b>
 * terminal 저장 실패: AI 진행 상태 불명이므로 해제하지 않고 TTL 만료에 맡긴다. ③ terminal 저장 <b>성공</b>:
 * 호출부가 compare-and-release. guard TTL은 1시간으로 PROCESSING TTL과 정렬한다(PROCESSING 저장 성공 시 refresh).
 */
@Service
@RequiredArgsConstructor
public class TimelineTaskService {

    private static final Duration PROCESSING_TTL = Duration.ofHours(1);
    private static final Duration TERMINAL_TTL = Duration.ofHours(24);
    // guard가 고아로 남아도(서버 크래시 등) PROCESSING task와 같은 주기로 자연 해제되게 정렬한다.
    private static final Duration DATE_GUARD_TTL = Duration.ofHours(1);

    private final TimelineTaskStore timelineTaskStore;

    /** draft 작업이 날짜 guard에 새기는 holder 값({@code task:{taskId}}). */
    public static String taskGuardHolder(String taskId) {
        return "task:" + taskId;
    }

    /** 삭제 작업이 날짜 guard에 새기는 holder 값({@code delete:{operationId}}). */
    public static String deleteGuardHolder(String operationId) {
        return "delete:" + operationId;
    }

    /** Event PATCH PHOTO 추가가 날짜 guard에 새기는 holder 값. */
    public static String photoAddGuardHolder(String operationId) {
        return "patch-photo-add:" + operationId;
    }

    /**
     * dailyRecordId는 선생성된 DailyRecord의 ID다 — 세 상태 모두 보존되며 폴링·guard 해제의 기준이다.
     * processingStartedAt은 폴링의 AI 작업 대기 경과 시간 기준(PROCESSING 전용 — terminal은 보존하지 않음).
     * userId는 task owner다 — 세 상태 전이 모두 필수로 받아 보존한다(폴링 소유권 대조·콜백 전이 기준).
     */
    public void createProcessing(String taskId, long userId, long dailyRecordId,
                                 TimelineDraftTask.TimelineWindow timelineWindow,
                                 String callbackTokenHash, Instant processingStartedAt) {
        timelineTaskStore.save(taskId,
                TimelineDraftTask.processing(userId, dailyRecordId, timelineWindow,
                        callbackTokenHash, processingStartedAt),
                PROCESSING_TTL);
    }

    public void markSuccess(String taskId, long userId, long dailyRecordId, String callbackTokenHash) {
        timelineTaskStore.save(taskId,
                TimelineDraftTask.success(userId, dailyRecordId, callbackTokenHash), TERMINAL_TTL);
    }

    /**
     * task를 FAILED로 종결한다. {@code failureCode}는 task 실패 분류({@link ErrorCode#TASK_FAILURE_CODES})만
     * 허용한다 — raw 문자열을 받지 않아, 내부 예외 메시지가 폴링 {@code body.error}로 유출되는 경로를
     * 시그니처에서 차단한다(상세는 호출부가 로그로만 남긴다).
     */
    public void markFailed(String taskId, long userId, long dailyRecordId, ErrorCode failureCode,
                           String callbackTokenHash) {
        if (!ErrorCode.TASK_FAILURE_CODES.contains(failureCode)) {
            throw new IllegalStateException("task 실패 분류 코드가 아닙니다: " + failureCode);
        }
        timelineTaskStore.save(taskId,
                TimelineDraftTask.failed(userId, dailyRecordId, failureCode.name(), callbackTokenHash),
                TERMINAL_TTL);
    }

    public Optional<TimelineDraftTask> find(String taskId) {
        return timelineTaskStore.find(taskId);
    }

    /** 날짜 guard를 holder 명의로 선점한다. false = 같은 날짜에 진행 중인 작업이 있음(ERROR_1016 거절 대상). */
    public boolean claimDateGuard(long userId, LocalDate recordDate, String holder) {
        return timelineTaskStore.claimDateGuard(userId, recordDate, holder, DATE_GUARD_TTL);
    }

    /**
     * 내 holder일 때만 guard TTL을 1시간으로 재갱신한다(PROCESSING 저장 성공 직후 task TTL과 정렬).
     * 반환값은 소유 재확인 결과다 — draft 생성은 true일 때만 AI dispatch를 진행한다(이중 dispatch 게이트).
     */
    public boolean refreshDateGuard(long userId, LocalDate recordDate, String holder) {
        return timelineTaskStore.refreshDateGuard(userId, recordDate, holder, DATE_GUARD_TTL);
    }

    /** 내 holder일 때만 guard를 해제한다(compare-and-release). false = 이미 만료됐거나 남의 guard(no-op). */
    public boolean releaseDateGuard(long userId, LocalDate recordDate, String holder) {
        return timelineTaskStore.releaseDateGuard(userId, recordDate, holder);
    }
}
