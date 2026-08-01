package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.geo.Coordinate;
import com.laimory.server.geo.GeoLookupOutcome;
import com.laimory.server.geo.GeoMetrics;
import com.laimory.server.geo.GeoPlace;
import com.laimory.server.geo.GeocodingService;
import com.laimory.server.geo.MapPlaceLookupException;
import com.laimory.server.timeline.HealthMetric;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.payload.HealthPayload;
import com.laimory.server.timeline.payload.MovementEndpoint;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.payload.StayPayload;
import com.laimory.server.timeline.photo.PhotoUrlService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 저장 전 payload 재구성(지오코딩·photoUrl 주입·클라 파생값 무시·durationText 계산·좌표 수집/dedupe)과
 * 부분 실패 정책 적용(cap·D1/D2 판정·D5 fallback·D7 오류 매핑·batch metric) 검증.
 * 지오코딩은 {@link GeocodingService#lookupAll}로 좌표를 한 번에 넘기므로, 수집 범위(STAY·MOVEMENT만)와
 * encounter order·dedupe를 lookupAll에 전달된 Set으로 단언한다.
 */
@ExtendWith(MockitoExtension.class)
class SourceItemEnrichmentServiceTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 6, 17, 9, 0);
    private static final long USER_ID = 0L;
    private static final int MAX_UNIQUE_COORDINATES = 30;

    @Mock
    private GeocodingService geocodingService;
    @Mock
    private PhotoUrlService photoUrlService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private SourceItemEnrichmentService service() {
        return new SourceItemEnrichmentService(
                geocodingService, photoUrlService, new GeoMetrics(meterRegistry), MAX_UNIQUE_COORDINATES);
    }

    /** 요청된 좌표 전부에 성공 outcome을 채워 반환하도록 lookupAll을 스텁한다(명시 안 된 좌표는 EMPTY). */
    private void stubLookupAll(Map<Coordinate, GeoPlace> results) {
        stubOutcomes(results, Map.of());
    }

    /** 성공·실패 outcome을 좌표별로 지정해 lookupAll을 스텁한다. */
    private void stubOutcomes(Map<Coordinate, GeoPlace> successes, Map<Coordinate, MapPlaceLookupException> failures) {
        when(geocodingService.lookupAll(anySet())).thenAnswer(invocation -> {
            Set<Coordinate> coordinates = invocation.getArgument(0);
            Map<Coordinate, GeoLookupOutcome> outcomes = new HashMap<>();
            for (Coordinate coordinate : coordinates) {
                if (failures.containsKey(coordinate)) {
                    outcomes.put(coordinate, new GeoLookupOutcome.Failure(failures.get(coordinate)));
                } else {
                    outcomes.put(coordinate, new GeoLookupOutcome.Success(
                            successes.getOrDefault(coordinate, GeoPlace.EMPTY)));
                }
            }
            return outcomes;
        });
    }

    /** 서로 다른 좌표의 STAY item을 {@code count}개 만든다(시간순 1분 간격). */
    private static List<SourceItemDto> distinctStays(int count) {
        List<SourceItemDto> sources = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sources.add(new SourceItemDto(ItemType.STAY, "raw-" + i, T.plusMinutes(i), null,
                    new StayPayload(37.0 + i * 0.01, 127.0 + i * 0.01, null, null, null)));
        }
        return sources;
    }

    private static Coordinate stayCoordinate(int index) {
        return new Coordinate(37.0 + index * 0.01, 127.0 + index * 0.01);
    }

    private double batchCount(String outcome, String failureKind) {
        var timer = meterRegistry.find("laimory.geo.batch")
                .tag("outcome", outcome).tag("failure_kind", failureKind).timer();
        return timer == null ? 0 : timer.count();
    }

    // ── 재구성 회귀 ──

    @Test
    void enrich_fillsStayAndMovementEndpoints_ignoringClientDerivedFields() {
        stubLookupAll(Map.of(
                new Coordinate(37.5340, 126.9668),
                new GeoPlace("서울 용산구 청파로20길 95", List.of("서울드래곤시티", "그랑씨엘"))));
        // 클라가 서버 파생 필드(address/places/durationText)를 위조해 보내도 서버 값으로만 재구성된다.
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.STAY, "raw-loc", T, T.plusMinutes(105),
                        new StayPayload(37.5340, 126.9668, "위조 주소", List.of("위조 장소"), "999시간")),
                new SourceItemDto(ItemType.MOVEMENT, "raw-mov", T, null,
                        new MovementPayload(
                                new MovementEndpoint(37.4979, 127.0276, "위조 주소", List.of("위조 장소")),
                                new MovementEndpoint(37.5340, 126.9668, null, null),
                                "IN_VEHICLE", 5200.0)));

        List<SourceItemDto> enriched = service().enrich(sources, USER_ID);

        // 재구성(new SourceItemDto)돼도 envelope 필드(rawId)는 원본 그대로 보존돼야 한다 — 유실 시 DB NOT NULL 500.
        assertThat(enriched.get(0).rawId()).isEqualTo("raw-loc");
        assertThat(enriched.get(1).rawId()).isEqualTo("raw-mov");

        StayPayload stay = (StayPayload) enriched.get(0).payload();
        assertThat(stay.address()).isEqualTo("서울 용산구 청파로20길 95");
        assertThat(stay.places()).containsExactly("서울드래곤시티", "그랑씨엘");
        // durationText는 클라 위조값이 아니라 startAt/endAt(105분) 계산값.
        assertThat(stay.durationText()).isEqualTo("1시간45분");

        MovementPayload movement = (MovementPayload) enriched.get(1).payload();
        // 조회 결과가 빈 좌표는 enrich 필드 null(클라 위조값 fallback 없음).
        assertThat(movement.start().address()).isNull();
        assertThat(movement.start().places()).isNull();
        assertThat(movement.end().address()).isEqualTo("서울 용산구 청파로20길 95");
        assertThat(movement.end().places()).containsExactly("서울드래곤시티", "그랑씨엘");
        assertThat(movement.transports()).isEqualTo("IN_VEHICLE");
        assertThat(movement.distanceMeters()).isEqualTo(5200.0);
        // 전 좌표 성공 batch — success/none으로 1회 계수.
        assertThat(batchCount("success", "none")).isEqualTo(1);
    }

    @Test
    void enrich_injectsPhotoUrl_ignoringClientValue_withoutGeocoding() {
        String filename = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg";
        when(photoUrlService.buildUrl(filename, USER_ID)).thenReturn("https://cdn.example/abc/photos/" + filename);
        // 클라가 photoUrl을 위조해 보내도 서버 파생값으로만 덮어쓴다. 나머지 필드·envelope은 보존.
        SourceItemDto photo = new SourceItemDto(ItemType.PHOTO, "raw-photo", T, null,
                new PhotoPayload(filename, "content://x", 1.0, 2.0, "설명", "https://evil.example/fake.jpg"));

        List<SourceItemDto> enriched = service().enrich(List.of(photo), USER_ID);

        assertThat(enriched.get(0).itemType()).isEqualTo(ItemType.PHOTO);
        assertThat(enriched.get(0).rawId()).isEqualTo("raw-photo");
        assertThat(enriched.get(0).startAt()).isEqualTo(T);
        assertThat(enriched.get(0).endAt()).isNull();
        PhotoPayload reconstructed = (PhotoPayload) enriched.get(0).payload();
        assertThat(reconstructed.photoUrl()).isEqualTo("https://cdn.example/abc/photos/" + filename);
        assertThat(reconstructed.filename()).isEqualTo(filename);
        assertThat(reconstructed.clientPhotoUri()).isEqualTo("content://x");
        assertThat(reconstructed.latitude()).isEqualTo(1.0);
        assertThat(reconstructed.longitude()).isEqualTo(2.0);
        assertThat(reconstructed.description()).isEqualTo("설명");
        // PHOTO는 latitude/longitude 필드가 있어도 지오코딩 비대상 — 좌표가 수집되지 않아 lookupAll 자체를 생략한다.
        verifyNoInteractions(geocodingService);
    }

    @Test
    void enrich_collectsUniqueCoordinates_inEncounterOrder_forSingleLookupAll() {
        stubLookupAll(Map.of());
        // STAY 좌표 == MOVEMENT 도착 좌표 → 좌표당 정상 2콜인 외부 호출이므로 같은 좌표는 1회만 조회해야 한다.
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.STAY, "r1", T, null,
                        new StayPayload(37.5340, 126.9668, null, null, null)),
                new SourceItemDto(ItemType.MOVEMENT, "r2", T, null,
                        new MovementPayload(
                                new MovementEndpoint(37.4979, 127.0276, null, null),
                                new MovementEndpoint(37.5340, 126.9668, null, null),
                                null, null)));

        service().enrich(sources, USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<Coordinate>> captor = ArgumentCaptor.forClass(Set.class);
        verify(geocodingService).lookupAll(captor.capture());
        // dedupe(중복 좌표 1회) + LinkedHashSet의 source encounter order 보존(병렬 구독 시작 순서의 결정성).
        assertThat(captor.getValue()).containsExactly(
                new Coordinate(37.5340, 126.9668), new Coordinate(37.4979, 127.0276));
    }

    @Test
    void enrich_omitsDurationText_whenEndAtMissing() {
        stubLookupAll(Map.of());
        List<SourceItemDto> sources = List.of(new SourceItemDto(ItemType.STAY, "r1", T, null,
                new StayPayload(37.5340, 126.9668, null, null, null)));

        StayPayload stay = (StayPayload) service().enrich(sources, USER_ID).get(0).payload();

        // endAt이 없으면 머문 시간을 계산할 수 없어 null(NON_NULL 직렬화로 키 생략).
        assertThat(stay.durationText()).isNull();
    }

    @Test
    void enrich_formatsDurationText_hoursOnlyAndMinutesOnly() {
        stubLookupAll(Map.of());
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.STAY, "r1", T, T.plusHours(2),
                        new StayPayload(37.5340, 126.9668, null, null, null)),
                new SourceItemDto(ItemType.STAY, "r2", T, T.plusMinutes(45),
                        new StayPayload(37.5445, 127.0557, null, null, null)));

        List<SourceItemDto> enriched = service().enrich(sources, USER_ID);

        assertThat(((StayPayload) enriched.get(0).payload()).durationText()).isEqualTo("2시간");
        assertThat(((StayPayload) enriched.get(1).payload()).durationText()).isEqualTo("45분");
    }

    @Test
    void enrich_passesThroughNonEnrichedTypes_unchanged_withoutGeocoding() {
        // 서버 파생 필드가 없는 타입(HEALTH 등)은 재구성 없이 동일 인스턴스로 통과하고, 좌표가 없으니 lookupAll도 생략.
        SourceItemDto health = new SourceItemDto(ItemType.HEALTH, "raw-h", T, T.plusHours(1),
                new HealthPayload(HealthMetric.STEPS, "10145보"));

        List<SourceItemDto> enriched = service().enrich(List.of(health), USER_ID);

        assertThat(enriched.get(0)).isSameAs(health);
        verifyNoInteractions(geocodingService);
    }

    // ── T3/D5: 허용 partial — 성공 보존, 실패 좌표만 address=null·places=[] fallback ──

    @Test
    void enrich_keepsSuccesses_andFallsBackFailedCoordinate_whenExactlyTwentyPercentFailed() {
        // (F,U)=(1,5) — 정확히 20%는 허용(D1). 실패 좌표만 D5 fallback(address=null, places=[])이고
        // 성공 좌표의 실제 값은 보존된다. NON_NULL 직렬화로 실제 JSON은 address key 생략·places=[]가 된다.
        List<SourceItemDto> sources = distinctStays(5);
        Map<Coordinate, GeoPlace> successes = new HashMap<>();
        for (int i = 1; i < 5; i++) {
            successes.put(stayCoordinate(i), new GeoPlace("주소" + i, List.of("장소" + i)));
        }
        stubOutcomes(successes, Map.of(
                stayCoordinate(0), MapPlaceLookupException.remoteTransient("coord2address http 500", null)));

        List<SourceItemDto> enriched = service().enrich(sources, USER_ID);

        StayPayload failed = (StayPayload) enriched.get(0).payload();
        assertThat(failed.address()).isNull();
        assertThat(failed.places()).isEmpty();
        JsonNode stagingPayload = new ObjectMapper().valueToTree(failed);
        assertThat(stagingPayload.has("address")).isFalse();
        assertThat(stagingPayload.path("places").isArray()).isTrue();
        assertThat(stagingPayload.path("places").size()).isZero();
        // 실패 좌표도 위경도·rawId·시간은 유지된다.
        assertThat(failed.latitude()).isEqualTo(stayCoordinate(0).latitude());
        assertThat(enriched.get(0).rawId()).isEqualTo("raw-0");
        assertThat(enriched.get(0).startAt()).isEqualTo(T);
        for (int i = 1; i < 5; i++) {
            StayPayload ok = (StayPayload) enriched.get(i).payload();
            assertThat(ok.address()).isEqualTo("주소" + i);
            assertThat(ok.places()).containsExactly("장소" + i);
        }
        assertThat(batchCount("partial", "transient")).isEqualTo(1);
    }

    // ── T14/D1/D7: 비율 초과 거절 — 저장 없는 502, transient만이면 -1014 ──

    @Test
    void enrich_throws1014_whenTransientFailureRatioExceedsTwentyPercent() {
        List<SourceItemDto> sources = distinctStays(4);
        stubOutcomes(Map.of(), Map.of(
                stayCoordinate(1), MapPlaceLookupException.remoteTransient("coord2address http 500", null)));

        // (F,U)=(1,4) → 25% 초과 거절. materialize된 실패가 전부 transient라 -1014.
        assertThatThrownBy(() -> service().enrich(sources, USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1014));
        assertThat(batchCount("rejected", "transient")).isEqualTo(1);
    }

    @Test
    void enrich_throws1015_whenAggregateContainsPermanentFailure() {
        List<SourceItemDto> sources = distinctStays(4);
        stubOutcomes(Map.of(), Map.of(
                stayCoordinate(0), MapPlaceLookupException.remoteTransient("coord2address http 500", null),
                stayCoordinate(2), MapPlaceLookupException.remotePermanent("keyword http 429", null)));

        // 혼합 aggregate에 영구가 하나라도 있으면 -1015(D7).
        assertThatThrownBy(() -> service().enrich(sources, USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1015));
        assertThat(batchCount("rejected", "mixed")).isEqualTo(1);
    }

    // ── T9/D2/D3: 같은 실패 좌표의 시간순 반복 — HTTP 1회, observation 3회로 연속 거절 ──

    @Test
    void enrich_rejectsRepeatedFailingCoordinate_asThreeConsecutiveObservations_withSingleLookup() {
        // 같은 실패 좌표가 서로 다른 시점 STAY 3개에 반복 + 성공 좌표 12개: unique (F,U)=(1,13)라 D1은
        // 허용이지만 시간순 연속 실패 3개(D2)로 거절. HTTP 조회는 unique 1회다.
        Coordinate failing = new Coordinate(37.9, 127.9);
        List<SourceItemDto> sources = new ArrayList<>(distinctStays(12));
        for (int repeat = 0; repeat < 3; repeat++) {
            sources.add(new SourceItemDto(ItemType.STAY, "raw-repeat-" + repeat,
                    T.plusHours(2).plusMinutes(repeat), null,
                    new StayPayload(failing.latitude(), failing.longitude(), null, null, null)));
        }
        stubOutcomes(Map.of(), Map.of(
                failing, MapPlaceLookupException.remoteTransient("coord2address http 500", null)));

        assertThatThrownBy(() -> service().enrich(sources, USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1014));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<Coordinate>> captor = ArgumentCaptor.forClass(Set.class);
        verify(geocodingService).lookupAll(captor.capture());
        // 반복 좌표는 HTTP unique set에 1회만 들어간다(12 + 1).
        assertThat(captor.getValue()).hasSize(13);
    }

    // ── T16/D9/D17: unique coordinate cap — 초과는 외부 호출 전 400 경로 ──

    @Test
    void enrich_rejectsCapExceeded_beforeAnyLookup() {
        // U=31 > 30 → 외부 I/O 전 IllegalArgumentException(기존 validation 400/-400 경로), provider 미구독.
        List<SourceItemDto> sources = distinctStays(31);

        assertThatThrownBy(() -> service().enrich(sources, USER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique geo coordinates");
        verifyNoInteractions(geocodingService);
    }

    @Test
    void enrich_allowsExactlyCapUniqueCoordinates() {
        // U=30 경계는 허용. 중복·비대상(PHOTO 등)은 U 계산에 들어가지 않는다는 것은 dedupe 테스트가 고정한다.
        stubLookupAll(Map.of());
        List<SourceItemDto> sources = distinctStays(30);

        List<SourceItemDto> enriched = service().enrich(sources, USER_ID);

        assertThat(enriched).hasSize(30);
    }

    @Test
    void enrich_capCountsUniqueCoordinates_notSourceItems() {
        // sourceItems 31개여도 전부 같은 좌표(U=1)면 cap을 넘지 않는다 — 배열 길이가 아니라 파생 U 기준.
        stubLookupAll(Map.of());
        List<SourceItemDto> sources = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            sources.add(new SourceItemDto(ItemType.STAY, "raw-" + i, T.plusMinutes(i), null,
                    new StayPayload(37.5340, 126.9668, null, null, null)));
        }

        assertThat(service().enrich(sources, USER_ID)).hasSize(31);
    }

    // ── D4: programming error — 502로 가리지 않고 그대로 전파 ──

    @Test
    void enrich_propagatesNonMapLookupRuntimeException_asIs() {
        // enrichment/provider 버그는 502로 가리지 않고 그대로 전파해 catch-all 500이 되게 한다.
        when(geocodingService.lookupAll(anySet())).thenThrow(new IllegalStateException("enrichment bug"));
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.STAY, "r1", T, null,
                        new StayPayload(37.5340, 126.9668, null, null, null)));

        assertThatThrownBy(() -> service().enrich(sources, USER_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThat(batchCount("bug", "none")).isEqualTo(1);
    }

    @Test
    void enrich_rejectsMissingOutcomeKey_asProgrammingError() {
        // D6: lookupAll이 정상 반환했다면 모든 unique 좌표 key가 있어야 한다. 빈 Mono/수집 버그로 key가
        // 빠진 map을 성공으로 간주하면 시간순 판정과 reconstruct가 왜곡되므로 500 경로로 드러낸다.
        when(geocodingService.lookupAll(anySet())).thenReturn(Map.of());
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.STAY, "r1", T, null,
                        new StayPayload(37.5340, 126.9668, null, null, null)));

        assertThatThrownBy(() -> service().enrich(sources, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outcomes");
        assertThat(batchCount("bug", "none")).isEqualTo(1);
    }

    // ── 생성자 자기검증 ──

    @Test
    void constructor_failsFast_whenCapBelowOne() {
        assertThatThrownBy(() -> new SourceItemEnrichmentService(
                geocodingService, photoUrlService, new GeoMetrics(meterRegistry), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-unique-coordinates");
    }
}
