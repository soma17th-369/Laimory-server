package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.geo.Coordinate;
import com.laimory.server.geo.GeoPlace;
import com.laimory.server.geo.GeocodingService;
import com.laimory.server.geo.MapPlaceLookupException;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.payload.MovementEndpoint;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.payload.StayPayload;
import com.laimory.server.timeline.payload.TimelineItemPayload;
import com.laimory.server.timeline.photo.PhotoUrlService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 저장 전 payload 재구성: 서버 파생 필드는 클라 값을 무시하고 서버 값으로만 채운다
 * (mass assignment 방어 — 거절이 아니라 무시). STAY/MOVEMENT의 {@code address}/{@code places}는
 * 지오코딩 결과, {@code durationText}는 startAt/endAt 계산값, PHOTO의 {@code photoUrl}은 filename+userId로
 * 파생한 무서명 CloudFront 서빙 URL이다(AI가 DB payload에서 HTTP GET으로 소비).
 * 저장은 payload 통짜 직렬화라 이 재구성본이 곧 저장본이다.
 *
 * <p><b>2-pass 구조</b>: ① 지오코딩 대상 좌표(STAY 좌표·MOVEMENT start/end만 — PHOTO는 latitude/longitude
 * 필드가 있어도 지오코딩 비대상)를 {@link LinkedHashSet}으로 dedupe 수집하고 ② 한 번에 병렬 조회
 * ({@link GeocodingService#lookupAll}) 후 ③ 완성된 map으로 재구성한다. 좌표가 없으면(PHOTO/HEALTH만)
 * lookupAll 자체를 생략한다. source 결과 순서와 envelope 필드는 그대로 보존한다.
 *
 * <p>지오코딩이 끝내 실패하면(재시도 provider 내부 소진) 해당 draft 생성을 502(전이=-1014 / 영구=-1015)로 실패시킨다 —
 * 저품질 타임라인을 굽지 않는다(좌표만 있고 주소·장소가 없으면 AI가 장소를 알 수 없다). 하나라도 실패하면
 * enrich 전체가 throw한다. 병렬 조회라 1014/1015는 배치 종합 판정이 아니라 <b>가장 먼저 관측된 실패</b>의
 * 분류다(전이·영구가 경쟁하면 실행마다 다를 수 있음 — 둘 다 502라 수용). 같은 좌표는 요청 내 1회만 조회한다
 * (좌표당 카카오 정상 2콜인 외부 호출이라 dedupe 필수). 좌표는 검증 경계(requireValidSourceItems)가 필수를
 * 보장한 뒤라 null 케이스가 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceItemEnrichmentService {

    private final GeocodingService geocodingService;
    private final PhotoUrlService photoUrlService;

    /** {@code userId}는 PHOTO photoUrl의 full key 파생에 쓴다 — 저장될 row의 user_id와 같은 사용자여야 한다. */
    public List<SourceItemDto> enrich(List<SourceItemDto> sourceItems, long userId) {
        long startNanos = System.nanoTime();
        Map<Coordinate, GeoPlace> lookups = lookupCoordinates(sourceItems);
        List<SourceItemDto> enriched = sourceItems.stream()
                .map(src -> reconstruct(src, lookups, userId))
                .toList();
        if (!lookups.isEmpty()) {
            // 좌표값은 로그 금지(위치 민감정보) — 유니크 좌표 수·총 소요시간만.
            // 좌표당 호출 수·개별 소요시간은 provider가 lookup 단위로 남긴다.
            log.info("geocoding enrich: items={} coordinates={} totalMs={}",
                    sourceItems.size(), lookups.size(), (System.nanoTime() - startNanos) / 1_000_000);
        }
        return enriched;
    }

    /**
     * 지오코딩 대상 좌표만 수집해 한 번에 병렬 조회한다. 수집 대상은 STAY 좌표와 MOVEMENT start/end뿐 —
     * PHOTO는 좌표 필드가 있어도 비대상(현행 계약 보존), 그 외 타입도 미수집. {@link LinkedHashSet}이
     * source encounter order를 보존해 병렬 구독 시작 순서를 결정적으로 유지한다(완료 순서는 비결정).
     */
    private Map<Coordinate, GeoPlace> lookupCoordinates(List<SourceItemDto> sourceItems) {
        Set<Coordinate> coordinates = new LinkedHashSet<>();
        for (SourceItemDto src : sourceItems) {
            switch (src.payload()) {
                case StayPayload stay -> coordinates.add(new Coordinate(stay.latitude(), stay.longitude()));
                case MovementPayload movement -> {
                    coordinates.add(new Coordinate(movement.start().latitude(), movement.start().longitude()));
                    coordinates.add(new Coordinate(movement.end().latitude(), movement.end().longitude()));
                }
                default -> {
                }
            }
        }
        if (coordinates.isEmpty()) {
            return Map.of();
        }
        try {
            return geocodingService.lookupAll(coordinates);
        } catch (MapPlaceLookupException e) {
            // 지오코딩이 끝내 실패하면(재시도 provider 내부 소진) 저품질 타임라인을 굽지 않고 draft 생성을 502로 loud fail한다.
            // 재시도 가능성에 따라 코드를 분리해 클라가 재시도 UX를 분기한다(전이=1014 재시도 가능, 영구=1015 즉시 재시도 무의미).
            // enrich가 taskId 생성·저장 前이라 아무것도 안 만들어져 롤백 불필요. 원인 상세는 provider가 이미 로깅했다(좌표는 로그 금지).
            // broad RuntimeException은 잡지 않는다 — enrichment 자체 버그(NPE 등)는 catch-all 500이 맞고 502로 가리면 안 된다.
            throw new BusinessException(e.isRetryable()
                    ? ExceptionType.GEOCODING_TRANSIENT_FAILURE : ExceptionType.GEOCODING_PERMANENT_FAILURE);
        }
    }

    private SourceItemDto reconstruct(SourceItemDto src, Map<Coordinate, GeoPlace> lookups, long userId) {
        TimelineItemPayload reconstructed = switch (src.payload()) {
            case StayPayload stay -> {
                GeoPlace geo = lookups.get(new Coordinate(stay.latitude(), stay.longitude()));
                yield new StayPayload(
                        stay.latitude(), stay.longitude(),
                        geo.address(), geo.places(), durationText(src.startAt(), src.endAt()));
            }
            case MovementPayload movement -> new MovementPayload(
                    reconstructEndpoint(movement.start(), lookups),
                    reconstructEndpoint(movement.end(), lookups),
                    movement.transports(), movement.distanceMeters());
            case PhotoPayload photo -> new PhotoPayload(
                    photo.filename(), photo.clientPhotoUri(), photo.latitude(), photo.longitude(),
                    photo.description(),
                    photoUrlService.buildUrl(photo.filename(), userId));
            default -> src.payload();
        };
        if (reconstructed == src.payload()) {
            return src;
        }
        // rawId 등 envelope 필드는 그대로 보존 — 여기서 떨어지면 검증(이미 통과)이 못 잡고 DB NOT NULL에서 500이 난다.
        return new SourceItemDto(src.itemType(), src.rawId(), src.startAt(), src.endAt(), reconstructed);
    }

    private MovementEndpoint reconstructEndpoint(MovementEndpoint endpoint, Map<Coordinate, GeoPlace> lookups) {
        // 수집(lookupCoordinates)과 같은 규칙으로 키를 만들므로 map에 항상 존재한다.
        GeoPlace geo = lookups.get(new Coordinate(endpoint.latitude(), endpoint.longitude()));
        return new MovementEndpoint(
                endpoint.latitude(), endpoint.longitude(), geo.address(), geo.places());
    }

    /** STAY 머문 시간 텍스트("1시간45분"). 서버 파생값 — startAt/endAt로 계산하고 계산 불가(endAt 없음 등)면 null. */
    private static String durationText(LocalDateTime startAt, LocalDateTime endAt) {
        if (startAt == null || endAt == null || endAt.isBefore(startAt)) {
            return null;
        }
        long totalMinutes = Duration.between(startAt, endAt).toMinutes();
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0 && minutes > 0) {
            return hours + "시간" + minutes + "분";
        }
        if (hours > 0) {
            return hours + "시간";
        }
        return minutes + "분";
    }
}
