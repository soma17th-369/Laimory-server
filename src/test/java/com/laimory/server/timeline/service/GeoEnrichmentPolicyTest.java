package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.geo.Coordinate;
import com.laimory.server.geo.GeoLookupOutcome;
import com.laimory.server.geo.GeoPlace;
import com.laimory.server.geo.MapPlaceLookupException;
import com.laimory.server.timeline.service.GeoEnrichmentPolicy.CoordinateObservation;
import com.laimory.server.timeline.service.GeoEnrichmentPolicy.Decision;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 부분 실패 품질 판정(D1/D2/D7) 단위 검증 — 판정은 materialize된 outcome map과 시간순 observation만 보고,
 * request 순서·완료 순서와 무관해야 한다(request permutation 불변).
 */
class GeoEnrichmentPolicyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 7, 8, 9, 0);

    private static Coordinate coordinate(int index) {
        return new Coordinate(37.0 + index, 127.0 + index);
    }

    private static CoordinateObservation observation(int coordinateIndex, int minuteOffset) {
        return new CoordinateObservation(coordinate(coordinateIndex), BASE.plusMinutes(minuteOffset),
                "raw-" + coordinateIndex + "-" + minuteOffset, 0);
    }

    private static GeoLookupOutcome success() {
        return new GeoLookupOutcome.Success(new GeoPlace("주소", List.of()));
    }

    private static GeoLookupOutcome transientFailure() {
        return new GeoLookupOutcome.Failure(
                MapPlaceLookupException.remoteTransient("coord2address http 500", null));
    }

    private static GeoLookupOutcome permanentFailure() {
        return new GeoLookupOutcome.Failure(
                MapPlaceLookupException.remotePermanent("coord2address http 429", null));
    }

    private static GeoLookupOutcome localRejectedFailure() {
        return new GeoLookupOutcome.Failure(
                MapPlaceLookupException.localRejected("coord2address pool acquire rejected", null));
    }

    /**
     * 좌표 0..count-1을 1분 간격 시간순 observation으로 만들고, {@code failedIndexes}만 실패 outcome을 준다.
     */
    private static Decision decide(int count, List<Integer> failedIndexes, GeoLookupOutcome failure) {
        List<CoordinateObservation> observations = new ArrayList<>();
        Map<Coordinate, GeoLookupOutcome> outcomes = new HashMap<>();
        for (int i = 0; i < count; i++) {
            observations.add(observation(i, i));
            outcomes.put(coordinate(i), failedIndexes.contains(i) ? failure : success());
        }
        return GeoEnrichmentPolicy.decide(observations, outcomes);
    }

    // ── T4: D1 경계 — 정확히 20% 허용, 초과 거절(정수 교차곱) ──

    @ParameterizedTest
    @CsvSource({"1,5,false", "2,10,false", "1,4,true", "3,10,true"})
    void decide_rejectsOnlyWhenFailureRatioExceedsTwentyPercent(int failed, int total, boolean rejected) {
        List<Integer> failedIndexes = new ArrayList<>();
        // 연속 규칙과 겹치지 않도록 실패를 최대한 분산한다(간격 ≥ 실패간 성공 1개 이상).
        for (int i = 0; i < failed; i++) {
            failedIndexes.add(i * Math.max(2, total / failed));
        }
        Decision decision = decide(total, failedIndexes, transientFailure());

        if (rejected) {
            assertThat(decision).isInstanceOfSatisfying(Decision.Rejected.class,
                    r -> assertThat(r.rule()).isEqualTo(Decision.Rule.FAILURE_RATIO));
        } else {
            assertThat(decision).isInstanceOf(Decision.Allowed.class);
        }
    }

    @Test
    void decide_allowsAllSuccess() {
        assertThat(decide(5, List.of(), transientFailure())).isInstanceOf(Decision.Allowed.class);
    }

    // ── T5/T6/T7: D2 연속 3개 규칙 ──

    @Test
    void decide_allowsTwoConsecutiveFailures_whenRatioWithinLimit() {
        // (F,U)=(2,10) — 전체 정확히 20%, 연속 2개 → 두 규칙 모두 허용.
        assertThat(decide(10, List.of(3, 4), transientFailure())).isInstanceOf(Decision.Allowed.class);
    }

    @Test
    void decide_rejectsThreeConsecutiveFailures_evenWhenRatioAtTwentyPercent() {
        // (F,U)=(3,15) — 전체는 정확히 20%(5*3=15 ≯ 15)라 D1은 허용하지만 연속 3개가 D2로 거절.
        Decision decision = decide(15, List.of(5, 6, 7), transientFailure());
        assertThat(decision).isInstanceOfSatisfying(Decision.Rejected.class,
                r -> assertThat(r.rule()).isEqualTo(Decision.Rule.CONSECUTIVE_FAILURES));
    }

    @Test
    void decide_allowsThreeScatteredFailures_whenRatioAtTwentyPercent() {
        // 같은 (F,U)=(3,15)라도 실패가 분산돼 연속 3개가 없으면 허용 — 연속성이 판정 축임을 고정.
        assertThat(decide(15, List.of(2, 6, 11), transientFailure())).isInstanceOf(Decision.Allowed.class);
    }

    // ── T8: request permutation 불변 — 같은 outcome map이면 입력 순서와 무관 ──

    @Test
    void decide_isInvariant_underObservationInputPermutation() {
        // (F,U)=(3,15) — 전체 정확히 20%라 D1은 허용, 연속 3개(5,6,7)로 D2가 거절하는 구성.
        List<CoordinateObservation> observations = new ArrayList<>();
        Map<Coordinate, GeoLookupOutcome> outcomes = new HashMap<>();
        for (int i = 0; i < 15; i++) {
            observations.add(observation(i, i));
            outcomes.put(coordinate(i), List.of(5, 6, 7).contains(i) ? transientFailure() : success());
        }
        Decision sorted = GeoEnrichmentPolicy.decide(observations, outcomes);

        List<CoordinateObservation> shuffled = new ArrayList<>(observations);
        Collections.reverse(shuffled);
        Decision reversed = GeoEnrichmentPolicy.decide(shuffled, outcomes);

        // 입력 리스트 순서를 뒤집어도 시간순 정렬 뒤 판정하므로 결과가 같다(연속 3개 거절).
        assertThat(sorted).isInstanceOfSatisfying(Decision.Rejected.class,
                r -> assertThat(r.rule()).isEqualTo(Decision.Rule.CONSECUTIVE_FAILURES));
        assertThat(reversed).isEqualTo(sorted);
    }

    // ── T9: 같은 실패 좌표의 반복 — D1은 1회, D2는 반복 횟수 ──

    @Test
    void decide_countsRepeatedCoordinateOnceForRatio_butPerObservationForConsecutive() {
        // 실패 좌표 1개가 서로 다른 시점 3개에 반복: unique로는 (F,U)=(1,6) → 20% 이하 허용이지만
        // 시간순 observation 3개 연속 실패라 D2가 거절한다.
        Coordinate failing = coordinate(99);
        List<CoordinateObservation> observations = new ArrayList<>();
        Map<Coordinate, GeoLookupOutcome> outcomes = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            observations.add(observation(i, i));
            outcomes.put(coordinate(i), success());
        }
        for (int repeat = 0; repeat < 3; repeat++) {
            observations.add(new CoordinateObservation(failing, BASE.plusMinutes(10 + repeat),
                    "raw-repeat-" + repeat, 0));
        }
        outcomes.put(failing, transientFailure());

        Decision decision = GeoEnrichmentPolicy.decide(observations, outcomes);

        assertThat(decision).isInstanceOfSatisfying(Decision.Rejected.class,
                r -> assertThat(r.rule()).isEqualTo(Decision.Rule.CONSECUTIVE_FAILURES));
    }

    // ── T10: 정렬 계약 — observationAt → rawId → endpoint(START<END) ──

    @Test
    void sortChronologically_breaksTies_byRawIdThenEndpointOrder() {
        CoordinateObservation laterTime = new CoordinateObservation(coordinate(1), BASE.plusHours(1), "a", 0);
        CoordinateObservation sameTimeRawB = new CoordinateObservation(coordinate(2), BASE, "b", 0);
        CoordinateObservation sameTimeRawAEnd = new CoordinateObservation(coordinate(3), BASE, "a", 1);
        CoordinateObservation sameTimeRawAStart = new CoordinateObservation(coordinate(4), BASE, "a", 0);

        List<CoordinateObservation> sorted = GeoEnrichmentPolicy.sortChronologically(
                List.of(laterTime, sameTimeRawB, sameTimeRawAEnd, sameTimeRawAStart));

        assertThat(sorted).containsExactly(sameTimeRawAStart, sameTimeRawAEnd, sameTimeRawB, laterTime);
    }

    // ── T15/D7: materialized aggregate 오류 우선순위 — 영구 포함 시 -1015, 순서 무관 ──

    @Test
    void decide_prefersPermanentCode_whenMixedFailures_regardlessOfMapOrder() {
        // permanent+transient 혼합 aggregate — 완료/순회 순서와 무관하게 항상 -1015.
        List<CoordinateObservation> observations = new ArrayList<>();
        Map<Coordinate, GeoLookupOutcome> outcomes = new HashMap<>();
        observations.add(observation(0, 0));
        observations.add(observation(1, 1));
        observations.add(observation(2, 2));
        outcomes.put(coordinate(0), transientFailure());
        outcomes.put(coordinate(1), permanentFailure());
        outcomes.put(coordinate(2), transientFailure());

        Decision decision = GeoEnrichmentPolicy.decide(observations, outcomes);

        assertThat(decision).isInstanceOfSatisfying(Decision.Rejected.class, rejected ->
                assertThat(rejected.type()).isEqualTo(ExceptionType.GEOCODING_PERMANENT_FAILURE));
    }

    @Test
    void decide_usesTransientCode_whenAllFailuresTransient() {
        Decision decision = decide(2, List.of(0, 1), transientFailure());
        assertThat(decision).isInstanceOfSatisfying(Decision.Rejected.class, rejected ->
                assertThat(rejected.type()).isEqualTo(ExceptionType.GEOCODING_TRANSIENT_FAILURE));
    }

    @Test
    void decide_usesPermanentCode_forLowRatioButConsecutivePermanentFailures() {
        // 연속 규칙 거절이라도 D7은 aggregate의 permanent 여부로 코드를 정한다.
        Decision decision = decide(15, List.of(5, 6, 7), permanentFailure());
        assertThat(decision).isInstanceOfSatisfying(Decision.Rejected.class, rejected -> {
            assertThat(rejected.rule()).isEqualTo(Decision.Rule.CONSECUTIVE_FAILURES);
            assertThat(rejected.type()).isEqualTo(ExceptionType.GEOCODING_PERMANENT_FAILURE);
        });
    }

    // ── #262: LOCAL_REJECTED는 upstream 품질 신호가 아니다 — D1/D2 계수 제외 ──

    @Test
    void decide_allowsAllLocalRejected_evenAtFullFailureRatio() {
        // 5/5 전부 local 거절(혼잡)이어도 upstream 실패 0 → 허용(partial). 예전엔 D1 100%로 502였다.
        assertThat(decide(5, List.of(0, 1, 2, 3, 4), localRejectedFailure()))
                .isInstanceOf(Decision.Allowed.class);
    }

    @Test
    void decide_countsOnlyUpstreamFailures_forRatio() {
        // 10개 중 upstream 실패 2(20% 허용선) + local 3 — local을 세면 50%로 거절되지만 제외하므로 허용.
        List<CoordinateObservation> observations = new ArrayList<>();
        Map<Coordinate, GeoLookupOutcome> outcomes = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            observations.add(observation(i, i));
            GeoLookupOutcome outcome = success();
            if (i == 0 || i == 5) {
                outcome = transientFailure();
            } else if (i >= 7) {
                outcome = localRejectedFailure();
            }
            outcomes.put(coordinate(i), outcome);
        }
        assertThat(GeoEnrichmentPolicy.decide(observations, outcomes)).isInstanceOf(Decision.Allowed.class);
    }

    @Test
    void decide_localFailure_doesNotBreakConsecutiveUpstreamRun() {
        // 시간순 [upstream, upstream, local, upstream]: local은 무정보(skip)라 연속 3으로 거절돼야 한다.
        List<CoordinateObservation> observations = new ArrayList<>();
        Map<Coordinate, GeoLookupOutcome> outcomes = new HashMap<>();
        for (int i = 0; i < 15; i++) {
            observations.add(observation(i, i));
            GeoLookupOutcome outcome = success();
            if (i == 5 || i == 6 || i == 8) {
                outcome = transientFailure();
            } else if (i == 7) {
                outcome = localRejectedFailure();
            }
            outcomes.put(coordinate(i), outcome);
        }
        Decision decision = GeoEnrichmentPolicy.decide(observations, outcomes);
        assertThat(decision).isInstanceOf(Decision.Rejected.class);
        assertThat(((Decision.Rejected) decision).rule()).isEqualTo(Decision.Rule.CONSECUTIVE_FAILURES);
    }

    @Test
    void decide_successStillResetsConsecutiveRun_withLocalNearby() {
        // [upstream, upstream, 성공, local, upstream]: 성공이 reset하므로 연속 3 미달 → 허용.
        List<CoordinateObservation> observations = new ArrayList<>();
        Map<Coordinate, GeoLookupOutcome> outcomes = new HashMap<>();
        for (int i = 0; i < 15; i++) {
            observations.add(observation(i, i));
            GeoLookupOutcome outcome = success();
            if (i == 5 || i == 6 || i == 9) {
                outcome = transientFailure();
            } else if (i == 8) {
                outcome = localRejectedFailure();
            }
            outcomes.put(coordinate(i), outcome);
        }
        assertThat(GeoEnrichmentPolicy.decide(observations, outcomes)).isInstanceOf(Decision.Allowed.class);
    }
}
