package com.laimory.server.terms.service;

import com.laimory.server.terms.TermStage;
import com.laimory.server.terms.TermTimes;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.repository.TermDocumentRepository;
import io.micrometer.core.instrument.Counter;
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
 * 상태(법무 원문 대기 — 예정된 fail-open)는 WARN, seed 행이 존재하는데 틀렸거나(종류 누락·미지
 * literal·잘못된 URL) ready였다가 퇴행한 경우는 ERROR(운영 경보 대상)다. gauge/counter는 수위와 무관하게 동일하게
 * 기록한다(대시보드 추적).
 *
 * <p>runtime enforcement는 요청마다 {@link #checkStage(TermStage, LocalDateTime)}로 DB 권위를 직접
 * 조회한다(임의 TTL cache 없음 — activation 즉시 판정 반영). 기대 필수 종류 중 하나라도 current 문서가
 * 없는 stage는 부분 강제하지 않고 준비되지 않은 catalog로 표시한다 — gate는 stage 전체를 fail-open하고,
 * 잘못된 seed가 5xx나 전 회원 차단으로 이어지지 않게 한다. {@code required=false} 조건부 문서는
 * 종류별로 따로 판정해 누락 시 그 gate만 fail-open한다.
 *
 * <p>로그는 상태 전이에서만 남기고(bounded — 요청마다 반복하지 않음) 발생 빈도는
 * stage와 조건부 문서에 분리된 fail-open counter와 ready gauge가 담당한다.
 */
@Slf4j
@Component
public class TermCatalogReadiness {

    static final String CATALOG_READY_GAUGE = "laimory.terms.catalog.ready";
    static final String GATE_FAIL_OPEN_COUNTER = "laimory.terms.gate.fail_open";
    static final String CONDITIONAL_CATALOG_READY_GAUGE = "laimory.terms.conditional.catalog.ready";
    static final String CONDITIONAL_GATE_FAIL_OPEN_COUNTER = "laimory.terms.conditional.gate.fail_open";

    private final TermDocumentRepository termDocumentRepository;
    private final TermDocumentService termDocumentService;
    private final Clock clock;

    private final Map<TermStage, AtomicInteger> stageReadyGauges = new EnumMap<>(TermStage.class);
    private final Map<TermStage, Counter> failOpenCounters = new EnumMap<>(TermStage.class);
    private final Map<TermStage, AtomicBoolean> notReadyLogged = new EnumMap<>(TermStage.class);
    private final Map<TermType, AtomicInteger> conditionalReadyGauges = new EnumMap<>(TermType.class);
    private final Map<TermType, Counter> conditionalFailOpenCounters = new EnumMap<>(TermType.class);
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
            failOpenCounters.put(stage, Counter.builder(GATE_FAIL_OPEN_COUNTER)
                    .description("Terms gate skipped because the stage catalog is not ready (fail-open)")
                    .tag("stage", stage.name())
                    .register(meterRegistry));
            notReadyLogged.put(stage, new AtomicBoolean(false));
        }
        for (TermType termType : TermType.values()) {
            if (termType.required()) {
                continue;
            }
            AtomicInteger readyState = new AtomicInteger(0);
            conditionalReadyGauges.put(termType, readyState);
            meterRegistry.gauge(CONDITIONAL_CATALOG_READY_GAUGE,
                    Tags.of("term_type", termType.name()), readyState);
            conditionalFailOpenCounters.put(termType, Counter.builder(CONDITIONAL_GATE_FAIL_OPEN_COUNTER)
                    .description("Conditional terms gate skipped because its current document is unavailable")
                    .tag("term_type", termType.name())
                    .register(meterRegistry));
            conditionalNotReadyLogged.put(termType, new AtomicBoolean(false));
        }
    }

    /** stage catalog 판정 결과 — 준비되지 않았으면 enforcement가 stage 전체를 fail-open한다. */
    public record StageCatalog(boolean ready, List<TermDocumentSummary> currentRequiredDocuments) {
    }

    /** 조건부 약관 하나의 catalog 판정 결과 — 누락 시 해당 조건부 gate만 fail-open한다. */
    public record ConditionalTermCatalog(boolean ready, Optional<TermDocumentSummary> currentDocument) {
    }

    /** 판정 시각은 지금 캡처한 instant의 KST 벽시계다. */
    public StageCatalog checkStage(TermStage stage) {
        return checkStage(stage, TermTimes.kstWallClock(clock.instant()));
    }

    /**
     * stage 준비 상태와 현재 필수 문서 집합을 함께 계산한다. 준비 조건:
     * 기대 필수 종류 전부에 현재 문서가 있다.
     */
    public StageCatalog checkStage(TermStage stage, LocalDateTime nowKst) {
        // 요청마다 도는 판정이라 판정에 쓰는 식별 요약만 조회한다.
        List<TermDocumentSummary> currentDocuments = termDocumentService.findCurrentSummaries(
                TermType.typesOf(stage), nowKst);

        Set<TermType> currentTypes = currentDocuments.stream()
                .map(TermDocumentSummary::termType)
                .collect(Collectors.toSet());

        boolean ready = currentTypes.containsAll(TermType.requiredTypesOf(stage));
        publishStageState(stage, ready, currentDocuments.isEmpty());
        List<TermDocumentSummary> currentRequired = currentDocuments.stream()
                .filter(summary -> summary.termType().required())
                .toList();
        return new StageCatalog(ready, currentRequired);
    }

    /** gate가 미준비 stage를 통과시킬 때 호출한다 — 발생량은 counter, 상세는 전이 로그가 담당한다. */
    public void recordFailOpen(TermStage stage) {
        failOpenCounters.get(stage).increment();
    }

    /** 조건부 약관의 현재 문서를 요청 시점 DB 권위로 조회한다. */
    public ConditionalTermCatalog checkConditionalTerm(TermType termType) {
        return checkConditionalTerm(termType, TermTimes.kstWallClock(clock.instant()));
    }

    ConditionalTermCatalog checkConditionalTerm(TermType termType, LocalDateTime nowKst) {
        requireConditional(termType);
        Optional<TermDocumentSummary> currentDocument = termDocumentService
                .findCurrentSummaries(List.of(termType), nowKst)
                .stream()
                .findFirst();
        boolean ready = currentDocument.isPresent();
        publishConditionalState(termType, ready);
        return new ConditionalTermCatalog(ready, currentDocument);
    }

    /** 조건부 gate가 문서 누락 때문에 통과할 때 호출한다. */
    public void recordConditionalFailOpen(TermType termType) {
        requireConditional(termType);
        conditionalFailOpenCounters.get(termType).increment();
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
            for (TermType termType : TermType.values()) {
                if (!termType.required() && !checkConditionalTerm(termType, nowKst).ready()) {
                    problems.add("conditional term not ready: " + termType.name());
                }
            }
        } catch (RuntimeException e) {
            log.error("term catalog startup verification failed", e);
            return;
        }
        if (!seeded) {
            // seed 전(테이블 완전 비어있음)은 법무 원문 대기 중의 예정된 fail-open 상태다 — 경보(ERROR)가
            // 아니라 WARN 1줄로만 알린다(반복 기동 경보 소음 방지). 행이 하나라도 생기면 아래 ERROR 경로다.
            log.warn("term catalog not seeded yet — enforcement fails open until activation (pre-activation state)");
        } else if (problems.isEmpty()) {
            log.info("term catalog verified: all {} term types seeded", TermType.values().length);
        } else {
            // 경보 1줄(bounded) — 기동·공개 조회는 계속되고 미준비 stage의 gate는 fail-open된다.
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
     * WARN(예정된 fail-open — seed 전 소음 방지), 그 외(행이 있는데 틀림·ready였다가 퇴행)는 ERROR다.
     * 전체 행 수 확인은 전이 시점에만 수행한다(요청마다 아님). gauge는 수위와 무관하게 0/1을 기록한다.
     */
    private void publishStageState(TermStage stage, boolean ready, boolean noCurrentCandidates) {
        stageReadyGauges.get(stage).set(ready ? 1 : 0);
        AtomicBoolean logged = notReadyLogged.get(stage);
        if (!ready && logged.compareAndSet(false, true)) {
            if (noCurrentCandidates && termDocumentRepository.count() == 0) {
                log.warn("term catalog not seeded yet for stage {} — enforcement fails open until activation",
                        stage.name());
            } else {
                log.error("term catalog not ready for stage {} — enforcement fails open until "
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
                log.warn("conditional term catalog not seeded yet for {} — only its gate fails open until activation",
                        termType.name());
            } else {
                log.error("conditional term catalog not ready for {} — only its gate fails open until "
                        + "seed/activation is fixed", termType.name());
            }
        } else if (ready && logged.compareAndSet(true, false)) {
            log.info("conditional term catalog recovered for {}", termType.name());
        }
    }

    private static void requireConditional(TermType termType) {
        if (termType.required()) {
            throw new IllegalArgumentException("termType is not conditional: " + termType.name());
        }
    }
}
