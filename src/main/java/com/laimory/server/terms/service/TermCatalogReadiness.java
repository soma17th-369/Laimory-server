package com.laimory.server.terms.service;

import com.laimory.server.terms.TermStage;
import com.laimory.server.terms.TermTimes;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.repository.TermDocumentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 약관 catalog 준비 상태 검사 — seed 존재와 {@link TermType} 기대 종류 커버리지의 단일 판정 지점.
 *
 * <p>기동 시 {@link TermType}에 선언된 모든 종류의 seed 존재(미래 효력 포함)와 모든 행의
 * {@code term_type} literal·{@code content_url}
 * 형식을 검사하고, 누락·잘못된 값·현재 유효 필수 문서 집합 불완전을 bounded log와 metric으로 경보한다 —
 * 기동과 공개 조회는 막지 않는다. 로그 수위는 상태 성격으로 가른다: 테이블이 완전히 빈 pre-activation
 * 상태(법무 원문 대기 — 예정된 미준비)는 WARN, seed 행이 존재하는데 틀렸거나(종류 누락·미지
 * literal·잘못된 URL) ready였다가 퇴행한 경우는 ERROR(운영 경보 대상)다. gauge는 수위와 무관하게 동일하게
 * 기록한다(대시보드 추적).
 *
 * <p>판정은 전 종류 current 요약을 한 쿼리로 뜬 catalog snapshot 위에서 stage·조건부 단위로 메모리
 * 계산한다. 호출 지점은 기동 검증(과 테스트)뿐이다 — 요청 경로 enforcement gate는 #436에서 제거됐고,
 * ready gauge 2종은 마지막 기동 검증이 기록한 기동 시점 값만 유지한다.
 *
 * <p>로그는 상태 전이에서만 남기고(bounded) 상태는 stage와 조건부 문서로 분리된 ready gauge가 담당한다.
 */
@Slf4j
@Component
public class TermCatalogReadiness {

    static final String CATALOG_READY_GAUGE = "laimory.terms.catalog.ready";
    static final String CONDITIONAL_CATALOG_READY_GAUGE = "laimory.terms.conditional.catalog.ready";

    private final TermDocumentRepository termDocumentRepository;
    private final TermDocumentService termDocumentService;
    private final Clock clock;

    private final Map<TermStage, AtomicInteger> stageReadyGauges = new EnumMap<>(TermStage.class);
    private final Map<TermStage, AtomicBoolean> notReadyLogged = new EnumMap<>(TermStage.class);
    private final Map<TermType, AtomicInteger> conditionalReadyGauges = new EnumMap<>(TermType.class);
    private final Map<TermType, AtomicBoolean> conditionalNotReadyLogged = new EnumMap<>(TermType.class);

    public TermCatalogReadiness(TermDocumentRepository termDocumentRepository,
                                TermDocumentService termDocumentService,
                                Clock clock,
                                MeterRegistry meterRegistry) {
        this.termDocumentRepository = termDocumentRepository;
        this.termDocumentService = termDocumentService;
        this.clock = clock;
        for (TermStage stage : TermStage.values()) {
            AtomicInteger readyState = new AtomicInteger(0);
            stageReadyGauges.put(stage, readyState);
            meterRegistry.gauge(CATALOG_READY_GAUGE, Tags.of("stage", stage.name()), readyState);
            notReadyLogged.put(stage, new AtomicBoolean(false));
        }
        TermType locationTerms = TermType.LOCATION_BASED_SERVICE_TERMS;
        AtomicInteger locationReadyState = new AtomicInteger(0);
        conditionalReadyGauges.put(locationTerms, locationReadyState);
        meterRegistry.gauge(CONDITIONAL_CATALOG_READY_GAUGE,
                Tags.of("term_type", locationTerms.name()), locationReadyState);
        conditionalNotReadyLogged.put(locationTerms, new AtomicBoolean(false));
    }

    /** stage catalog 판정 결과 — ready가 아니면 그 stage의 필수 종류 current 집합이 불완전하다. */
    public record StageCatalog(boolean ready, List<TermDocumentSummary> currentEnforcedDocuments) {
    }

    /** 조건부 약관 하나의 catalog 판정 결과 — ready가 아니면 current 문서가 없다. */
    public record ConditionalTermCatalog(boolean ready, Optional<TermDocumentSummary> currentDocument) {
    }

    /** 전 종류 current 요약 — 한 판정 회차의 stage·조건부 계산이 공유하는 판정 권위다. */
    private record CatalogSnapshot(Map<TermType, TermDocumentSummary> currentByType) {
    }

    /**
     * stage 준비 상태와 현재 필수 문서 집합을 함께 계산한다. 준비 조건: 필수 대상 종류 전부에
     * 현재 문서가 있다. 기동 검증·테스트용 — 주어진 시각으로 캐시 없이 조회한다.
     */
    public StageCatalog checkStage(TermStage stage, LocalDateTime nowKst) {
        return judgeStage(stage, loadSnapshot(nowKst));
    }

    private StageCatalog judgeStage(TermStage stage, CatalogSnapshot snapshot) {
        List<TermType> enforcedTypes = enforcedTypesOf(stage);
        List<TermDocumentSummary> currentDocuments = enforcedTypes.stream()
                .map(snapshot.currentByType()::get)
                .filter(Objects::nonNull)
                .toList();

        boolean ready = currentDocuments.size() == enforcedTypes.size();
        publishStageState(stage, ready, currentDocuments.isEmpty());
        return new StageCatalog(ready, currentDocuments);
    }

    ConditionalTermCatalog checkConditionalTerm(TermType termType, LocalDateTime nowKst) {
        requireConditional(termType);
        return judgeConditionalTerm(termType, loadSnapshot(nowKst));
    }

    private ConditionalTermCatalog judgeConditionalTerm(TermType termType, CatalogSnapshot snapshot) {
        Optional<TermDocumentSummary> currentDocument =
                Optional.ofNullable(snapshot.currentByType().get(termType));
        boolean ready = currentDocument.isPresent();
        publishConditionalState(termType, ready);
        return new ConditionalTermCatalog(ready, currentDocument);
    }

    /** 전 종류 current 요약 1쿼리 — load 자체는 어떤 stage/조건부 상태도 발행하지 않는다(판정이 발행). */
    private CatalogSnapshot loadSnapshot(LocalDateTime nowKst) {
        Map<TermType, TermDocumentSummary> currentByType = new EnumMap<>(TermType.class);
        termDocumentService.findCurrentSummaries(List.of(TermType.values()), nowKst)
                .forEach(summary -> currentByType.put(summary.termType(), summary));
        return new CatalogSnapshot(currentByType);
    }

    /** 기동 정합성 검사 — seed 누락·미지 literal·잘못된 URL·stage 미준비를 경보하되 기동은 막지 않는다. */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyCatalogOnStartup() {
        List<String> problems = new ArrayList<>();
        boolean seeded;
        try {
            List<TermDocumentRepository.TermCatalogRow> rows = termDocumentRepository.findCatalogRows();
            seeded = !rows.isEmpty();
            Set<String> seededTypes = rows.stream()
                    .map(TermDocumentRepository.TermCatalogRow::getTermType)
                    .collect(Collectors.toSet());
            for (TermType type : TermType.values()) {
                if (!seededTypes.contains(type.name())) {
                    problems.add("missing seed for termType=" + type.name());
                }
            }
            for (TermDocumentRepository.TermCatalogRow row : rows) {
                validateRow(row, problems);
            }
            LocalDateTime nowKst = TermTimes.kstWallClock(clock.instant());
            for (TermStage stage : TermStage.values()) {
                if (!checkStage(stage, nowKst).ready()) {
                    problems.add("stage not ready (incomplete current required set): " + stage.name());
                }
            }
            TermType locationTerms = TermType.LOCATION_BASED_SERVICE_TERMS;
            if (!checkConditionalTerm(locationTerms, nowKst).ready()) {
                problems.add("conditional term not ready: " + locationTerms.name());
            }
        } catch (RuntimeException e) {
            log.error("term catalog startup verification failed", e);
            return;
        }
        if (!seeded) {
            // seed 전(테이블 완전 비어있음)은 법무 원문 대기 중의 예정된 미준비 상태다 — 경보(ERROR)가
            // 아니라 WARN 1줄로만 알린다(반복 기동 경보 소음 방지). 행이 하나라도 생기면 아래 ERROR 경로다.
            log.warn("term catalog not seeded yet — public terms queries stay empty until activation (pre-activation state)");
        } else if (problems.isEmpty()) {
            log.info("term catalog verified: all {} term types seeded", TermType.values().length);
        } else {
            // 경보 1줄(bounded) — 기동·공개 조회는 계속된다(잘못된 seed는 공개 조회에서 조용히 빠진다).
            log.error("term catalog inconsistent: {}", String.join("; ", problems));
        }
    }

    private static void validateRow(TermDocumentRepository.TermCatalogRow row, List<String> problems) {
        try {
            TermType.valueOf(row.getTermType());
        } catch (IllegalArgumentException e) {
            problems.add("unknown termType literal in term_documents: " + row.getTermType());
            return;
        }
        if (!isPublishedPageUrl(row.getContentUrl())) {
            // 운영 seed가 넣는 문자열이라 형식만 본다 — 게시 host는 정책이 아니라 운영 선택이고,
            // page가 실제로 200인지는 배포 게이트가 확인한다(요청·기동 중 HTTP 조회 금지).
            problems.add("invalid contentUrl for termType=" + row.getTermType()
                    + " (must be an absolute https URI): " + row.getContentUrl());
        }
    }

    private static boolean isPublishedPageUrl(String contentUrl) {
        if (contentUrl == null || contentUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(contentUrl);
            return uri.isAbsolute() && "https".equals(uri.getScheme()) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * 상태 gauge 갱신 + 전이 시에만 로그(bounded — not-ready 지속 중 반복 없음). not-ready 전이의 수위는
     * catalog 성격으로 가른다: 이 stage의 current 후보가 0건이고 테이블 전체도 빈 pre-activation 상태면
     * WARN(예정된 미준비 — seed 전 소음 방지), 그 외(행이 있는데 틀림·ready였다가 퇴행)는 ERROR다.
     * 전체 행 수 확인은 전이 시점에만 수행한다. gauge는 수위와 무관하게 0/1을 기록한다.
     */
    private void publishStageState(TermStage stage, boolean ready, boolean noCurrentCandidates) {
        stageReadyGauges.get(stage).set(ready ? 1 : 0);
        AtomicBoolean logged = notReadyLogged.get(stage);
        if (!ready && logged.compareAndSet(false, true)) {
            if (noCurrentCandidates && termDocumentRepository.count() == 0) {
                log.warn("term catalog not seeded yet for stage {} — required set stays incomplete until activation",
                        stage.name());
            } else {
                log.error("term catalog not ready for stage {} — required set stays incomplete until "
                        + "seed/activation is fixed", stage.name());
            }
        } else if (ready && logged.compareAndSet(true, false)) {
            log.info("term catalog recovered for stage {}", stage.name());
        }
    }

    private void publishConditionalState(TermType termType, boolean ready) {
        conditionalReadyGauges.get(termType).set(ready ? 1 : 0);
        AtomicBoolean logged = conditionalNotReadyLogged.get(termType);
        if (!ready && logged.compareAndSet(false, true)) {
            if (termDocumentRepository.count() == 0) {
                log.warn("conditional term catalog not seeded yet for {} — its current document stays missing until activation",
                        termType.name());
            } else {
                log.error("conditional term catalog not ready for {} — its current document stays missing until "
                        + "seed/activation is fixed", termType.name());
            }
        } else if (ready && logged.compareAndSet(true, false)) {
            log.info("conditional term catalog recovered for {}", termType.name());
        }
    }

    private static void requireConditional(TermType termType) {
        if (termType != TermType.LOCATION_BASED_SERVICE_TERMS) {
            throw new IllegalArgumentException("termType is not conditional: " + termType.name());
        }
    }

    private static List<TermType> enforcedTypesOf(TermStage stage) {
        return switch (stage) {
            case LOGIN -> List.of(TermType.TERMS_OF_SERVICE);
            case TIMELINE_FIRST_CREATE -> List.of(
                    TermType.SENSITIVE_INFORMATION_CONSENT,
                    TermType.THIRD_PARTY_PROVISION_CONSENT,
                    TermType.CROSS_BORDER_TRANSFER_CONSENT);
        };
    }
}
