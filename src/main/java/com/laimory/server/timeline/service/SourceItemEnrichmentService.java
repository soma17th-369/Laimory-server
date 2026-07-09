package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.geo.GeoPlace;
import com.laimory.server.geo.GeocodingService;
import com.laimory.server.geo.MapPlaceLookupException;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.payload.LocationPayload;
import com.laimory.server.timeline.payload.MovementEndpoint;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.payload.TimelineItemPayload;
import com.laimory.server.timeline.photo.PhotoUrlService;
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
 * 저장 전 payload 재구성: 서버 파생 필드는 클라 값을 무시하고 서버 값으로만 채운다
 * (mass assignment 방어 — 거절이 아니라 무시). LOCATION/MOVEMENT의 {@code address}/{@code places}는
 * 지오코딩 결과, {@code durationText}는 startAt/endAt 계산값, PHOTO의 {@code photoUrl}은 filename+userId로
 * 파생한 무서명 CloudFront 서빙 URL이다(AI가 DB payload에서 HTTP GET으로 소비).
 * 저장은 payload 통짜 직렬화라 이 재구성본이 곧 저장본이다.
 *
 * <p>지오코딩이 끝내 실패하면(재시도 provider 내부 소진) 해당 draft 생성을 502(ERROR_1014)로 실패시킨다 —
 * 저품질 타임라인을 굽지 않는다(좌표만 있고 주소·장소가 없으면 AI가 장소를 알 수 없다). 한 좌표가 실패하면
 * stream이 short-circuit돼 enrich 전체가 throw한다. 같은 좌표는 요청 내 1회만 조회한다(좌표당 카카오 6콜이라
 * dedupe 필수). 좌표는 검증 경계(requireValidSourceItems)가 필수를 보장한 뒤라 null 케이스가 없다.
 */
@Service
@RequiredArgsConstructor
public class SourceItemEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(SourceItemEnrichmentService.class);

    private final GeocodingService geocodingService;
    private final PhotoUrlService photoUrlService;

    /** {@code userId}는 PHOTO photoUrl의 full key 파생에 쓴다 — 저장될 row의 user_id와 같은 사용자여야 한다. */
    public List<SourceItemDto> enrich(List<SourceItemDto> sourceItems, long userId) {
        long startNanos = System.nanoTime();
        Map<Coordinate, GeoPlace> lookups = new HashMap<>();
        List<SourceItemDto> enriched = sourceItems.stream()
                .map(src -> reconstruct(src, lookups, userId))
                .toList();
        if (!lookups.isEmpty()) {
            // 좌표값은 로그 금지(위치 민감정보) — 유니크 좌표 수·총 소요시간만.
            // 좌표당 호출 수·개별 소요시간은 GeocodingService가 lookup 단위로 남긴다.
            log.info("geocoding enrich: items={} coordinates={} totalMs={}",
                    sourceItems.size(), lookups.size(), (System.nanoTime() - startNanos) / 1_000_000);
        }
        return enriched;
    }

    private SourceItemDto reconstruct(SourceItemDto src, Map<Coordinate, GeoPlace> lookups, long userId) {
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
        GeoPlace geo = lookup(endpoint.latitude(), endpoint.longitude(), lookups);
        return new MovementEndpoint(
                endpoint.latitude(), endpoint.longitude(), geo.address(), geo.places());
    }

    private GeoPlace lookup(double latitude, double longitude, Map<Coordinate, GeoPlace> lookups) {
        return lookups.computeIfAbsent(new Coordinate(latitude, longitude), coordinate -> {
            try {
                return geocodingService.lookup(coordinate.latitude(), coordinate.longitude());
            } catch (MapPlaceLookupException e) {
                // 지오코딩이 끝내 실패하면(재시도 provider 내부 소진) 저품질 타임라인을 굽지 않고 draft 생성을 502로 loud fail한다.
                // enrich가 taskId 생성·저장 前이라 아무것도 안 만들어져 롤백 불필요. 원인 상세는 provider가 이미 로깅했다(좌표는 로그 금지).
                // broad RuntimeException은 잡지 않는다 — enrichment 자체 버그(NPE 등)는 catch-all 500이 맞고 502로 가리면 안 된다.
                throw new BusinessException(ErrorCode.ERROR_1014);
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
