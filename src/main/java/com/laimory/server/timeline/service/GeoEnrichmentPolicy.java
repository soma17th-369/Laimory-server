package com.laimory.server.timeline.service;

import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.geo.Coordinate;
import com.laimory.server.geo.GeoLookupOutcome;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 지오코딩 부분 실패의 backend 품질 판정(D1/D2/D7) — HTTP 실행 모델(unique 좌표)과 분리된 판정 전용 모델.
 * 외부 호출·완료 순서를 알지 못하고 <b>materialize된 좌표별 최종 outcome</b>과 D3의 시간순 coordinate
 * observation만 입력으로 받는다 — 같은 outcome map이 주어지면 request 배열 순서나 reactive 완료 순서와
 * 무관하게 같은 결과를 낸다(request permutation 불변).
 *
 * <ul>
 *   <li><b>D1(전체 한도)</b>: unique coordinate 수 {@code U>0}와 실패 unique coordinate 수 {@code F}에
 *       대해 {@code 5F > U}(실패 20% 초과)면 거절한다. 정확히 20%는 허용한다. 비율은 floating point가
 *       아니라 정수 교차곱으로 판정한다. 같은 좌표의 반복 observation은 D1에서 한 번만 센다.</li>
 *   <li><b>D2(시간 축 guard)</b>: 시간순으로 안정 정렬된 observation에서 실패가 3개 연속하면 전체
 *       성공률과 무관하게 거절한다(“오전 이동정보 전체 손실” 방지). 같은 실패 좌표가 여러 시점에
 *       반복되면 반복 횟수대로 센다.</li>
 *   <li><b>D7(오류 우선순위)</b>: 거절 시 materialize된 실패 중 영구({@code clientMayRetryLater=false})가
 *       하나라도 있으면 {@code -1015}, 아니면 {@code -1014}다. circuit 때문에 호출하지 못한 좌표의 가상
 *       provider 응답은 판정하지 않는다.</li>
 * </ul>
 */
final class GeoEnrichmentPolicy {

    /** 연속 실패 거절 한도(D2) — 3개 연속부터 거절, 2개 연속은 이 규칙만으로 거절하지 않는다. */
    static final int CONSECUTIVE_FAILURE_LIMIT = 3;

    private GeoEnrichmentPolicy() {
    }

    /**
     * D3의 시간순 좌표 관측 하나 — rawId dedupe·기존 저장 item 제외 뒤 남은 source가 좌표 endpoint마다
     * 하나씩 만든다(STAY 1개, MOVEMENT start/end 2개, PHOTO 0개). {@code observationAt}은 STAY·MOVEMENT
     * START는 필수 {@code startAt}, MOVEMENT END는 {@code endAt}이 있으면 그 값, 없으면 {@code startAt}이다.
     * {@code endpointOrder}는 START(0) &lt; END(1) — 같은 시각·rawId의 결정적 tie-break.
     */
    record CoordinateObservation(Coordinate coordinate, LocalDateTime observationAt, String rawId,
            int endpointOrder) {
    }

    sealed interface Decision {

        /** 허용 — 성공 좌표는 실제 값, 실패 좌표는 D5 fallback으로 계속 진행한다. */
        record Allowed() implements Decision {
        }

        /** 거절 — draft 생성을 D7 우선순위의 지오코딩 502로 실패시킨다. */
        record Rejected(ExceptionType type, Rule rule) implements Decision {
        }

        enum Rule { FAILURE_RATIO, CONSECUTIVE_FAILURES }
    }

    /** {@code (observationAt, rawId 오름차순, endpoint START<END)} 안정 정렬 — D3의 시간순 판정 축. */
    static List<CoordinateObservation> sortChronologically(List<CoordinateObservation> observations) {
        return observations.stream()
                .sorted(Comparator.comparing(CoordinateObservation::observationAt)
                        .thenComparing(CoordinateObservation::rawId)
                        .thenComparingInt(CoordinateObservation::endpointOrder))
                .toList();
    }

    static Decision decide(List<CoordinateObservation> observations,
            Map<Coordinate, GeoLookupOutcome> outcomes) {
        long failedUnique = outcomes.values().stream()
                .filter(GeoLookupOutcome.Failure.class::isInstance)
                .count();
        if (failedUnique == 0) {
            return new Decision.Allowed();
        }
        // D1: 5F > U ⇔ F/U > 20% (정수 교차곱 — 정확히 20%는 허용).
        if (5 * failedUnique > outcomes.size()) {
            return new Decision.Rejected(rejectionType(outcomes), Decision.Rule.FAILURE_RATIO);
        }
        // D2: 시간순 연속 실패 3개.
        int consecutive = 0;
        for (CoordinateObservation observation : sortChronologically(observations)) {
            if (outcomes.get(observation.coordinate()) instanceof GeoLookupOutcome.Failure) {
                consecutive++;
                if (consecutive >= CONSECUTIVE_FAILURE_LIMIT) {
                    return new Decision.Rejected(rejectionType(outcomes), Decision.Rule.CONSECUTIVE_FAILURES);
                }
            } else {
                consecutive = 0;
            }
        }
        return new Decision.Allowed();
    }

    /** D7: materialize된 실패 중 영구가 하나라도 있으면 {@code -1015}, 아니면 {@code -1014}. */
    private static ExceptionType rejectionType(Map<Coordinate, GeoLookupOutcome> outcomes) {
        boolean anyPermanent = outcomes.values().stream()
                .anyMatch(outcome -> outcome instanceof GeoLookupOutcome.Failure failure
                        && !failure.failure().clientMayRetryLater());
        return anyPermanent ? ExceptionType.GEOCODING_PERMANENT_FAILURE : ExceptionType.GEOCODING_TRANSIENT_FAILURE;
    }
}
