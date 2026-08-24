package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.geo.Coordinate;
import com.laimory.server.geo.GeoLookupOutcome;
import com.laimory.server.geo.GeoMetrics;
import com.laimory.server.geo.GeoPlace;
import com.laimory.server.geo.GeocodingService;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.payload.MovementEndpoint;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.payload.StayPayload;
import com.laimory.server.timeline.payload.TimelineItemPayload;
import com.laimory.server.timeline.photo.PhotoUrlService;
import com.laimory.server.timeline.service.GeoEnrichmentPolicy.CoordinateObservation;
import com.laimory.server.timeline.service.GeoEnrichmentPolicy.Decision;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 저장 전 payload 재구성: 서버 파생 필드는 클라 값을 무시하고 서버 값으로만 채운다
 * (mass assignment 방어 — 거절이 아니라 무시). STAY/MOVEMENT의 {@code address}/{@code places}는
 * 지오코딩 결과, {@code durationText}는 startAt/endAt 계산값, PHOTO의 {@code photoUrl}은 filename+subjectId로
 * 파생한 무서명 CloudFront 서빙 URL이다(AI가 서버간 입력 조회 API로 소비).
 * 저장은 payload 통짜 직렬화라 이 재구성본이 곧 저장본이다.
 *
 * <p><b>2-pass 구조</b>: ① 지오코딩 대상 좌표를 {@link LinkedHashSet}으로 dedupe 수집하고 ② 한 번에 병렬
 * 조회({@link GeocodingService#lookupAll}) 후 ③ 품질 판정을 통과하면 완성된 map으로 재구성한다.
 * 좌표가 하나도 없으면 lookupAll 자체를 생략한다. source 결과 순서와 envelope 필드는 그대로 보존한다.
 *
 * <p><b>조회 대상과 판정 축은 다르다</b>: 조회 대상은 STAY 좌표·MOVEMENT start/end에 <b>좌표를 가진 PHOTO</b>가
 * 합류한 합집합이다. 반면 시간순 관측({@link CoordinateObservation})은 STAY/MOVEMENT만 만든다 — PHOTO는
 * D1(실패율)에는 합류하지만 <b>D2(시간순 연속 실패 3) 축에는 관측을 만들지 않는다</b>. D2가 관측 단위로
 * 계수하는데 사진은 시간축에 조밀하게 뭉쳐, 같은 자리에서 찍은 사진 3장의 좌표 조회가 한 번만 실패해도
 * 하루 draft 전체가 502가 되기 때문이다. 하루 25개 안팎이 흩어진 STAY/MOVEMENT를 전제로 정한 임계값이라
 * 사진에는 의미가 달라진다. D1은 {@code outcomes.size()} 기반이라 합집합을 넘기면 자동으로 합류한다.
 *
 * <p><b>공개 입력 상한(D9/D17)</b>: rawId dedupe·기존 저장 item 제외 뒤 실제 lookup할 unique coordinate가
 * {@code app.geo.max-unique-coordinates}(기본 100)를 넘으면 외부 호출 전에 400/{@code -400}으로 거절한다.
 *
 * <p><b>부분 실패 판정(D1/D2/D7)</b>: materialize된 좌표별 최종 outcome으로 전체 실패 20% 초과 또는 시간순
 * 연속 3개 실패면 저장 전에 502(영구 포함 {@code -1015}, 아니면 {@code -1014})로 거절한다. 허용되면 성공
 * 좌표의 주소·장소는 보존하고 실패 좌표만 {@code address=null}·{@code places=[]} fallback으로 계속한다
 * (NON_NULL 직렬화라 실제 저장/AI JSON은 address key 생략·{@code places: []} — 새 public failure marker는
 * 만들지 않는다). enrichment 자체 버그는 partial로 강등하지 않고 그대로 전파한다(catch-all 500).
 */
@Slf4j
@Service
public class SourceItemEnrichmentService {

    private final GeocodingService geocodingService;
    private final PhotoUrlService photoUrlService;
    private final GeoMetrics geoMetrics;
    private final int maxUniqueCoordinates;

    public SourceItemEnrichmentService(
            GeocodingService geocodingService,
            PhotoUrlService photoUrlService,
            GeoMetrics geoMetrics,
            @Value("${app.geo.max-unique-coordinates:100}") int maxUniqueCoordinates) {
        if (maxUniqueCoordinates < 1) {
            throw new IllegalStateException(
                    "app.geo.max-unique-coordinates must be >= 1 but was " + maxUniqueCoordinates);
        }
        this.geocodingService = geocodingService;
        this.photoUrlService = photoUrlService;
        this.geoMetrics = geoMetrics;
        this.maxUniqueCoordinates = maxUniqueCoordinates;
    }

    /** {@code subjectId}는 PHOTO photoUrl의 full key 파생에 쓴다 — 저장될 row의 owner와 같아야 한다. */
    @WithSpan
    public List<SourceItemDto> enrich(List<SourceItemDto> sourceItems, UUID subjectId) {
        long startNanos = System.nanoTime();
        List<CoordinateObservation> observations = collectObservations(sourceItems);
        Set<Coordinate> coordinates = lookupCoordinates(observations, sourceItems);
        // 공개 입력 상한 — 외부 I/O 전 기존 validation 400/-400 경로(IllegalArgumentException).
        // 상한·개수만 메시지에 담는다(좌표 금지). sourceItems 배열 길이가 아니라 필터 뒤 unique 좌표 수 기준이다.
        if (coordinates.size() > maxUniqueCoordinates) {
            throw new IllegalArgumentException("unique geo coordinates exceed maximum "
                    + maxUniqueCoordinates + ": count=" + coordinates.size());
        }
        Map<Coordinate, GeoPlace> lookups = coordinates.isEmpty()
                ? Map.of()
                : lookupAndJudge(coordinates, observations);
        List<SourceItemDto> enriched = sourceItems.stream()
                .map(src -> reconstruct(src, lookups, subjectId))
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
     * <b>D2 시간축 관측</b>만 수집한다 — STAY 좌표와 MOVEMENT start/end뿐이고 PHOTO는 좌표가 있어도
     * 관측을 만들지 않는다(클래스 javadoc의 판정 축 분리 참고). source encounter order를 보존해 병렬 구독
     * 시작 순서를 결정적으로 유지한다(완료 순서는 비결정 — 판정에는 쓰지 않는다).
     * {@code startAt}은 검증 경계(requireValidSourceItems)가 필수를 보장한 뒤라 null 케이스가 없다.
     */
    private static List<CoordinateObservation> collectObservations(List<SourceItemDto> sourceItems) {
        List<CoordinateObservation> observations = new ArrayList<>();
        for (SourceItemDto src : sourceItems) {
            switch (src.payload()) {
                case StayPayload stay -> observations.add(new CoordinateObservation(
                        new Coordinate(stay.latitude(), stay.longitude()), src.startAt(), src.rawId(), 0));
                case MovementPayload movement -> {
                    observations.add(new CoordinateObservation(
                            new Coordinate(movement.start().latitude(), movement.start().longitude()),
                            src.startAt(), src.rawId(), 0));
                    // MOVEMENT END 시각은 endAt이 있으면 그 값, 없으면 startAt(best-known timestamp, D3).
                    LocalDateTime endObservedAt = src.endAt() != null ? src.endAt() : src.startAt();
                    observations.add(new CoordinateObservation(
                            new Coordinate(movement.end().latitude(), movement.end().longitude()),
                            endObservedAt, src.rawId(), 1));
                }
                default -> {
                }
            }
        }
        return observations;
    }

    /**
     * 실제로 조회할 unique 좌표 — D2 관측 좌표(STAY/MOVEMENT)에 좌표를 가진 PHOTO를 더한 합집합이다.
     * 관측 좌표를 먼저 넣어 기존 요청의 구독 시작 순서를 그대로 두고, PHOTO는 그 뒤에 source 순서로 붙인다
     * ({@link LinkedHashSet} — 결정적 순서). 같은 좌표를 STAY와 PHOTO가 공유하면 조회는 1회다.
     */
    private static Set<Coordinate> lookupCoordinates(List<CoordinateObservation> observations,
            List<SourceItemDto> sourceItems) {
        Set<Coordinate> coordinates = new LinkedHashSet<>();
        for (CoordinateObservation observation : observations) {
            coordinates.add(observation.coordinate());
        }
        for (SourceItemDto src : sourceItems) {
            if (src.payload() instanceof PhotoPayload photo && hasCoordinate(photo)) {
                coordinates.add(new Coordinate(photo.latitude(), photo.longitude()));
            }
        }
        return coordinates;
    }

    /**
     * PHOTO 좌표는 선택이라 둘 다 있을 때만 조회 대상이다. 부분·범위 밖 좌표는 입력 경계
     * (TimelineDraftTaskService)가 이미 400으로 걸러낸 뒤다.
     */
    private static boolean hasCoordinate(PhotoPayload photo) {
        return photo.latitude() != null && photo.longitude() != null;
    }

    /**
     * unique 좌표를 병렬 조회하고 materialize된 aggregate로 D1/D2/D7을 판정한다. 거절이면 DB/Redis/task
     * 저장 前이라 durable state가 없는 상태로 502를 던진다(롤백 불필요). 허용이면 모든 unique 좌표 key를 가진
     * map을 반환한다(D6) — 성공은 실제 값, 실패는 D5 fallback({@code address=null}·{@code places=[]}).
     */
    private Map<Coordinate, GeoPlace> lookupAndJudge(Set<Coordinate> coordinates,
            List<CoordinateObservation> observations) {
        long batchStartNanos = System.nanoTime();
        try {
            Map<Coordinate, GeoLookupOutcome> outcomes = geocodingService.lookupAll(coordinates);
            // D6: 성공적으로 반환된 aggregate는 입력 unique set과 key가 정확히 같아야 한다. provider가
            // Mono.empty를 내거나 수집 버그로 key가 빠진 상태를 성공으로 오인하면 reconstruct NPE 또는
            // 시간순 판정 왜곡이 생기므로 programming error로 즉시 드러낸다(좌표는 메시지에 넣지 않음).
            if (outcomes == null || !outcomes.keySet().equals(coordinates)
                    || outcomes.values().stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalStateException("geo lookup outcomes do not match unique coordinate inputs");
            }
            long failedUnique = outcomes.values().stream()
                    .filter(GeoLookupOutcome.Failure.class::isInstance)
                    .count();
            String failureKind = batchFailureKind(outcomes);
            Decision decision = GeoEnrichmentPolicy.decide(observations, outcomes);
            Duration took = Duration.ofNanos(System.nanoTime() - batchStartNanos);
            if (decision instanceof Decision.Rejected rejected) {
                geoMetrics.recordBatch("rejected", failureKind, took);
                // 개수·규칙만 로그(좌표 금지). 상세 실패 원인은 provider가 콜 단위로 이미 남겼다.
                log.warn("geo batch rejected: rule={} uniqueCoordinates={} failedUnique={} errorCode={}",
                        rejected.rule(), outcomes.size(), failedUnique, rejected.type().code());
                throw new BusinessException(rejected.type());
            }
            Map<Coordinate, GeoPlace> lookups = new HashMap<>();
            outcomes.forEach((coordinate, outcome) -> lookups.put(coordinate, switch (outcome) {
                case GeoLookupOutcome.Success success -> java.util.Objects.requireNonNull(
                        success.place(), "successful geo outcome requires place");
                // D5 fallback: 위경도·시간은 source가 유지하고 주소만 비운다. NON_NULL 직렬화로 실제 JSON은
                // address key 생략·places=[]가 된다. 정상 "주소 없음"(200+documents=[])과 같은 wire shape지만
                // 내부 outcome 구분은 위 metric이 담당한다. noop의 null/null(EMPTY)과도 구분된다.
                case GeoLookupOutcome.Failure ignored -> new GeoPlace(null, List.of());
            }));
            geoMetrics.recordBatch(failedUnique == 0 ? "success" : "partial", failureKind, took);
            return lookups;
        } catch (BusinessException rejected) {
            // 위에서 rejected metric을 이미 기록한 제품 판정 — bug로 중복 계수하지 않는다.
            throw rejected;
        } catch (RuntimeException bug) {
            // 예상된 provider 실패는 outcome으로 materialize돼 여기 오지 않는다 — 도달하면 코드 결함이므로
            // partial로 강등하지 않고 기존 catch-all 500으로 전파한다(D4).
            geoMetrics.recordBatch("bug", "none", Duration.ofNanos(System.nanoTime() - batchStartNanos));
            throw bug;
        }
    }

    /** batch metric의 {@code failure_kind} tag: {@code none|transient|permanent|mixed}. */
    private static String batchFailureKind(Map<Coordinate, GeoLookupOutcome> outcomes) {
        boolean anyTransient = false;
        boolean anyPermanent = false;
        for (GeoLookupOutcome outcome : outcomes.values()) {
            if (outcome instanceof GeoLookupOutcome.Failure failure) {
                if (failure.failure().clientMayRetryLater()) {
                    anyTransient = true;
                } else {
                    anyPermanent = true;
                }
            }
        }
        if (anyTransient && anyPermanent) {
            return "mixed";
        }
        if (anyTransient) {
            return "transient";
        }
        if (anyPermanent) {
            return "permanent";
        }
        return "none";
    }

    private SourceItemDto reconstruct(SourceItemDto src, Map<Coordinate, GeoPlace> lookups, UUID subjectId) {
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
            case PhotoPayload photo -> {
                // 좌표가 없는 사진은 조회 대상이 아니므로 enrich 필드도 비운다(NON_NULL — key 생략).
                GeoPlace geo = hasCoordinate(photo)
                        ? lookups.get(new Coordinate(photo.latitude(), photo.longitude()))
                        : GeoPlace.EMPTY;
                yield new PhotoPayload(
                        photo.filename(), photo.clientPhotoUri(), photo.latitude(), photo.longitude(),
                        photo.description(), geo.address(), geo.places(),
                        photoUrlService.buildSubjectUrl(photo.filename(), subjectId));
            }
            default -> src.payload();
        };
        if (reconstructed == src.payload()) {
            return src;
        }
        // rawId 등 envelope 필드는 그대로 보존 — 여기서 떨어지면 검증(이미 통과)이 못 잡고 DB NOT NULL에서 500이 난다.
        return new SourceItemDto(src.itemType(), src.rawId(), src.startAt(), src.endAt(), reconstructed);
    }

    private MovementEndpoint reconstructEndpoint(MovementEndpoint endpoint, Map<Coordinate, GeoPlace> lookups) {
        // 수집(collectObservations)과 같은 규칙으로 키를 만들므로 map에 항상 존재한다(D6).
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
