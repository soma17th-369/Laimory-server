package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.UserMemoryDigest;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateResultRequest;
import com.laimory.server.timeline.entity.UserMemoryUpdateTask;
import com.laimory.server.timeline.repository.UserMemoryUpdatePendingStore;
import com.laimory.server.timeline.repository.UserMemoryUpdateTaskStore;
import com.laimory.server.user.UserMemoryService;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI가 보낸 User Memory 갱신 결과를 적용한다(서버간 {@code /s/api}).
 *
 * <p><b>{@code daily_records}는 건드리지 않는다.</b> 하루 기록은 저장 API가 이미 SAVED로 커밋했고 이
 * 경로는 User Memory만 바꾼다 — 이 시점에 record가 SAVED인 것이 정상이다.
 *
 * <p>순서가 load-bearing이다: token 검증이 개인 데이터 접근보다 먼저다. 그 다음 base 지문을 대조해,
 * 접수 이후 다른 날짜의 갱신이 문서를 교체했으면 결과를 폐기한다(409) — 적용하면 그 날짜의 기여가
 * 조용히 사라진다.
 *
 * <p>성공·실패 어느 쪽이든 task를 지운다. 그래서 중복·뒤늦은 결과는 404가 되고 AI는 4xx를 재시도 중단
 * 신호로 읽는다. 사용자 guard는 지우지 않고 TTL에 맡긴다 — 그 사용자의 다음 접수는 다음 배치(24시간
 * 뒤)라 일찍 반납해 봐야 막는 것이 없고, 대조 없는 삭제는 남의 guard를 지울 위험만 남긴다.
 *
 * <p><b>미반영 작업 큐를 정리하는 곳도 여기다.</b> 접수 시점에는 성패를 알 수 없고 AI 계약이 "202 뒤
 * 백그라운드 처리 → 결과 API 호출"이라, 성패를 아는 유일한 지점이 이 호출이다 — 반영되면 그 날들을 큐에서
 * 빼고, 반영하지 못하면(FAILED·지문 불일치·계약 위반) 큐에 넣어 다음 배치가 다시 시도하게 한다.
 * 큐 항목은 최초 기록 시각을 유지하므로 재기록으로 포기 시한이 연장되지 않는다.
 *
 * <p>로그에 memory 문서 내용·PII를 남기지 않는다 — 식별자와 AI {@code errorCode}, 소요 시간만 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMemoryUpdateResultService {

    private final UserMemoryUpdateTaskStore taskStore;
    private final UserMemoryUpdatePendingStore pendingStore;
    private final UserMemoryService userMemoryService;
    private final Clock clock;

    /**
     * @throws BusinessException token 불일치 401 {@code -1002}, task 없음·만료·중복 404 {@code -1001},
     *                           base memory 불일치 409 {@code -1017}, 계약 위반 400 {@code -400}
     */
    public void applyResult(String applicationVersion, String taskId, String taskToken,
                            AiUserMemoryUpdateResultRequest request) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        UserMemoryUpdateTask task = taskStore.find(taskId)
                .orElseThrow(() -> new BusinessException(ExceptionType.SAVE_TASK_NOT_FOUND));
        if (!task.matchesToken(taskToken)) {
            throw new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH);
        }

        if (request.isFailed()) {
            finish(task, taskId, false);
            log.warn("User Memory 갱신 실패 통보: userId={} dailyRecordIds={} taskId={} aiErrorCode={} elapsedMs={}",
                    task.userId(), task.dailyRecordIds(), taskId, request.errorCode(), elapsedMillis(task));
            return;
        }
        if (!request.isSuccess() || request.userMemory() == null) {
            // 계약 위반(status 누락·SUCCESS인데 userMemory 없음). AI는 4xx를 재시도 중단으로 읽으므로
            // task를 남겨 봐야 TTL까지 guard만 잡고 있다 — 종결하고 다음 갱신 길을 터 준다.
            finish(task, taskId, false);
            log.error("User Memory 갱신 결과 계약 위반: userId={} dailyRecordIds={} taskId={} status={}",
                    task.userId(), task.dailyRecordIds(), taskId, request.status());
            throw new BusinessException(ExceptionType.VALIDATION_FAILED);
        }

        if (!Objects.equals(UserMemoryDigest.of(userMemoryService.find(task.userId())), task.baseMemoryHash())) {
            // 접수 이후 다른 날짜의 갱신이 문서를 교체했다. 적용하면 그 날짜 기여가 사라지므로 폐기한다.
            finish(task, taskId, false);
            log.warn("User Memory 갱신 폐기(base 문서 교체됨): userId={} dailyRecordIds={} taskId={}",
                    task.userId(), task.dailyRecordIds(), taskId);
            throw new BusinessException(ExceptionType.SAVE_TASK_STATE_CONFLICT);
        }

        userMemoryService.replace(task.userId(), request.userMemory());
        finish(task, taskId, true);
        log.info("User Memory 갱신 반영: userId={} dailyRecordIds={} taskId={} elapsedMs={}",
                task.userId(), task.dailyRecordIds(), taskId, elapsedMillis(task));
    }

    /**
     * 종결 = task 삭제 + 미반영 큐 정리. guard는 건드리지 않는다(TTL이 반납한다).
     *
     * @param applied 문서가 실제로 교체됐으면 그 날들을 큐에서 뺀다. 아니면 넣어 다음 배치가 다시 시도한다
     *                — 접수 시점에는 알 수 없던 성패가 확정되는 지점이 여기다
     */
    private void finish(UserMemoryUpdateTask task, String taskId, boolean applied) {
        taskStore.delete(taskId);
        if (applied) {
            pendingStore.removeAll(task.userId(), task.dailyRecordIds());
        } else {
            pendingStore.enqueueAll(task.userId(), task.dailyRecordIds(), clock.instant());
        }
    }

    private long elapsedMillis(UserMemoryUpdateTask task) {
        return Duration.between(task.processingStartedAt(), clock.instant()).toMillis();
    }
}
