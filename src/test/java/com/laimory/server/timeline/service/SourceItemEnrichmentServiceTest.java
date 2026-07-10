package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
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
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 저장 전 payload 재구성(지오코딩·photoUrl 주입·클라 파생값 무시·durationText 계산·좌표 dedupe·실패 강등) 검증. */
@ExtendWith(MockitoExtension.class)
class SourceItemEnrichmentServiceTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 6, 17, 9, 0);
    private static final long USER_ID = 0L;

    @Mock
    private GeocodingService geocodingService;
    @Mock
    private PhotoUrlService photoUrlService;

    private SourceItemEnrichmentService service() {
        return new SourceItemEnrichmentService(geocodingService, photoUrlService);
    }

    @Test
    void enrich_fillsStayAndMovementEndpoints_ignoringClientDerivedFields() {
        when(geocodingService.lookup(37.5340, 126.9668))
                .thenReturn(new GeoPlace("서울 용산구 청파로20길 95", List.of("서울드래곤시티", "그랑씨엘")));
        when(geocodingService.lookup(37.4979, 127.0276)).thenReturn(GeoPlace.EMPTY);
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
    }

    @Test
    void enrich_injectsPhotoUrl_ignoringClientValue_preservingOtherFields() {
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
    }

    @Test
    void enrich_lookupsEachCoordinateOnce_acrossItems() {
        when(geocodingService.lookup(anyDouble(), anyDouble())).thenReturn(GeoPlace.EMPTY);
        // STAY 좌표 == MOVEMENT 도착 좌표 → 좌표당 6콜이므로 같은 좌표는 1회만 조회해야 한다.
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.STAY, "r1", T, null,
                        new StayPayload(37.5340, 126.9668, null, null, null)),
                new SourceItemDto(ItemType.MOVEMENT, "r2", T, null,
                        new MovementPayload(
                                new MovementEndpoint(37.4979, 127.0276, null, null),
                                new MovementEndpoint(37.5340, 126.9668, null, null),
                                null, null)));

        service().enrich(sources, USER_ID);

        verify(geocodingService, times(2)).lookup(anyDouble(), anyDouble());
        verify(geocodingService).lookup(37.5340, 126.9668);
        verify(geocodingService).lookup(37.4979, 127.0276);
    }

    @Test
    void enrich_omitsDurationText_whenEndAtMissing() {
        when(geocodingService.lookup(anyDouble(), anyDouble())).thenReturn(GeoPlace.EMPTY);
        List<SourceItemDto> sources = List.of(new SourceItemDto(ItemType.STAY, "r1", T, null,
                new StayPayload(37.5340, 126.9668, null, null, null)));

        StayPayload stay = (StayPayload) service().enrich(sources, USER_ID).get(0).payload();

        // endAt이 없으면 머문 시간을 계산할 수 없어 null(NON_NULL 직렬화로 키 생략).
        assertThat(stay.durationText()).isNull();
    }

    @Test
    void enrich_formatsDurationText_hoursOnlyAndMinutesOnly() {
        when(geocodingService.lookup(anyDouble(), anyDouble())).thenReturn(GeoPlace.EMPTY);
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
    void enrich_throwsBusinessException1014_whenLookupFailsRetryable_andShortCircuits() {
        // loud fail A: 전이적 실패(retryable=true — 5xx·타임아웃 재시도 소진)는 ERROR_1014(재시도 가능)로 매핑한다.
        when(geocodingService.lookup(37.5340, 126.9668))
                .thenThrow(new MapPlaceLookupException("coord2address http 500", true, null));
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.STAY, "r1", T, null,
                        new StayPayload(37.5340, 126.9668, null, null, null)),
                new SourceItemDto(ItemType.STAY, "r2", T, null,
                        new StayPayload(37.5445, 127.0557, null, null, null)));

        assertThatThrownBy(() -> service().enrich(sources, USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1014));
        // 첫 좌표가 실패하면 stream이 short-circuit돼 이후 좌표는 조회하지 않는다(낭비 호출 방지).
        verify(geocodingService, never()).lookup(37.5445, 127.0557);
    }

    @Test
    void enrich_throwsBusinessException1015_whenLookupFailsNonRetryable() {
        // 영구적 실패(retryable=false — 429 쿼터·401/403 키·파싱/shape)는 ERROR_1015로 매핑한다(클라 재시도 UX 분기용).
        when(geocodingService.lookup(37.5340, 126.9668))
                .thenThrow(new MapPlaceLookupException("coord2address http 401", false, null));
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.STAY, "r1", T, null,
                        new StayPayload(37.5340, 126.9668, null, null, null)));

        assertThatThrownBy(() -> service().enrich(sources, USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1015));
    }

    @Test
    void enrich_propagatesNonMapLookupRuntimeException_asIs() {
        // enrichment 자체 버그(NPE 등)는 502로 가리지 않고 그대로 전파해 catch-all 500이 되게 한다 — broad RuntimeException catch 금지.
        when(geocodingService.lookup(37.5340, 126.9668)).thenThrow(new IllegalStateException("enrichment bug"));
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.STAY, "r1", T, null,
                        new StayPayload(37.5340, 126.9668, null, null, null)));

        assertThatThrownBy(() -> service().enrich(sources, USER_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enrich_passesThroughNonEnrichedTypes_unchanged() {
        // 서버 파생 필드가 없는 타입(HEALTH 등)은 재구성 없이 동일 인스턴스로 통과한다.
        SourceItemDto health = new SourceItemDto(ItemType.HEALTH, "raw-h", T, T.plusHours(1),
                new HealthPayload(HealthMetric.STEPS, "10145보"));

        List<SourceItemDto> enriched = service().enrich(List.of(health), USER_ID);

        assertThat(enriched.get(0)).isSameAs(health);
    }
}
