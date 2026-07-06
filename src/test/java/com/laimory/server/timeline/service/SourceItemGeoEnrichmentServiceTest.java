package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.geo.GeoPlace;
import com.laimory.server.geo.GeocodingService;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.payload.LocationPayload;
import com.laimory.server.timeline.payload.MovementEndpoint;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 저장 전 payload 재구성(enrich 주입·클라 파생값 무시·durationText 계산·좌표 dedupe·실패 강등) 검증. */
@ExtendWith(MockitoExtension.class)
class SourceItemGeoEnrichmentServiceTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 6, 17, 9, 0);

    @Mock
    private GeocodingService geocodingService;

    private SourceItemGeoEnrichmentService service() {
        return new SourceItemGeoEnrichmentService(geocodingService);
    }

    @Test
    void enrich_fillsLocationAndMovementEndpoints_ignoringClientDerivedFields() {
        when(geocodingService.lookup(37.5340, 126.9668))
                .thenReturn(new GeoPlace("서울 용산구 청파로20길 95", List.of("서울드래곤시티", "그랑씨엘")));
        when(geocodingService.lookup(37.4979, 127.0276)).thenReturn(GeoPlace.EMPTY);
        // 클라가 서버 파생 필드(address/places/durationText)를 위조해 보내도 서버 값으로만 재구성된다.
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.LOCATION, T, T.plusMinutes(105),
                        new LocationPayload(37.5340, 126.9668, "위조 주소", List.of("위조 장소"), "999시간")),
                new SourceItemDto(ItemType.MOVEMENT, T, null,
                        new MovementPayload(
                                new MovementEndpoint(37.4979, 127.0276, "위조 주소", List.of("위조 장소")),
                                new MovementEndpoint(37.5340, 126.9668, null, null),
                                "IN_VEHICLE", 5200.0)));

        List<SourceItemDto> enriched = service().enrich(sources);

        LocationPayload location = (LocationPayload) enriched.get(0).payload();
        assertThat(location.address()).isEqualTo("서울 용산구 청파로20길 95");
        assertThat(location.places()).containsExactly("서울드래곤시티", "그랑씨엘");
        // durationText는 클라 위조값이 아니라 startAt/endAt(105분) 계산값.
        assertThat(location.durationText()).isEqualTo("1시간45분");

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
    void enrich_lookupsEachCoordinateOnce_acrossItems() {
        when(geocodingService.lookup(anyDouble(), anyDouble())).thenReturn(GeoPlace.EMPTY);
        // LOCATION 좌표 == MOVEMENT 도착 좌표 → 좌표당 6콜이므로 같은 좌표는 1회만 조회해야 한다.
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.LOCATION, T, null,
                        new LocationPayload(37.5340, 126.9668, null, null, null)),
                new SourceItemDto(ItemType.MOVEMENT, T, null,
                        new MovementPayload(
                                new MovementEndpoint(37.4979, 127.0276, null, null),
                                new MovementEndpoint(37.5340, 126.9668, null, null),
                                null, null)));

        service().enrich(sources);

        verify(geocodingService, times(2)).lookup(anyDouble(), anyDouble());
        verify(geocodingService).lookup(37.5340, 126.9668);
        verify(geocodingService).lookup(37.4979, 127.0276);
    }

    @Test
    void enrich_omitsDurationText_whenEndAtMissing() {
        when(geocodingService.lookup(anyDouble(), anyDouble())).thenReturn(GeoPlace.EMPTY);
        List<SourceItemDto> sources = List.of(new SourceItemDto(ItemType.LOCATION, T, null,
                new LocationPayload(37.5340, 126.9668, null, null, null)));

        LocationPayload location = (LocationPayload) service().enrich(sources).get(0).payload();

        // endAt이 없으면 머문 시간을 계산할 수 없어 null(NON_NULL 직렬화로 키 생략).
        assertThat(location.durationText()).isNull();
    }

    @Test
    void enrich_formatsDurationText_hoursOnlyAndMinutesOnly() {
        when(geocodingService.lookup(anyDouble(), anyDouble())).thenReturn(GeoPlace.EMPTY);
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.LOCATION, T, T.plusHours(2),
                        new LocationPayload(37.5340, 126.9668, null, null, null)),
                new SourceItemDto(ItemType.LOCATION, T, T.plusMinutes(45),
                        new LocationPayload(37.5445, 127.0557, null, null, null)));

        List<SourceItemDto> enriched = service().enrich(sources);

        assertThat(((LocationPayload) enriched.get(0).payload()).durationText()).isEqualTo("2시간");
        assertThat(((LocationPayload) enriched.get(1).payload()).durationText()).isEqualTo("45분");
    }

    @Test
    void enrich_degradesToNullFields_whenLookupThrows() {
        when(geocodingService.lookup(anyDouble(), anyDouble())).thenReturn(GeoPlace.EMPTY);
        when(geocodingService.lookup(37.5340, 126.9668)).thenThrow(new RuntimeException("kakao down"));
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.LOCATION, T, null,
                        new LocationPayload(37.5340, 126.9668, null, null, null)),
                new SourceItemDto(ItemType.LOCATION, T, null,
                        new LocationPayload(37.5445, 127.0557, null, null, null)));

        // 지오코딩 실패는 draft 생성을 죽이지 않는다 — 해당 좌표만 null 강등, 예외 미전파.
        List<SourceItemDto> enriched = service().enrich(sources);

        LocationPayload failed = (LocationPayload) enriched.get(0).payload();
        assertThat(failed.address()).isNull();
        assertThat(failed.places()).isNull();
        assertThat(failed.latitude()).isEqualTo(37.5340);
        assertThat(enriched.get(1).payload()).isInstanceOf(LocationPayload.class);
    }

    @Test
    void enrich_passesThroughNonGeoTypes_unchanged() {
        SourceItemDto photo = new SourceItemDto(ItemType.PHOTO, T, null,
                new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://x", 1.0, 2.0, null));

        List<SourceItemDto> enriched = service().enrich(List.of(photo));

        // 지오코딩 대상이 아닌 타입은 좌표가 있어도(PHOTO lat/lng) 건드리지 않는다 — 이번 Epic 범위 밖.
        assertThat(enriched.get(0)).isSameAs(photo);
    }
}
