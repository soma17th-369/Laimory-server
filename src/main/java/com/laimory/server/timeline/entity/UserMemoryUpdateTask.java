package com.laimory.server.timeline.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.laimory.server.timeline.TaskTokens;
import java.time.Instant;
import java.util.Objects;

/**
 * User Memory 갱신 비동기 작업의 상태 모델. Redis에 JSON으로 저장된다(JPA 엔티티 아님).
 *
 * <p>draft task와 달리 {@code status} 필드가 없다 — <b>key 존재 자체가 "진행 중"</b>이고 종결은 삭제다.
 * 입력 조회 단계가 없어 나눌 stage도, 재발급할 token도 없다(작업당 token 하나, 결과 POST가 유일한 호출).
 *
 * <p>{@code baseMemoryHash}는 접수 body에 실어 보낸 User Memory 문서의 SHA-256이다(문서가 없으면 null).
 * 결과를 적용하기 직전 현재 문서와 대조해, 그 사이 다른 날짜의 갱신이 문서를 교체했으면 결과를 폐기한다
 * — 적용하면 그 날짜의 기여가 조용히 사라지기 때문이다.
 *
 * <p>{@code processingStartedAt}은 접수 직전 캡처한 서버 절대 시각이며 소요 시간 로그의 기준이다.
 */
public record UserMemoryUpdateTask(
        long userId,
        long dailyRecordId,
        String tokenHash,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant processingStartedAt,
        String baseMemoryHash
) {

    public UserMemoryUpdateTask {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId는 양수여야 합니다");
        }
        if (dailyRecordId <= 0) {
            throw new IllegalArgumentException("dailyRecordId는 양수여야 합니다");
        }
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(processingStartedAt, "processingStartedAt");
    }

    /** 결과 저장 요청의 task token 검증. */
    public boolean matchesToken(String token) {
        return TaskTokens.matches(token, tokenHash);
    }
}
