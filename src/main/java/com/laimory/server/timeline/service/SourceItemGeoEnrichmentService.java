package com.laimory.server.timeline.service;

import com.laimory.server.geo.GeoPlace;
import com.laimory.server.geo.GeocodingService;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.payload.LocationPayload;
import com.laimory.server.timeline.payload.MovementEndpoint;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.TimelineItemPayload;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 저장 전 LOCATION/MOVEMENT payload 재구성: 서버 파생 필드는 클라 값을 무시하고 서버 값으로만 채운다
 * (mass assignment 방어 — 거절이 아니라 무시). {@code address}/{@code places}는 지오코딩 결과,
 * {@code durationText}는 startAt/endAt 계산값이다. 저장은 payload 통짜 직렬화라 이 재구성본이 곧 저장본이다.
 *
 * <p>지오코딩 실패는 해당 좌표의 enrich 필드만 null로 강등하고 계속한다 — 외부 API가 draft 생성을
 * 죽이지 않는다. 같은 좌표는 요청 내 1회만 조회한다(좌표당 카카오 6콜이라 dedupe 필수).
 * 좌표는 검증 경계(requireValidSourceItems)가 필수를 보장한 뒤라 null 케이스가 없다.
 */
@Service
@RequiredArgsConstructor
public class SourceItemGeoEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(SourceItemGeoEnrichmentService.class);

    private final GeocodingService geocodingService;

    public List<SourceItemDto> enrich(List<SourceItemDto> sourceItems) {
        long startNanos = System.nanoTime();
        Map<Coordinate, GeoPlace> lookups = new HashMap<>();
        List<SourceItemDto> enriched = sourceItems.stream()
                .map(src -> reconstruct(src, lookups))
                .toList();
        if (!lookups.isEmpty()) {
            // 좌표값은 로그 금지(위치 민감정보) — 유니크 좌표 수·총 소요시간만.
            // 좌표당 호출 수·개별 소요시간은 GeocodingService가 lookup 단위로 남긴다.
            log.info("geocoding enrich: items={} coordinates={} totalMs={}",
                    sourceItems.size(), lookups.size(), (System.nanoTime() - startNanos) / 1_000_000);
        }
        return enriched;
    }

    private SourceItemDto reconstruct(SourceItemDto src, Map<Coordinate, GeoPlace> lookups) {
        TimelineItemPayload reconstructed = switch (src.payload()) {
            case LocationPayload location -> {
                GeoPlace geo = lookup(location.latitude(), location.longitude(), lookups);
                yield new LocationPayload(
                        location.latitude(), location.longitude(),
                        geo.address(), geo.places(), durationText(src.startAt(), src.endAt()));
            }
            case MovementPayload movement -> new MovementPayload(
                    reconstructEndpoint(movement.start(), lookups),
                    reconstructEndpoint(movement.end(), lookups),
                    movement.transports(), movement.distanceMeters());
            default -> src.payload();
        };
        if (reconstructed == src.payload()) {
            return src;
        }
        // rawId 등 envelope 필드는 그대로 보존 — 여기서 떨어지면 검증(이미 통과)이 못 잡고 DB NOT NULL에서 500이 난다.
        return new SourceItemDto(src.itemType(), src.rawId(), src.startAt(), src.endAt(), reconstructed);
    }

    private MovementEndpoint reconstructEndpoint(MovementEndpoint endpoint, Map<Coordinate, GeoPlace> lookups) {
        GeoPlace geo = lookup(endpoint.latitude(), endpoint.longitude(), lookups);
        return new MovementEndpoint(
                endpoint.latitude(), endpoint.longitude(), geo.address(), geo.places());
    }

    private GeoPlace lookup(double latitude, double longitude, Map<Coordinate, GeoPlace> lookups) {
        return lookups.computeIfAbsent(new Coordinate(latitude, longitude), coordinate -> {
            try {
                return geocodingService.lookup(coordinate.latitude(), coordinate.longitude());
            } catch (RuntimeException e) {
                // 좌표는 위치 민감정보라 로그에 남기지 않는다 — 실패 사실만(상세 강등은 GeocodingService가 1차 방어).
                log.warn("geocoding lookup failed: {}", e.getClass().getSimpleName());
                return GeoPlace.EMPTY;
            }
        });
    }

    /** LOCATION 머문 시간 텍스트("1시간45분"). 서버 파생값 — startAt/endAt로 계산하고 계산 불가(endAt 없음 등)면 null. */
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

    private record Coordinate(double latitude, double longitude) {
    }
}
